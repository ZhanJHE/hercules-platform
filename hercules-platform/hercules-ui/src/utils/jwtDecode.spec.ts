import { describe, expect, it } from 'vitest'
import { jwtDecode } from './jwtDecode'

describe('jwtDecode', () => {
  it('解析合法 JWT payload（含中文 UTF-8）', () => {
    // 动态构造合法 token：payload = {"username":"张三","role":"STUDENT","studentId":1}
    const payloadJson = JSON.stringify({ username: '张三', role: 'STUDENT', studentId: 1 })
    const b64 = Buffer.from(payloadJson, 'utf-8').toString('base64')
    const token = `header.${b64}.signature`
    const payload = jwtDecode(token)
    expect(payload?.username).toBe('张三')
    expect(payload?.role).toBe('STUDENT')
    expect(payload?.studentId).toBe(1)
  })

  it('非法输入返回 null（不抛出）', () => {
    expect(jwtDecode('not-a-jwt')).toBeNull()
    expect(jwtDecode('a.b.c')).toBeNull()
    expect(jwtDecode('')).toBeNull()
  })
})
