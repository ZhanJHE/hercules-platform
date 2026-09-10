<script setup lang="ts">
/** 课程管理（管理端）：全量课程只读浏览（分页 + 搜索）。 */
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { listCourses } from '@/api/course'
import type { Course } from '@/types'

const rows = ref<Course[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const loading = ref(false)

const columns = [
  { prop: 'id', label: 'ID', width: 70 },
  { prop: 'courseCode', label: '课程编号', minWidth: 110 },
  { prop: 'courseName', label: '课程名称', minWidth: 160 },
  { prop: 'teacherName', label: '教师', minWidth: 100 },
  { prop: 'credit', label: '学分', width: 80 },
  { prop: 'capacity', label: '容量', width: 90 },
  { prop: 'enrolled', label: '已选', width: 90 },
]

async function fetchPage(): Promise<void> {
  loading.value = true
  try {
    const result = await listCourses(page.value, 15, keyword.value)
    rows.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  page.value = 1
  void fetchPage()
}

function onPageChange(next: number): void {
  page.value = next
  void fetchPage()
}

onMounted(fetchPage)
</script>

<template>
  <div>
    <PageHeader title="课程管理" :subtitle="`共 ${total} 门课程（只读浏览）`">
      <template #actions>
        <el-input
          v-model="keyword"
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

    <el-table v-loading="loading" :data="rows" stripe border>
      <el-table-column v-for="column in columns" :key="column.prop" :prop="column.prop" :label="column.label" :width="column.width" :min-width="column.minWidth" />
      <template #empty>
        <el-empty description="暂无数据" :image-size="64" />
      </template>
    </el-table>

    <div style="display: flex; justify-content: flex-end; margin-top: 12px">
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="onPageChange" />
    </div>
  </div>
</template>
