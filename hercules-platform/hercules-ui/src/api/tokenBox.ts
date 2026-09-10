/**
 * 令牌与登录档案的存储盒（阶段 G）。
 *
 * <p>存储策略（《认证设计》口径）：
 * <ul>
 *   <li>accessToken —— 仅内存（模块变量），不落 localStorage（缩小 XSS 窗口）；页面刷新后经
 *       refreshToken 静默续期恢复；</li>
 *   <li>refreshToken —— localStorage（7 天有效期，服务端可随时吊销）；</li>
 *   <li>profile（username/role）—— localStorage，仅供 UI 路由预判与展示；权限以后端为准。</li>
 * </ul>
 *
 * <p>独立于 Pinia 的原因：axios 拦截器（401 刷新）与 Pinia store 存在相互引用风险，
 * 令牌基础态下沉到无依赖模块，store 与拦截器都只读它。
 *
 * @author zhanjh
 * @since 0.1.0
 */
export interface UserProfile {
  username: string
  role: string
}

const REFRESH_KEY = 'hercules.refresh'
const PROFILE_KEY = 'hercules.profile'

let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function setTokens(newAccess: string, newRefresh: string): void {
  accessToken = newAccess
  localStorage.setItem(REFRESH_KEY, newRefresh)
}

export function getProfile(): UserProfile | null {
  const raw = localStorage.getItem(PROFILE_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as UserProfile
  } catch {
    return null
  }
}

export function setProfile(profile: UserProfile): void {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
}

export function clearTokens(): void {
  accessToken = null
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(PROFILE_KEY)
}
