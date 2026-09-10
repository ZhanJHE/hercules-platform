/**
 * 认证领域 API（阶段 G）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { http } from './request'
import type { TokenResponse } from '@/types'

export async function login(username: string, password: string): Promise<TokenResponse> {
  return (await http.post<TokenResponse, TokenResponse>('/v1/auth/login', { username, password })) as TokenResponse
}

export async function logout(refreshToken: string): Promise<void> {
  await http.post('/v1/auth/logout', { refreshToken })
}
