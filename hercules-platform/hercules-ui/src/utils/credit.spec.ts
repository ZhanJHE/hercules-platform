import { describe, expect, it } from 'vitest'
import { sumCredits } from './credit'

describe('sumCredits', () => {
  it('十进制求和规避浮点误差（3.0+4.5=7.5）', () => {
    expect(sumCredits(['3.0', '4.5'])).toBe(7.5)
  })

  it('混合数字与字符串', () => {
    expect(sumCredits([2, '3.5', null, undefined, '', 'abc'])).toBe(5.5)
  })

  it('空输入为 0', () => {
    expect(sumCredits([])).toBe(0)
    expect(sumCredits([null, ''])).toBe(0)
  })
})
