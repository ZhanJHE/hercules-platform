/**
 * 401 静默刷新（阶段 G）：单飞（single-flight）刷新 + 全局登出跳转。
 *
 * <p>单飞语义：并发多个 401 请求时，只发起一次 /auth/refresh，其余请求共享同一
 * Promise——刷新成功后各自重放，失败后统一跳登录。刷新协调逻辑抽为
 * {@link createRefreshCoordinator} 以便注入假 post 函数做单测（零网络）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { getRefreshToken, setTokens, clearTokens } from './tokenBox'

/** 刷新请求的注入点：默认为原生 axios POST（不经业务拦截器）。 */
export type RefreshPost = (url: string, body: { refreshToken: string }) => Promise<{ data: { code: number; message?: string; data: { accessToken: string; refreshToken: string } } }>

export interface RefreshCoordinator {
  refreshOnce(): Promise<string>
}

export function createRefreshCoordinator(post: RefreshPost, onLogout: () => void): RefreshCoordinator {
  let inflight: Promise<string> | null = null

  const doRefresh = async (): Promise<string> => {
    const refreshToken = getRefreshToken()
    if (!refreshToken) {
      throw new Error('no refresh token')
    }
    const resp = await post('/api/v1/auth/refresh', { refreshToken })
    if (resp.data.code !== 0) {
      throw new Error(resp.data.message ?? 'refresh rejected')
    }
    const { accessToken, refreshToken: newRefresh } = resp.data.data
    setTokens(accessToken, newRefresh)
    return accessToken
  }

  return {
    refreshOnce(): Promise<string> {
      if (!inflight) {
        inflight = doRefresh()
          .catch(err => {
            // 刷新失败 = 会话彻底失效：清空并跳登录
            clearTokens()
            onLogout()
            throw err
          })
          .finally(() => {
            inflight = null
          })
      }
      return inflight
    },
  }
}
