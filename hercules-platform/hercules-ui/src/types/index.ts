/**
 * 与后端契约一一对应的前端类型（阶段 G）。
 *
 * @author zhanjh
 * @since 0.1.0
 */

/** 课程实体（t_course / GET /courses）。 */
export interface Course {
  id: number
  courseCode: string
  courseName: string
  teacherName?: string
  credit?: number
  capacity?: number
  enrolled?: number
  scheduleJson?: string
  prerequisitesJson?: string
  syllabusUrl?: string
  updateTime?: string
}

/** 选课记录实体（t_enrollment）。 */
export interface Enrollment {
  id: number
  studentId: number
  courseId: number
  status: number // 0 预选 / 1 已选 / 2 退选
  createTime?: string
  updateTime?: string
  /** mine 接口为 enrollment 明细；课程信息前端经 courseStore 补齐 */
  courseIdCourse?: Course
}

/** 分页结果（PageResult&lt;T&gt;）。 */
export interface PageResult<T> {
  total: number
  current: number
  size: number
  records: T[]
}

/** 登录/刷新响应（TokenResponse）。 */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
}

/** 缓存治理统计快照（GET /cache/stats 的 data，字段为后端 snapshot 的子集）。 */
export interface CacheStats {
  l1Hit?: number
  l1Miss?: number
  l2Hit?: number
  l2Miss?: number
  dbLoad?: number
  cacheHitRate?: number
  redisDegraded?: number
  redisCircuitState?: string
  versionApplied?: number
  conflictDetected?: number
}

/** 对话消息（前端渲染模型）。 */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** assistant 消息附带的 meta（推荐候选/选课校验结果） */
  meta?: ChatMeta
  /** 是否仍在流式接收 */
  streaming?: boolean
  /** 出错标记 */
  error?: boolean
}

/** SSE meta 事件的结构化数据。 */
export interface ChatMeta {
  intent?: string
  keyword?: string
  passed?: boolean
  courseId?: number
  courseName?: string
  conflicts?: string[]
  candidateCount?: number
  candidates?: Array<{
    id: number
    code: string
    name: string
    credit: string
    conflicted: boolean
    conflicts: string[]
  }>
}

/** JWT payload（仅 UI 路由预判用，权限以后端二次校验为准）。 */
export interface JwtPayload {
  sub?: string
  username?: string
  role?: string
  studentId?: number | null
  jti?: string
  exp?: number
}
