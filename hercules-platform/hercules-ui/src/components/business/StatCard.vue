<script setup lang="ts">
/** 指标卡（业务组件）：驾驶舱六宫格复用，差异仅标题/数值/提示。 */
defineProps<{ title: string; value: string | number; hint?: string; tone?: 'default' | 'success' | 'danger' | 'warning' }>()
</script>

<template>
  <div class="stat-card" :class="tone ?? 'default'">
    <p class="title">{{ title }}</p>
    <p class="value">{{ value }}</p>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.stat-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px 16px;
  background: #fff;
}
.title {
  margin: 0;
  color: #57606a;
  font-size: 13px;
}
.value {
  margin: 6px 0 0;
  font-size: 26px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.hint {
  margin: 4px 0 0;
  color: #8b949e;
  font-size: 12px;
}
.success .value { color: #116932; }
.danger .value { color: #c62828; }
.warning .value { color: #b26a00; }
</style>
