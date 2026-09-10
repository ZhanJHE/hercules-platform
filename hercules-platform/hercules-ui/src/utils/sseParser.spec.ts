import { describe, expect, it } from 'vitest'
import { SseStreamParser } from './sseParser'

describe('SseStreamParser', () => {
  it('完整单事件解析', () => {
    const parser = new SseStreamParser()
    const events = parser.push('event:token\ndata:你好\n\n')
    expect(events).toEqual([{ event: 'token', data: '你好' }])
  })

  it('跨 chunk 粘包：半行缓存到下一片', () => {
    const parser = new SseStreamParser()
    expect(parser.push('event:tok')).toEqual([])
    expect(parser.push('en\ndata:你好\n\nevent:meta\ndata:{"a"')).toEqual([{ event: 'token', data: '你好' }])
    const rest = parser.push(':1}\n\n')
    expect(rest).toEqual([{ event: 'meta', data: '{"a":1}' }])
  })

  it('多行 data 按换行拼接', () => {
    const parser = new SseStreamParser()
    const events = parser.push('data:第一行\ndata:第二行\n\n')
    expect(events).toEqual([{ event: 'message', data: '第一行\n第二行' }])
  })

  it('CRLF 行分隔与缺省事件名', () => {
    const parser = new SseStreamParser()
    const events = parser.push('data:a\r\ndata:b\r\n\r\n')
    expect(events).toEqual([{ event: 'message', data: 'a\nb' }])
  })

  it('finish 补触发未换行结尾的最后一行', () => {
    const parser = new SseStreamParser()
    parser.push('event:error\ndata:boom')
    expect(parser.finish()).toEqual([{ event: 'error', data: 'boom' }])
  })

  it('注释行与纯空行被忽略', () => {
    const parser = new SseStreamParser()
    expect(parser.push(': heartbeat\n\n\n')).toEqual([])
  })
})
