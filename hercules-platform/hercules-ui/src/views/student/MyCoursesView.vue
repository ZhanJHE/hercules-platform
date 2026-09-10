<script setup lang="ts">
/** 我的选课（S2）：有效选课列表 + 退课 + 学分环。 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import CourseCard from '@/components/business/CourseCard.vue'
import CreditRing from '@/components/business/CreditRing.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useEnrollmentStore } from '@/stores/enrollment'

const enrollmentStore = useEnrollmentStore()

const CREDIT_CAP = 30 // 学分上限（培养方案数据未建模，暂按 30 学分展示进度）

onMounted(() => {
  void enrollmentStore.fetchMine()
})

async function onWithdraw(course: { id: number; courseName?: string }): Promise<void> {
  try {
    await enrollmentStore.withdraw(course.id)
    ElMessage.success(`已退选《${course.courseName ?? course.id}》`)
  } catch {
    // 错误提示已由 request 层弹出
  }
}
</script>

<template>
  <div>
    <PageHeader title="我的选课" :subtitle="`有效选课 ${enrollmentStore.activeEnrollments.length} 门`" />

    <div class="layout">
      <div class="cards">
        <template v-if="enrollmentStore.activeEnrollments.length > 0">
          <CourseCard
            v-for="course in enrollmentStore.myCourses"
            :key="course.id"
            :course="course"
            mode="mine"
            @withdraw="onWithdraw"
          />
        </template>
        <EmptyState v-else text="还没有有效选课，去课程大厅看看吧" />
      </div>
      <div class="side">
        <CreditRing :earned="enrollmentStore.totalCredits" :cap="CREDIT_CAP" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.cards {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.side {
  width: 220px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  background: #fff;
}
</style>
