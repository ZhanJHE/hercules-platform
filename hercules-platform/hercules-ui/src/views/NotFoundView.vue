<script setup lang="ts">
/** 404 页面。 */
</script>

<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center">
    <el-empty description="页面不存在">
      <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
    </el-empty>
  </div>
</template>
