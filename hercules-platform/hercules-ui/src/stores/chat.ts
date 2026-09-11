/**
 * 对话 store（阶段 D/G）：消息流式状态机 + SSE 消费。
 *
 * <p>状态机：idle → sending（user 消息入列 + assistant 占位流式接收）→ idle。
 * meta 事件挂到当前 assistant 消息（候选卡/确认按钮渲染依据）；error 事件写入消息体。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { defineStore } from 'pinia'
import { streamChat } from '@/api/chat'
import type { ChatMessage, ChatMeta } from '@/types'

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: crypto.randomUUID(),
    messages: [] as ChatMessage[],
    sending: false,
    /** 中断控制器（停止生成按钮） */
    controller: null as AbortController | null,
  }),
  actions: {
    /** 发送一条用户消息并消费 SSE 流。 */
    async send(message: string): Promise<void> {
      if (this.sending || !message.trim()) {
        return
      }
      this.sending = true
      this.messages.push({ role: 'user', content: message })
      this.messages.push({ role: 'assistant', content: '', streaming: true })
      // 占位消息入列后从数组取 reactive proxy：后续逐 token 修改必须经 proxy 才触发渲染
      const assistant = this.messages[this.messages.length - 1]

      this.controller = new AbortController()
      const handlers = {
        onToken: (token: string) => {
          assistant.content += token
        },
        onMeta: (meta: ChatMeta) => {
          assistant.meta = { ...assistant.meta, ...meta }
        },
        onError: (message: string) => {
          assistant.content = assistant.content || message
          assistant.error = true
        },
        onDone: () => {
          assistant.streaming = false
          this.sending = false
          this.controller = null
        },
        onSessionInvalid: () => {
          // 服务端判定会话归属他人：重建会话（换 sessionId + 清空消息）后提示用户重发
          this.reset()
          this.messages.push({
            role: 'assistant',
            content: '会话已失效，请重新发送。',
            error: true,
          })
        },
      }
      try {
        await streamChat(this.sessionId, message, handlers, this.controller.signal)
      } catch (err) {
        // AbortError = 用户主动停止；其余网络错误降级提示
        if ((err as Error).name !== 'AbortError') {
          assistant.content = assistant.content || '对话服务暂时不可用，请稍后再试。'
          assistant.error = true
        }
        assistant.streaming = false
        this.sending = false
        this.controller = null
      }
    },
    /** 停止生成（AbortController 中断 fetch）。 */
    stop(): void {
      this.controller?.abort()
    },
    /** 重置会话（新会话 ID + 清空消息）；进行中的生成一并终止并复位状态机。 */
    reset(): void {
      if (this.sending) {
        this.stop()
      }
      this.sending = false
      this.controller = null
      this.sessionId = crypto.randomUUID()
      this.messages = []
    },
  },
})
