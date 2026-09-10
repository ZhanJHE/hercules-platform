<script setup lang="ts">
/**
 * 课程卡片（业务组件，三处复用）：mode = list（选课按钮）/ detail（完整信息）/ mine（退课按钮）。
 */
import { computed } from 'vue'
import type { Course } from '@/types'

const props = withDefaults(
  defineProps<{
    course: Course
    mode?: 'list' | 'detail' | 'mine'
    enrolling?: boolean
  }>(),
  { mode: 'list', enrolling: false },
)

defineEmits<{ enroll: [course: Course]; withdraw: [course: Course]; open: [course: Course] }>()

const seats = computed(() => {
  const capacity = props.course.capacity ?? 0
  const enrolled = props.course.enrolled ?? 0
  return { enrolled, capacity, left: Math.max(capacity - enrolled, 0), full: enrolled >= capacity }
})

function scheduleText(json?: string): string {
  if (!json) return '时间待定'
  try {
    const node = JSON.parse(json)
    if (!node.day && !node.sections) return '时间待定'
    return `周${node.day ?? '?'} 第 ${(node.sections ?? []).join(',')} 节`
  } catch {
    return '时间待定'
  }
}
</script>

<template>
  <div class="course-card">
    <div class="head">
      <span class="code">{{ course.courseCode }}</span>
      <el-tag size="small" effect="plain">{{ course.credit ?? '—' }} 学分</el-tag>
      <el-tag v-if="seats.full" size="small" type="danger" effect="plain">已满</el-tag>
    </div>
    <h3 class="name">{{ course.courseName }}</h3>
    <p class="meta">教师：{{ course.teacherName || '—' }}　时间：{{ scheduleText(course.scheduleJson) }}</p>
    <p v-if="mode === 'detail'" class="meta">先修：{{ course.prerequisitesJson && course.prerequisitesJson !== '[]' ? course.prerequisitesJson : '无' }}</p>
    <p class="meta">容量：{{ seats.enrolled }}/{{ seats.capacity }}（余 {{ seats.left }}）</p>
    <div class="actions">
      <el-button
        v-if="mode === 'list'"
        type="primary"
        size="small"
        :disabled="seats.full"
        :loading="enrolling"
        @click="$emit('enroll', course)"
      >选课</el-button>
      <el-button
        v-if="mode === 'mine'"
        type="danger"
        size="small"
        plain
        @click="$emit('withdraw', course)"
      >退课</el-button>
      <el-button v-if="mode === 'list'" size="small" text @click="$emit('open', course)">详情</el-button>
    </div>
  </div>
</template>

<style scoped>
.course-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.code {
  font-family: Consolas, monospace;
  color: #1677ff;
  font-weight: 600;
}
.name {
  margin: 0;
  font-size: 16px;
}
.meta {
  margin: 0;
  color: #57606a;
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>
