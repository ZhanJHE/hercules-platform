/**
 * 课程领域 API（阶段 G）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { http } from './request'
import type { Course, PageResult } from '@/types'

export async function listCourses(page: number, size: number, keyword: string): Promise<PageResult<Course>> {
  const params: Record<string, string | number> = { page, size }
  if (keyword) {
    params.keyword = keyword
  }
  // axios 第二泛型 = 响应类型：拦截器已解包 R<T>，实际返回即为 T
  return (await http.get<PageResult<Course>, PageResult<Course>>('/v1/courses', { params })) as PageResult<Course>
}

export async function getCourse(id: number): Promise<Course> {
  return (await http.get<Course, Course>(`/v1/courses/${id}`)) as Course
}
