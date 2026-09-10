/**
 * axios 封装（阶段 G）：R<T> 解包、401 单飞刷新重放、错误统一提示。
 *
 * <p>约定（前端设计 §六）：HTTP 只在本文件与各领域 api 模块出现；组件禁止直连 api。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { getAccessToken } from './tokenBox'
import { createRefreshCoordinator } from './authRefresh'

/** 业务/API 错误：code 为 HTTP 或业务状态码。 */
export class ApiError extends Error {
  public readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

/** 裸 axios 实例：刷新请求与业务请求共用（无业务拦截器）。 */
const rawAxios = axios.create({ timeout: 20000 })

/** 刷新协调器：成功仅更新 tokenBox；失败清空并跳登录。 */
const coordinator = createRefreshCoordinator(
  (url, body) => rawAxios.post(url, body),
  () => {
    window.location.assign('/login')
  },
)

/** 业务 axios 实例：所有领域 api 使用。 */
export const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.request.use(config => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  response => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      // 预期业务失败（404/409 等）：提示后以 ApiError 拒绝
      ElMessage.error(body.message ?? '请求失败')
      return Promise.reject(new ApiError(body.code, body.message ?? '请求失败'))
    }
    return body
  },
  async (error: AxiosError) => {
    const config = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    const status = error.response?.status

    // 401：单飞刷新后重放一次（认证端点自身 401 不重试，直接跳登录）
    if (status === 401 && config && !config._retried && !config.url?.includes('/auth/')) {
      config._retried = true
      try {
        const newToken = await coordinator.refreshOnce()
        config.headers.Authorization = `Bearer ${newToken}`
        return http.request(config)
      } catch {
        return Promise.reject(new ApiError(401, '登录已过期，请重新登录'))
      }
    }

    if (status === 401) {
      clearAndGoLogin()
      return Promise.reject(new ApiError(401, '登录已过期，请重新登录'))
    }

    const message = (error.response?.data as { message?: string } | undefined)?.message
        ?? (error.code === 'ECONNABORTED' ? '请求超时' : error.message)
    ElMessage.error(`请求失败：${message}`)
    return Promise.reject(new ApiError(status ?? 0, message))
  },
)

function clearAndGoLogin(): void {
  // 延迟导入避免 router ↔ api 循环依赖
  void import('@/router').then(({ default: router }) => {
    void router.push('/login')
  })
}
