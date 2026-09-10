<script setup lang="ts">
/** 数据表格（通用组件）：统一斑马纹/边框/loading 语义。 */
defineProps<{
  columns: Array<{ prop: string; label: string; width?: number; minWidth?: number }>
  rows: unknown[]
  loading?: boolean
}>()
</script>

<template>
  <el-table :data="rows" v-loading="loading" stripe border size="default">
    <el-table-column
      v-for="column in columns"
      :key="column.prop"
      :prop="column.prop"
      :label="column.label"
      :width="column.width"
      :min-width="column.minWidth"
    >
      <template #default="{ row }">
        {{ row[column.prop] ?? '—' }}
      </template>
    </el-table-column>
    <template #empty>
      <el-empty description="暂无数据" :image-size="64" />
    </template>
  </el-table>
</template>
