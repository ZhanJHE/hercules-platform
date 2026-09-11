/**
 * 对话 store 单元测试（阶段 G/越权收敛）：会话重置与会话失效（403）恢复路径。
 *
 * <p>会话归属由服务端强制（sessionId 首次使用时绑定用户），本文件只验证客户端的
 * 配合行为：重置会换发新 sessionId 并复位状态机；收到 403 回调后重建会话并提示重发。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 拦截对话 API：由用例自行驱动 handlers（避免真实 fetch 与 SSE 解析）
const { streamChatMock } = vi.hoisted(() => ({ streamChatMock: vi.fn() }))
vi.mock('@/api/chat', () => ({ streamChat: streamChatMock }))

import { useChatStore } from './chat'

/** streamChat 的 handler 形状（仅用到本文件关心的两个回调）。 */
interface TestHandlers {
  onDone: () => void
  onSessionInvalid?: () => void
}

describe('useChatStore（会话重置与失效恢复）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    streamChatMock.mockReset()
  })

  it('reset 换发新 sessionId 并清空消息，同时复位状态机', () => {
    const store = useChatStore()
    const first = store.sessionId
    store.messages.push({ role: 'user', content: '你好' })
    store.sending = true

    store.reset()

    expect(store.sessionId).not.toBe(first)
    expect(store.messages).toEqual([])
    // 复位状态机：否则「停止生成」按钮与输入禁用会永久停留在发送态
    expect(store.sending).toBe(false)
    expect(store.controller).toBeNull()
  })

  it('会话失效（403）时重建会话、提示重发，且重发使用新 sessionId', async () => {
    const store = useChatStore()
    const staleSessionId = store.sessionId

    // 服务端判定会话归属他人：api 层回调 onSessionInvalid
    streamChatMock.mockImplementation(async (_sid: string, _msg: string, handlers: TestHandlers) => {
      handlers.onSessionInvalid?.()
    })
    await store.send('推荐几门课')

    expect(store.sessionId).not.toBe(staleSessionId)
    expect(store.sending).toBe(false)
    // 旧的占位消息已随 reset 清空，仅保留一条失效提示
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0].role).toBe('assistant')
    expect(store.messages[0].error).toBe(true)
    expect(store.messages[0].content).toContain('会话已失效')

    // 用户重发：必须使用重建后的会话 ID（否则会再次命中 403）
    streamChatMock.mockImplementation(async (_sid: string, _msg: string, handlers: TestHandlers) => {
      handlers.onDone()
    })
    await store.send('再试一次')

    const calls = streamChatMock.mock.calls
    const resendSessionId = calls[calls.length - 1]?.[0]
    expect(resendSessionId).toBe(store.sessionId)
    expect(resendSessionId).not.toBe(staleSessionId)
  })
})
