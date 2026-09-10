<script setup lang="ts">
/** 选课确认弹窗（业务组件）：容量余量校验文案 + 二次确认。 */
import type { Course } from '@/types'

const props = defineProps<{ course: Course | null; visible: boolean; enrolling?: boolean }>()
const emit = defineEmits<{ confirm: [course: Course]; cancel: [] }>()

function seats(course: Course): string {
  const capacity = course.capacity ?? 0
  const enrolled = course.enrolled ?? 0
  return `${enrolled}/${capacity}（余 ${Math.max(capacity - enrolled, 0)}）`
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="确认选课"
    width="420px"
    @update:model-value="emit('cancel')"
  >
    <template v-if="course">
      <p style="margin-top: 0">确认选择课程 <b>{{ course.courseName }}</b>（{{ course.courseCode }}）？</p>
      <p style="color: #57606a">教师：{{ course.teacherName || '—' }}　学分：{{ course.credit ?? '—' }}　容量：{{ seats(course) }}</p>
    </template>
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="enrolling" @click="course && emit('confirm', course)">确认选课</el-button>
    </template>
  </el-dialog>
</template>
