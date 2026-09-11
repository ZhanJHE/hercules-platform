<script setup lang="ts">
/** 系统监控（S3）：六个指标格 + 熔断状态 + 命中率趋势（每 20 秒轮询一次）。 */
import { computed, onMounted, onUnmounted } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatCard from '@/components/business/StatCard.vue'
import MetricTable from '@/components/business/MetricTable.vue'
import { useMonitorStore } from '@/stores/monitor'

const monitor = useMonitorStore()

const circuitTone = computed<'success' | 'danger' | 'warning'>(() => {
  const state = monitor.snapshot.redisCircuitState ?? 'CLOSED'
  return state === 'OPEN' ? 'danger' : state === 'HALF_OPEN' ? 'warning' : 'success'
})

const detailItems = computed(() => [
  { label: 'L1 命中', value: monitor.snapshot.l1Hit ?? 0 },
  { label: 'L1 未命中', value: monitor.snapshot.l1Miss ?? 0 },
  { label: 'L2 命中', value: monitor.snapshot.l2Hit ?? 0 },
  { label: 'L2 未命中', value: monitor.snapshot.l2Miss ?? 0 },
  { label: '数据库回源', value: monitor.snapshot.dbLoad ?? 0 },
  { label: 'Redis 降级次数', value: monitor.snapshot.redisDegraded ?? 0 },
])

// ECharts 命中率趋势（轮询采样驱动，懒加载减小首屏体积）
import * as echarts from 'echarts'
import { ref, watch } from 'vue'
const chartEl = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

onMounted(() => {
  monitor.startPolling(20000)
  if (chartEl.value) {
    chart = echarts.init(chartEl.value)
  }
})

onUnmounted(() => {
  monitor.stopPolling()
  chart?.dispose()
})

watch(
  () => monitor.history.length,
  () => {
    chart?.setOption({
      grid: { left: 40, right: 12, top: 24, bottom: 24 },
      xAxis: { type: 'category', data: monitor.history.map(point => point.time) },
      yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      series: [{ type: 'line', smooth: true, data: monitor.history.map(point => point.rate), areaStyle: {} }],
    })
  },
)
</script>

<template>
  <div>
    <PageHeader title="系统监控" subtitle="缓存命中、数据库回源与同步情况（每 20 秒自动刷新）">
      <template #actions>
        <el-button :loading="monitor.loading" @click="monitor.fetchStats()">立即刷新</el-button>
      </template>
    </PageHeader>

    <div class="grid">
      <StatCard title="缓存命中率" :value="`${Math.round((monitor.snapshot.cacheHitRate ?? 0) * 1000) / 10}%`" tone="success" hint="(L1 命中 + L2 命中) / 总读请求" />
      <StatCard title="L1 命中" :value="monitor.snapshot.l1Hit ?? 0" hint="进程内缓存" />
      <StatCard title="L2 命中" :value="monitor.snapshot.l2Hit ?? 0" hint="Redis 分布式缓存" />
      <StatCard title="数据库回源" :value="monitor.snapshot.dbLoad ?? 0" hint="L1/L2 均未命中" />
      <StatCard title="版本应用" :value="monitor.snapshot.versionApplied ?? 0" hint="同步链应用计数" />
      <StatCard title="冲突检测" :value="monitor.snapshot.conflictDetected ?? 0" tone="warning" hint="CONCURRENT 合并计数" />
    </div>

    <div class="panels">
      <div class="panel">
        <h3>Redis 熔断状态</h3>
        <el-tag :type="circuitTone" size="large" effect="dark">
          {{ monitor.snapshot.redisCircuitState ?? 'CLOSED' }}
        </el-tag>
        <MetricTable :items="detailItems" style="margin-top: 12px" />
      </div>
      <div class="panel">
        <h3>缓存命中率趋势（本次会话采样）</h3>
        <div ref="chartEl" class="chart" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.panels {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 12px;
  margin-top: 14px;
}
.panel {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
}
.panel h3 {
  margin: 0 0 10px;
  font-size: 15px;
}
.chart {
  height: 260px;
}
@media (max-width: 1000px) {
  .panels {
    grid-template-columns: 1fr;
  }
}
</style>
