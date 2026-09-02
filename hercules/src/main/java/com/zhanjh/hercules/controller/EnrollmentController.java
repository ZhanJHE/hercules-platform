package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.model.Enrollment;
import com.zhanjh.hercules.service.EnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 选课写路径 REST 接口：/api/v1/enrollment，提供选课（POST，JSON 请求体）与退课（DELETE，query 参数）。
 *
 * <p>两者均为事务写路径：事务内经条件 UPDATE 防超选/防负数并流转 t_enrollment 记录状态，
 * 事务提交后由向量时钟同步链刷新课程详情缓存（先提交、后同步）。
 *
 * <p>错误码语义（GlobalExceptionHandler 将 BusinessException 的 code 映射为 HTTP 状态码）：
 * 404 课程不存在 / 无有效选课记录；409 课程容量已满 / 已选人数为 0；
 * 400 请求体参数校验失败（@Valid + @NotNull 启用，字段缺失时返回）。
 *
 * <p>线程安全性：无实例状态，仅委托无状态的 EnrollmentService。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/enrollment")
public class EnrollmentController {

    /**
     * 选课请求体。
     *
     * <p>校验说明：控制器方法参数标注 @Valid，studentId / courseId 的 @NotNull 生效——
     * 字段缺失时由 Bean Validation 返回 400（GlobalExceptionHandler 统一处理）。
     *
     * @param studentId 学生 ID，必填
     * @param courseId  课程 ID，必填
     */
    public record EnrollRequest(@NotNull Long studentId, @NotNull Long courseId) {
    }

    /** 选课写路径服务：事务内写库 + 提交后发版本事件 */
    private final EnrollmentService enrollmentService;

    /**
     * 构造注入。
     *
     * @param enrollmentService 选课写路径服务
     */
    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /**
     * 选课。
     *
     * <p>请求示例：{@code POST /api/v1/enrollment}，Content-Type: application/json，
     * 请求体 {@code {"studentId":1001,"courseId":2}}
     * <br>成功响应示例：
     * {@code {"code":0,"message":"ok","data":{"id":7,"studentId":1001,"courseId":2,"status":1,...}}}
     *
     * @param request 选课请求体（学生 ID + 课程 ID，@Valid 校验非空）
     * @return 统一响应体，data 为新建选课记录（status=1 已选）
     * @throws BusinessException code=400（HTTP 400）请求体字段缺失；
     *                           code=404（HTTP 404）课程不存在；
     *                           code=409（HTTP 409）课程容量已满
     */
    @PostMapping
    public R<Enrollment> enroll(@RequestBody @Valid EnrollRequest request) {
        return R.ok(enrollmentService.enroll(request.studentId(), request.courseId()));
    }

    /**
     * 退课。
     *
     * <p>请求示例：{@code DELETE /api/v1/enrollment?studentId=1001&courseId=2}
     * <br>成功响应 data 为被置为退选状态的那条记录（status=2），只做状态流转、不删行。
     *
     * @param studentId 学生 ID（query 参数）
     * @param courseId  课程 ID（query 参数）
     * @return 统一响应体，data 为更新后的选课记录（status=2 退选）
     * @throws BusinessException code=404（HTTP 404）未找到有效选课记录；
     *                           code=409（HTTP 409）已选人数为 0，无法退课
     */
    @DeleteMapping
    public R<Enrollment> withdraw(@RequestParam Long studentId, @RequestParam Long courseId) {
        return R.ok(enrollmentService.withdraw(studentId, courseId));
    }
}
