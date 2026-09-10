<script setup lang="ts">
/** 课程大厅（S1）：关键字搜索 + 分页 + 选课确认（CourseCard/RDataTable 复用）。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import CourseCard from '@/components/business/CourseCard.vue'
import EnrollConfirmDialog from '@/components/business/EnrollConfirmDialog.vue'
import { useCourseStore } from '@/stores/course'
import { useEnrollmentStore } from '@/stores/enrollment'
import type { Course } from '@/types'

const courseStore = useCourseStore()
const enrollmentStore = useEnrollmentStore()
const router = useRouter()

const keywordInput = ref('')
const dialogVisible = ref(false)
const dialogCourse = ref<Course | null>(null)

const pages = computed(() => Math.max(Math.ceil(courseStore.total / courseStore.size), 1))

onMounted(() => {
  void courseStore.fetchList()
})

function onSearch(): void {
  courseStore.setKeyword(keywordInput.value)
  void courseStore.fetchList()
}

function onPageChange(page: number): void {
  courseStore.setPage(page)
  void courseStore.fetchList()
}

function openDetail(course: Course): void {
  void router.push(`/student/courses/${course.id}`)
}

async function onConfirmEnroll(course: Course): Promise<void> {
  try {
    await enrollmentStore.enroll(course.id)
    ElMessage.success(`已选上《${course.courseName}》`)
    dialogVisible.value = false
  } catch {
    // 错误提示已由 request 层弹出（409 容量满/重复选课等）
  }
}
</script>

<template>
  <div>
    <PageHeader title="课程大厅" :subtitle="`共 ${courseStore.total} 门课程`">
      <template #actions>
        <el-input
          v-model="keywordInput"
          placeholder="课程名 / 编号 / 教师"
          clearable
          style="width: 260px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #append>
            <el-button @click="onSearch">搜索</el-button>
          </template>
        </el-input>
      </template>
    </PageHeader>

    <div v-loading="courseStore.loading" class="card-grid">
      <CourseCard
        v-for="course in courseStore.list"
        :key="course.id"
        :course="course"
        mode="list"
        :enrolling="enrollmentStore.enrolling"
        @enroll="course => { dialogCourse = course; dialogVisible = true }"
        @open="openDetail"
      />
    </div>
    <el-empty v-if="!courseStore.loading && courseStore.list.length === 0" description="没有匹配的课程" />

    <div class="pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="courseStore.total"
        :page-size="courseStore.size"
        :current-page="courseStore.page"
        :page-count="pages"
        @current-change="onPageChange"
      />
    </div>

    <EnrollConfirmDialog
      :course="dialogCourse"
      :visible="dialogVisible"
      :enrolling="enrollmentStore.enrolling"
      @confirm="onConfirmEnroll"
      @cancel="dialogVisible = false"
    />
  </div>
</template>

<style scoped>
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
  min-height: 120px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
