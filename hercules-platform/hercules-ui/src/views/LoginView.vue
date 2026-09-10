<script setup lang="ts">
/** 登录页（阶段 G）：接真实 JWT 登录接口，按角色跳转对应端。 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'st001', password: '' })
const loading = ref(false)

async function submit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    const area = await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect as string | undefined
    await router.push(redirect ?? (area === 'admin' ? '/admin/dashboard' : '/student/courses'))
  } catch {
    // 错误提示已由 request 层统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="brand">Hercules 选课平台</h1>
      <p class="sub">智慧校园 · 高并发选课</p>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="st001 / admin" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="123456 / admin123" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
      <p class="hint">演示账号：st001~st060（123456）｜ admin（admin123）</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #eef2f7 0%, #f5f7fa 100%);
}
.login-card {
  width: 360px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  padding: 28px 32px;
}
.brand {
  margin: 0 0 4px;
  font-size: 20px;
}
.sub {
  margin: 0 0 18px;
  color: #57606a;
  font-size: 13px;
}
.submit {
  width: 100%;
}
.hint {
  color: #8b949e;
  font-size: 12px;
  margin: 12px 0 0;
  text-align: center;
}
</style>
