<script setup lang="ts">
/** 演示工具（S4）：答辩现场演示同步链行为（冲突模拟 / L1 失效）。 */
import PageHeader from '@/components/common/PageHeader.vue'
import DemoToolPanel from '@/components/business/DemoToolPanel.vue'
</script>

<template>
  <div>
    <PageHeader title="演示工具" subtitle="答辩演示钩子：冲突模拟走同步链合并，L1 失效演示 L2 回填" />
    <div class="page-card" style="max-width: 760px">
      <DemoToolPanel />
    </div>
  </div>
</template>
