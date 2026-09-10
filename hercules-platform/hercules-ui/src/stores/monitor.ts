/**
 * 监控 store（阶段 G）：驾驶舱快照 + 20s 轮询 + 命中率采样（ECharts 数据源）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { defineStore } from 'pinia'
import { getCacheStats } from '@/api/monitor'
import type { CacheStats } from '@/types'

export const useMonitorStore = defineStore('monitor', {
  state: () => ({
    snapshot: {} as CacheStats,
    /** 命中率采样（时间标签 + 百分比），驾驶舱折线数据源 */
    history: [] as Array<{ time: string; rate: number }>,
    loading: false,
    pollingTimer: 0,
  }),
  actions: {
    async fetchStats(): Promise<void> {
      this.loading = true
      try {
        this.snapshot = await getCacheStats()
        const rate = Math.round((this.snapshot.cacheHitRate ?? 0) * 1000) / 10
        const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
        this.history.push({ time, rate })
        if (this.history.length > 30) {
          this.history.shift()
        }
      } finally {
        this.loading = false
      }
    },
    startPolling(intervalMs = 20000): void {
      this.stopPolling()
      void this.fetchStats()
      this.pollingTimer = window.setInterval(() => {
        void this.fetchStats()
      }, intervalMs)
    },
    stopPolling(): void {
      if (this.pollingTimer) {
        window.clearInterval(this.pollingTimer)
        this.pollingTimer = 0
      }
    },
  },
})
