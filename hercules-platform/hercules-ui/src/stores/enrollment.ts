/**
 * 选课 store（阶段 G）：选课/退课/我的记录。
 *
 * <p>跨 store 依赖单向：选课/退课成功后调 courseStore.fetchList 刷新容量（铁律 4）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { defineStore } from 'pinia'
import { getCourse } from '@/api/course'
import { myEnrollments as apiMyEnrollments, withdraw as apiWithdraw, enroll as apiEnroll } from '@/api/enrollment'
import { useCourseStore } from './course'
import type { Course, Enrollment } from '@/types'

export const useEnrollmentStore = defineStore('enrollment', {
  state: () => ({
    myEnrollments: [] as Enrollment[],
    /** 有效选课（status=1）对应的课程对象列表 */
    myCourses: [] as Course[],
    enrolling: false,
    loading: false,
  }),
  getters: {
    activeEnrollments: state => state.myEnrollments.filter(e => e.status === 1),
    totalCredits: state => {
      const cents = state.myCourses.reduce((sum, c) => sum + Math.round((Number.parseFloat(String(c.credit ?? '0')) || 0) * 10), 0)
      return cents / 10
    },
  },
  actions: {
    async fetchMine(): Promise<void> {
      this.loading = true
      try {
        this.myEnrollments = await apiMyEnrollments()
        const courses: Course[] = []
        for (const enrollment of this.activeEnrollments) {
          const course = await getCourse(enrollment.courseId)
          courses.push(course)
        }
        this.myCourses = courses
      } finally {
        this.loading = false
      }
    },
    async enroll(courseId: number): Promise<Enrollment> {
      this.enrolling = true
      try {
        const created = await apiEnroll(courseId)
        // 单向依赖：选课成功后刷新课程列表容量
        await useCourseStore().fetchList()
        await this.fetchMine()
        return created
      } finally {
        this.enrolling = false
      }
    },
    async withdraw(courseId: number): Promise<Enrollment> {
      const withdrawn = await apiWithdraw(courseId)
      await useCourseStore().fetchList()
      await this.fetchMine()
      return withdrawn
    },
  },
})
