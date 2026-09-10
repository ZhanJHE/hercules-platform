/**
 * 路由表与守卫（阶段 G）：登录门 + 角色预判（UI 层，权限以后端二次校验为准）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/student',
    component: () => import('@/layouts/AppLayout.vue'),
    meta: { roles: ['STUDENT'], area: 'student' },
    children: [
      { path: '', redirect: '/student/courses' },
      { path: 'courses', name: 'courses', component: () => import('@/views/student/CourseListView.vue'), meta: { roles: ['STUDENT'], title: '课程大厅' } },
      { path: 'courses/:id', name: 'course-detail', component: () => import('@/views/student/CourseDetailView.vue'), meta: { roles: ['STUDENT'], title: '课程详情' } },
      { path: 'me', name: 'my-courses', component: () => import('@/views/student/MyCoursesView.vue'), meta: { roles: ['STUDENT'], title: '我的选课' } },
      { path: 'chat', name: 'chat', component: () => import('@/views/student/ChatView.vue'), meta: { roles: ['STUDENT'], title: '对话助手' } },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AppLayout.vue'),
    meta: { roles: ['ADMIN'], area: 'admin' },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/admin/DashboardView.vue'), meta: { roles: ['ADMIN'], title: '治理驾驶舱' } },
      { path: 'courses', name: 'admin-courses', component: () => import('@/views/admin/AdminCoursesView.vue'), meta: { roles: ['ADMIN'], title: '课程管理' } },
      { path: 'demo', name: 'demo', component: () => import('@/views/admin/DemoToolView.vue'), meta: { roles: ['ADMIN'], title: '演示工具' } },
    ],
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { public: true, title: '页面不存在' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(to => {
  const auth = useAuthStore()
  const routeRoles = to.meta.roles as string[] | undefined
  if (to.meta.public) {
    return true
  }
  // 未登录（无档案且无 token）→ 登录页
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 角色预判不符 → 回对应端首页（安全校验在后端）
  if (routeRoles && !routeRoles.includes(auth.role)) {
    return auth.role === 'ADMIN' ? '/admin/dashboard' : '/student/courses'
  }
  return true
})

router.afterEach(to => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Hercules` : 'Hercules 选课平台'
})

export default router
