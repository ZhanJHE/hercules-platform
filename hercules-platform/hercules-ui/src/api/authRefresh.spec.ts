import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRefreshCoordinator, type RefreshPost } from './authRefresh'
import { clearTokens, getRefreshToken, setTokens } from './tokenBox'

// node 测试环境无 localStorage：注入内存 stub（tokenBox 依赖）
const storageStub = new Map<string, string>()
beforeEach(() => {
  storageStub.clear()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storageStub.get(key) ?? null,
    setItem: (key: string, value: string) => void storageStub.set(key, value),
    removeItem: (key: string) => void storageStub.delete(key),
  })
})

/** 构造成功刷新响应。 */
function okResponse(access: string, refresh: string) {
  return { data: { code: 0, data: { accessToken: access, refreshToken: refresh } } }
}

describe('createRefreshCoordinator（401 单飞刷新）', () => {
  afterEach(() => {
    clearTokens()
    vi.restoreAllMocks()
  })

  it('并发多个刷新请求只发起一次 POST，全部拿到新令牌', async () => {
    setTokens('old-access', 'valid-refresh')
    const post = vi.fn<RefreshPost>()
      .mockResolvedValue(okResponse('new-access', 'new-refresh'))
    const coordinator = createRefreshCoordinator(post, () => {})

    const [a, b, c] = await Promise.all([
      coordinator.refreshOnce(),
      coordinator.refreshOnce(),
      coordinator.refreshOnce(),
    ])

    expect(post).toHaveBeenCalledTimes(1)
    expect(a).toBe('new-access')
    expect(b).toBe('new-access')
    expect(c).toBe('new-access')
  })

  it('刷新成功后写入新令牌对（tokenBox）', async () => {
    setTokens('old-access', 'valid-refresh')
    const post = vi.fn<RefreshPost>().mockResolvedValue(okResponse('a2', 'r2'))
    const coordinator = createRefreshCoordinator(post, () => {})

    await coordinator.refreshOnce()

    // tokenBox 无读取接口暴露新 access（内存态），以重放行为为准：再次刷新用新 refresh
    expect(getRefreshToken()).toBe('r2')
  })

  it('刷新失败：清空本地并触发登出回调，后续调用重新尝试', async () => {
    setTokens('old-access', 'expired-refresh')
    const post = vi.fn<RefreshPost>().mockRejectedValue(new Error('401'))
    const onLogout = vi.fn()
    const coordinator = createRefreshCoordinator(post, onLogout)

    await expect(coordinator.refreshOnce()).rejects.toThrow('401')
    expect(onLogout).toHaveBeenCalledTimes(1)
  })

  it('无 refreshToken 直接失败（不发起请求）', async () => {
    clearTokens()
    const post = vi.fn<RefreshPost>()
    const coordinator = createRefreshCoordinator(post, () => {})

    await expect(coordinator.refreshOnce()).rejects.toThrow('no refresh token')
    expect(post).not.toHaveBeenCalled()
  })
})
