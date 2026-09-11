<script setup lang="ts">
/**
 * 应用布局（阶段 G）：左侧功能菜单（按角色渲染）+ 顶部栏（标题 + 用户下拉）+ 内容区。
 *
 * <p>对《前端设计.md》双 Layout 的一处简化：学生/管理共用本组件，菜单与区域由
 * authStore.role 派生（渲染逻辑同源）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, User, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const collapsed = ref(false)

interface MenuItem {
  index: string
  title: string
}

const menus = computed<MenuItem[]>(() =>
  auth.isAdmin
    ? [
        { index: '/admin/dashboard', title: '系统监控' },
        { index: '/admin/courses', title: '课程管理' },
      ]
    : [
        { index: '/student/courses', title: '课程大厅' },
        { index: '/student/me', title: '我的选课' },
        { index: '/student/chat', title: '对话助手' },
      ],
)

const activeMenu = computed(() => '/' + route.path.split('/')[1] + '/' + (route.path.split('/')[2] ?? ''))

const pageTitle = computed(() => (route.meta.title as string) ?? '')

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '64px' : '220px'" class="app-aside">
      <div class="brand">
        <span v-if="!collapsed">Hercules 选课平台</span>
        <span v-else>H</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        router
        class="app-menu"
      >
        <el-menu-item v-for="menu in menus" :key="menu.index" :index="menu.index">
          <span>{{ menu.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="header-right">
          <el-dropdown @command="onLogout">
            <span class="user-chip">
              <el-icon><User /></el-icon>
              {{ auth.username || '未登录' }}
              <el-tag size="small" type="info" effect="plain">{{ auth.role }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}
.app-aside {
  background: #001529;
  transition: width 0.2s;
  overflow-x: hidden;
}
.brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
  letter-spacing: 1px;
}
.app-menu {
  border-right: none;
  background: #001529;
}
.app-menu :deep(.el-menu-item) {
  color: #c7cbe0;
}
.app-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: #1677ff;
}
.app-menu :deep(.el-menu-item:hover) {
  background: #11306b;
  color: #fff;
}
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  cursor: pointer;
  font-size: 18px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
}
.app-main {
  padding: 16px 20px;
  overflow: auto;
}
</style>
