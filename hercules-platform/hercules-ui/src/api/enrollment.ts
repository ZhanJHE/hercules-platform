/**
 * 选课领域 API（阶段 G）。
 *
 * @author zhanjh
 * @since 0.1.0
 */
import { http } from './request'
import type { Enrollment } from '@/types'

export async function enroll(courseId: number): Promise<Enrollment> {
  // axios 第二泛型 = 响应类型：拦截器已解包 R<T>，实际返回即为 T
  return (await http.post<Enrollment, Enrollment>('/v1/enrollment', { courseId })) as Enrollment
}

export async function withdraw(courseId: number): Promise<Enrollment> {
  return (await http.delete<Enrollment, Enrollment>('/v1/enrollment', { params: { courseId } })) as Enrollment
}

export async function myEnrollments(): Promise<Enrollment[]> {
  return (await http.get<Enrollment[], Enrollment[]>('/v1/enrollment/mine')) as Enrollment[]
}
