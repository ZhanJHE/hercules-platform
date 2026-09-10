/**
 * 监控领域 API（阶段 G）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { http } from './request'
import type { CacheStats } from '@/types'

export async function getCacheStats(): Promise<CacheStats> {
  return (await http.get<CacheStats, CacheStats>('/v1/cache/stats')) as CacheStats
}
