<script setup lang="ts">
/** 404 页面：按登录状态与角色回到对应首页（不能直接跳 `/`，否则又回到本页）。 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const homePath = computed(() => {
  if (!auth.isLoggedIn) {
    return '/login'
  }
  return auth.role === 'ADMIN' ? '/admin/dashboard' : '/student/courses'
})

function goHome(): void {
  void router.push(homePath.value)
}
</script>

<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center">
    <el-empty description="页面不存在">
      <el-button type="primary" @click="goHome">返回首页</el-button>
    </el-empty>
  </div>
</template>
