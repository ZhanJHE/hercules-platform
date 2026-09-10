<script setup lang="ts">
/** 页头：标题 + 副标题 + 右侧动作插槽（通用组件）。 */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="page-header">
    <div>
      <h2 class="title">{{ title }}</h2>
      <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
    </div>
    <div class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 14px;
}
.title {
  margin: 0;
  font-size: 18px;
}
.subtitle {
  margin: 4px 0 0;
  color: #57606a;
  font-size: 13px;
}
</style>
