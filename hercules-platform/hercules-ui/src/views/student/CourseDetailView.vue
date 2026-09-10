<script setup lang="ts">
/** 课程详情（S1）：完整信息 + 选课（EnrollConfirmDialog 复用）。 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import CourseCard from '@/components/business/CourseCard.vue'
import EnrollConfirmDialog from '@/components/business/EnrollConfirmDialog.vue'
import { getCourse } from '@/api/course'
import { useEnrollmentStore } from '@/stores/enrollment'
import type { Course } from '@/types'

const route = useRoute()
const router = useRouter()
const enrollmentStore = useEnrollmentStore()

const course = ref<Course | null>(null)
const dialogVisible = ref(false)

const courseId = computed(() => Number(route.params.id))

onMounted(async () => {
  course.value = await getCourse(courseId.value)
})

async function onConfirmEnroll(target: Course): Promise<void> {
  try {
    await enrollmentStore.enroll(target.id)
    ElMessage.success(`已选上《${target.courseName}》`)
    dialogVisible.value = false
    course.value = await getCourse(courseId.value)
  } catch {
    // 错误提示已由 request 层弹出
  }
}

function goBack(): void {
  void router.push('/student/courses')
}
</script>

<template>
  <div>
    <PageHeader title="课程详情">
      <template #actions>
        <el-button @click="goBack">返回列表</el-button>
      </template>
    </PageHeader>

    <div v-if="course" style="max-width: 520px">
      <CourseCard :course="course" mode="detail" @enroll="() => { dialogVisible = true }" />
      <el-button type="primary" style="margin-top: 14px" @click="dialogVisible = true">选这门课</el-button>
    </div>
    <el-skeleton v-else :rows="5" animated style="max-width: 520px" />

    <EnrollConfirmDialog
      :course="course"
      :visible="dialogVisible"
      :enrolling="enrollmentStore.enrolling"
      @confirm="onConfirmEnroll"
      @cancel="dialogVisible = false"
    />
  </div>
</template>
