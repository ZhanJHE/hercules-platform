<script setup lang="ts">
/** 学分环（业务组件）：SVG 圆环展示已修学分进度。 */
import { computed } from 'vue'

const props = defineProps<{ earned: number; cap: number }>()

const RADIUS = 42
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const ratio = computed(() => (props.cap > 0 ? Math.min(props.earned / props.cap, 1) : 0))
const dash = computed(() => `${CIRCUMFERENCE * ratio.value} ${CIRCUMFERENCE}`)
</script>

<template>
  <div class="credit-ring">
    <svg width="110" height="110" viewBox="0 0 110 110">
      <circle cx="55" cy="55" :r="RADIUS" fill="none" stroke="#e4e7ed" stroke-width="10" />
      <circle
        cx="55" cy="55" :r="RADIUS" fill="none" stroke="#1677ff" stroke-width="10"
        stroke-linecap="round"
        :stroke-dasharray="dash"
        transform="rotate(-90 55 55)"
      />
      <text x="55" y="52" text-anchor="middle" font-size="18" font-weight="600">{{ earned }}</text>
      <text x="55" y="70" text-anchor="middle" font-size="11" fill="#57606a">已修学分</text>
    </svg>
    <p class="hint">培养方案上限 {{ cap > 0 ? cap + ' 学分' : '未设置' }}</p>
  </div>
</template>

<style scoped>
.credit-ring {
  text-align: center;
}
.hint {
  color: #57606a;
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
