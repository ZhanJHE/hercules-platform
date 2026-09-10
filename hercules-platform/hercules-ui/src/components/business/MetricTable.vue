<script setup lang="ts">
/** 指标明细表（业务组件）：键值对形式的统计明细渲染。 */
defineProps<{ items: Array<{ label: string; value: string | number }> }>()
</script>

<template>
  <el-descriptions :column="1" border size="small">
    <el-descriptions-item v-for="item in items" :key="item.label" :label="item.label">
      {{ item.value }}
    </el-descriptions-item>
  </el-descriptions>
</template>
