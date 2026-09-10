<script setup lang="ts">
/** 空态占位（通用组件）。 */
defineProps<{ text?: string }>()
</script>

<template>
  <el-empty :description="text ?? '暂无数据'" :image-size="72" />
</template>
