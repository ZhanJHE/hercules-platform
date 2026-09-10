/**
 * 学分合计（阶段 G）：十进制字符串/数字求和，结果保留一位小数（规避浮点误差）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
export function sumCredits(credits: Array<string | number | null | undefined>): number {
  let cents = 0
  for (const credit of credits) {
    if (credit === null || credit === undefined || credit === '') {
      continue
    }
    const value = typeof credit === 'number' ? credit : Number.parseFloat(credit)
    if (Number.isNaN(value)) {
      continue
    }
    cents += Math.round(value * 10)
  }
  return cents / 10
}
