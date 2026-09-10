<script setup lang="ts">
/** 状态标签：把后端状态字符串映射为颜色语义（通用组件）。 */
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const mapping: Record<string, { type: 'success' | 'warning' | 'danger' | 'info'; label: string }> = {
  CLOSED: { type: 'success', label: '熔断关闭' },
  OPEN: { type: 'danger', label: '熔断打开' },
  HALF_OPEN: { type: 'warning', label: '熔断半开' },
  '1': { type: 'success', label: '已选' },
  '2': { type: 'info', label: '已退选' },
  '0': { type: 'warning', label: '预选' },
}

const view = computed(() => mapping[props.status] ?? { type: 'info' as const, label: props.status })
</script>

<template>
  <el-tag :type="view.type" effect="plain">{{ view.label }}</el-tag>
</template>
