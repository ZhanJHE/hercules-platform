/**
 * JWT payload 解码（阶段 G）：base64url 解出 payload 段。
 *
 * <p>边界：只做 base64 解码，<b>不校验签名</b>——结果仅用于 UI 路由预判与用户名展示，
 * 权限语义以后端二次校验为准（《认证设计.md》约定）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import type { JwtPayload } from '@/types'

export function jwtDecode(token: string): JwtPayload | null {
  const parts = token.split('.')
  if (parts.length !== 3) {
    return null
  }
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    )
    return JSON.parse(json) as JwtPayload
  } catch {
    return null
  }
}
