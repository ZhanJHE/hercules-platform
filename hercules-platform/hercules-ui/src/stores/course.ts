/**
 * 课程 store（阶段 G）：列表/详情/关键词，全部经 action 单向流。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { defineStore } from 'pinia'
import * as courseApi from '@/api/course'
import type { Course } from '@/types'

export const useCourseStore = defineStore('course', {
  state: () => ({
    list: [] as Course[],
    total: 0,
    page: 1,
    size: 10,
    keyword: '',
    loading: false,
  }),
  actions: {
    async fetchList(): Promise<void> {
      this.loading = true
      try {
        const result = await courseApi.listCourses(this.page, this.size, this.keyword)
        this.list = result.records
        this.total = result.total
      } finally {
        this.loading = false
      }
    },
    setKeyword(keyword: string): void {
      this.keyword = keyword
      this.page = 1
    },
    setPage(page: number): void {
      this.page = page
    },
  },
})
