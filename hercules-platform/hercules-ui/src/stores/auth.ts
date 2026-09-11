/**
 * 认证 store（阶段 G）：登录/登出与 UI 档案。
 *
 * <p>token 基础态在 api/tokenBox（内存 access + localStorage refresh），
 * 本 store 持有响应式副本供布局/守卫读取。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { clearTokens, getAccessToken, getProfile, setProfile, setTokens } from '@/api/tokenBox'
import { jwtDecode } from '@/utils/jwtDecode'
import { useChatStore } from './chat'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    username: getProfile()?.username ?? '',
    role: getProfile()?.role ?? '',
  }),
  getters: {
    isLoggedIn: state => state.role !== '' || getAccessToken() !== null,
    isStudent: state => state.role === 'STUDENT',
    isAdmin: state => state.role === 'ADMIN',
  },
  actions: {
    /** 登录：换取令牌对，按 JWT payload 预判角色（仅 UI 用）。 */
    async login(username: string, password: string): Promise<'student' | 'admin'> {
      const token = await authApi.login(username, password)
      setTokens(token.accessToken, token.refreshToken)
      const payload = jwtDecode(token.accessToken)
      const role = payload?.role === 'ADMIN' ? 'ADMIN' : 'STUDENT'
      this.username = payload?.username ?? username
      this.role = role
      setProfile({ username: this.username, role })
      return role === 'ADMIN' ? 'admin' : 'student'
    },
    /** 登出：通知后端（吊销 refresh + 黑名单）并清空本地。 */
    async logout(): Promise<void> {
      try {
        const refresh = localStorage.getItem('hercules.refresh')
        if (refresh) {
          await authApi.logout(refresh)
        }
      } catch {
        // 后端登出失败也照常清空本地（令牌短有效期兜底）
      }
      this.clearLocal()
    },
    /** 仅清空本地态（401 刷新失败路径调用）。 */
    clearLocal(): void {
      clearTokens()
      this.username = ''
      this.role = ''
      // 会话归属随用户走：重置对话会话，避免换账号后沿用上一用户的 sessionId（服务端会返回 403）
      useChatStore().reset()
    },
  },
})
