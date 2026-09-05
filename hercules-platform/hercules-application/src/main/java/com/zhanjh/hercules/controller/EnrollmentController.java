package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.auth.JwtUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.model.Enrollment;
import com.zhanjh.hercules.service.EnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 选课写路径 REST 接口：/api/v1/enrollment（仅 STUDENT 角色，Spring Security URL 规则控制）。
 *
 * <p><b>水平越权收敛（阶段 A+ 认证）</b>：studentId 一律取自认证主体（JWT claims），
 * 前端传参不生效——学生无法操作他人数据。
 *
 * <p>接口：选课（POST，JSON 请求体）、退课（DELETE，query 参数）、我的选课记录（GET /mine）。
 * 两者写路径均为事务：条件 UPDATE 防超选/防负数并流转 t_enrollment 记录状态，
 * 事务提交后由向量时钟同步链刷新课程详情缓存（先提交、后同步）。
 *
 * <p>错误码语义（GlobalExceptionHandler 将 BusinessException 的 code 映射为 HTTP 状态码）：
 * 404 课程不存在 / 无有效选课记录；409 课程容量已满 / 已选人数为 0；
 * 400 请求体参数校验失败；403 非 STUDENT 角色（由 SecurityConfig 控制）。
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
     * <p>校验说明：studentId 已收敛为服务端自取（JWT claims），请求体只携带 courseId；
     * @Valid + @NotNull 生效，字段缺失返回 400。
     *
     * @param courseId 课程 ID，必填
     */
    public record EnrollRequest(@NotNull Long courseId) {
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
     * 选课（为当前登录学生选课）。
     *
     * <p>请求示例：{@code POST /api/v1/enrollment}，Content-Type: application/json，
     * 请求体 {@code {"courseId":2}}，携带 Bearer accessToken。
     * <br>成功响应示例：
     * {@code {"code":0,"message":"ok","data":{"id":7,"studentId":20240001,"courseId":2,"status":1,...}}}
     *
     * @param principal 认证主体（studentId 服务端自取）
     * @param request   选课请求体（@Valid 校验 courseId 非空）
     * @return 统一响应体，data 为新建选课记录（status=1 已选）
     * @throws BusinessException code=400（HTTP 400）请求体字段缺失；
     *                           code=403（HTTP 403）非学生角色；
     *                           code=404（HTTP 404）课程不存在；
     *                           code=409（HTTP 409）课程容量已满
     */
    @PostMapping
    public R<Enrollment> enroll(@AuthenticationPrincipal JwtUtil.AuthClaims principal,
                                @RequestBody @Valid EnrollRequest request) {
        return R.ok(enrollmentService.enroll(principal, request.courseId()));
    }

    /**
     * 退课（当前登录学生）。
     *
     * <p>请求示例：{@code DELETE /api/v1/enrollment?courseId=2}（Bearer accessToken）。
     * <br>成功响应 data 为被置为退选状态的那条记录（status=2），只做状态流转、不删行。
     *
     * @param principal 认证主体（studentId 服务端自取）
     * @param courseId  课程 ID（query 参数）
     * @return 统一响应体，data 为更新后的选课记录（status=2 退选）
     * @throws BusinessException code=403（HTTP 403）非学生角色；
     *                           code=404（HTTP 404）未找到有效选课记录；
     *                           code=409（HTTP 409）已选人数为 0，无法退课
     */
    @DeleteMapping
    public R<Enrollment> withdraw(@AuthenticationPrincipal JwtUtil.AuthClaims principal,
                                  @RequestParam Long courseId) {
        return R.ok(enrollmentService.withdraw(principal, courseId));
    }

    /**
     * 查询当前登录学生的选课记录（时间倒序，含已退选流水）。
     *
     * <p>请求示例：{@code GET /api/v1/enrollment/mine}（Bearer accessToken）。
     *
     * @param principal 认证主体
     * @return 统一响应体，data 为本人全部选课记录（可能为空列表）
     */
    @GetMapping("/mine")
    public R<List<Enrollment>> mine(@AuthenticationPrincipal JwtUtil.AuthClaims principal) {
        return R.ok(enrollmentService.mine(principal));
    }
}
