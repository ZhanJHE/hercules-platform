/**
 * 对话领域 API（阶段 D/G）：POST SSE 流式（fetch + ReadableStream，EventSource 不支持 POST）。
 *
 * <p>事件流：token*（正文分片）→ meta（结构化数据 JSON）→ 完成；error 事件为兜底提示。
 * 401 时静默刷新一次并重放（与 axios 拦截器同语义，fetch 不经过 axios）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { getAccessToken } from './tokenBox'
import { createRefreshCoordinator } from './authRefresh'
import { SseStreamParser } from '@/utils/sseParser'
import axios from 'axios'
import type { ChatMeta } from '@/types'

export interface ChatStreamHandlers {
  onToken: (token: string) => void
  onMeta: (meta: ChatMeta) => void
  onError: (message: string) => void
  onDone: () => void
}

/** 裸 axios：仅用于 401 刷新（与 request.ts 同一协调器语义）。 */
const coordinator = createRefreshCoordinator(
  (url, body) => axios.post(url, body),
  () => {
    window.location.assign('/login')
  },
)

/**
 * 发起对话并消费 SSE 流，直至服务端完成或 signal 中断。
 *
 * @param sessionId 会话标识
 * @param message   用户消息
 * @param handlers  事件回调
 * @param signal    中断控制器（停止生成按钮）
 */
export async function streamChat(sessionId: string, message: string, handlers: ChatStreamHandlers, signal?: AbortSignal): Promise<void> {
  const send = () => fetch('/api/v1/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getAccessToken() ?? ''}`,
    },
    body: JSON.stringify({ sessionId, message }),
    signal,
  })

  let response = await send()
  if (response.status === 401) {
    await coordinator.refreshOnce()
    response = await send()
  }
  if (!response.ok || !response.body) {
    handlers.onError(`对话请求失败（HTTP ${response.status}）`)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseStreamParser()

  const dispatch = (event: { event: string; data: string }): void => {
    if (event.event === 'token') {
      handlers.onToken(event.data)
    } else if (event.event === 'meta') {
      try {
        handlers.onMeta(JSON.parse(event.data) as ChatMeta)
      } catch {
        // meta 解析失败不中断正文
      }
    } else if (event.event === 'error') {
      handlers.onError(event.data)
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      parser.finish().forEach(dispatch)
      handlers.onDone()
      return
    }
    parser.push(decoder.decode(value, { stream: true })).forEach(dispatch)
  }
}
