package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.PageResult;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.service.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程查询 REST 接口：/api/v1/courses，只读，响应统一经 {@link R} 包装（code=0 表示成功）。
 *
 * <p>接口契约：
 * <ul>
 *   <li>GET /api/v1/courses —— 课程分页列表，支持关键字搜索；数据经列表缓存
 *       （course:list:*，10s 短 TTL）；</li>
 *   <li>GET /api/v1/courses/{id} —— 课程详情；数据经详情缓存（course:{id}，纳入版本链）。</li>
 * </ul>
 *
 * <p>错误码语义（GlobalExceptionHandler 将 BusinessException 的 code 映射为 HTTP 状态码）：
 * 404 表示课程不存在；400 表示分页参数越界（page &lt; 1，或 size 超出 1~{@value #MAX_PAGE_SIZE}）；
 * 本控制器为纯读路径，不产生 409。
 *
 * <p>线程安全性：无实例状态，仅委托无状态的 CourseService。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    /** 课程查询服务：承载多级缓存读路径 */
    private final CourseService courseService;

    /** 每页条数上限：防止过大的 size 单页拉取全表（分页边界校验，遗留事项清理） */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 构造注入。
     *
     * @param courseService 课程查询服务
     */
    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * 课程分页列表。
     *
     * <p>请求示例：{@code GET /api/v1/courses?page=1&size=10&keyword=数据结构}
     * <br>响应示例（data 为自定义分页结构，非 MyBatis-Plus Page）：
     * <pre>{@code
     * {"code":0,"message":"ok","data":{"total":25,"current":1,"size":10,
     *  "records":[{"id":1,"courseCode":"CS101","courseName":"...","enrolled":30,"capacity":60}]}}
     * }</pre>
     *
     * @param page    页码，缺省 1，必须 ≥ 1
     * @param size    每页条数，缺省 10，必须在 1~{@value #MAX_PAGE_SIZE} 之间
     * @param keyword 可选关键字，匹配课程名/课程编号/教师名三列（OR LIKE）；缺省时全量分页
     * @return 统一响应体，data 为 PageResult&lt;Course&gt;
     * @throws BusinessException code=400（HTTP 400），page &lt; 1 或 size 越界时抛出
     */
    @GetMapping
    public R<PageResult<Course>> list(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(required = false) String keyword) {
        // 分页边界校验：显式 400 拒绝而非静默截断，杜绝 size 大值单页拉全表（遗留事项清理）
        if (page < 1) {
            throw new BusinessException(400, "page 必须 >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(400, "size 必须在 1~" + MAX_PAGE_SIZE + " 之间");
        }
        return R.ok(courseService.list(page, size, keyword));
    }

    /**
     * 课程详情。
     *
     * <p>请求示例：{@code GET /api/v1/courses/1}
     * <br>响应示例：{@code {"code":0,"message":"ok","data":{"id":1,...}}}
     *
     * @param id 课程 ID（路径变量）
     * @return 统一响应体，data 为课程对象
     * @throws BusinessException code=404（HTTP 404），课程 ID 不存在时抛出
     */
    @GetMapping("/{id}")
    public R<Course> detail(@PathVariable Long id) {
        Course course = courseService.getCourse(id);
        // 缓存层不缓存空值：此处拿到 null 即 DB 中也不存在，统一转 404 语义
        if (course == null) {
            throw new BusinessException(404, "课程不存在: " + id);
        }
        return R.ok(course);
    }
}
