/**
 * SSE 文本流解析器（阶段 G）：把分片到达的 SSE 文本还原为事件序列。
 *
 * <p>协议要点：事件由「event: <名>」与若干「data: <载荷>」行组成，空行结束一个事件；
 * 跨 chunk 的半行必须缓存到下一片（粘包处理）；多行 data 按协议以换行拼接。
 *
 * <p>纯函数式状态机：push(chunk) → 本次完整事件数组，无 DOM/网络依赖，可单测。
 *
 * @author zhanjh
 * @since 0.1.0
 */
export interface SseEvent {
  event: string
  data: string
}

export class SseStreamParser {
  /** 跨 chunk 的半行缓冲。 */
  private buffer = ''

  /** 当前事件的事件名（缺省 message）。 */
  private eventName = ''

  /** 当前事件的 data 行累积。 */
  private dataLines: string[] = []

  public push(chunk: string): SseEvent[] {
    this.buffer += chunk
    const events: SseEvent[] = []
    let index: number
    // 行分隔符兼容 \n 与 \r\n
    while ((index = this.buffer.indexOf('\n')) >= 0) {
      const rawLine = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
      const event = this.consumeLine(line)
      if (event) {
        events.push(event)
      }
    }
    return events
  }

  /**
   * 流结束时的收尾：缓冲中未换行结尾的残余行逐行消费，并补触发未完成事件。
   *
   * @return 剩余完整事件
   */
  public finish(): SseEvent[] {
    const remaining = this.buffer
    this.buffer = ''
    const events: SseEvent[] = []
    if (remaining !== '') {
      for (const rawLine of remaining.split('\n')) {
        const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
        const event = this.consumeLine(line)
        if (event) {
          events.push(event)
        }
      }
    }
    const tail = this.flush()
    if (tail) {
      events.push(tail)
    }
    return events
  }

  private consumeLine(line: string): SseEvent | null {
    if (line === '') {
      return this.flush()
    }
    if (line.startsWith('event:')) {
      this.eventName = line.slice(6).trim()
      return null
    }
    if (line.startsWith('data:')) {
      this.dataLines.push(line.slice(5).replace(/^ /, ''))
      return null
    }
    // 注释行（: 开头）与其他字段忽略
    return null
  }

  private flush(): SseEvent | null {
    if (this.dataLines.length === 0) {
      this.eventName = ''
      return null
    }
    const event: SseEvent = { event: this.eventName || 'message', data: this.dataLines.join('\n') }
    this.eventName = ''
    this.dataLines = []
    return event
  }
}
