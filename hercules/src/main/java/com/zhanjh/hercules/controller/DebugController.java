package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.cache.core.CacheManager;
import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.mapper.CourseMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.producer.VersionChangeProducer;
import com.zhanjh.hercules.sync.support.VersionReader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调试/演示端点：/api/v1/debug，仅供毕设答辩现场演示同步链行为，生产环境应关闭或加鉴权。
 *
 * <p>提供两个钩子：
 * <ul>
 *   <li>POST /api/v1/debug/simulate-conflict —— 模拟另一节点（node-sim）并发写同一课程，
 *       构造向量时钟 CONCURRENT 冲突，演示字段级 LWW 合并；</li>
 *   <li>POST /api/v1/debug/evict-local —— 仅失效本机 L1，演示「L1 失效 → L2 Redis 回填」。</li>
 * </ul>
 *
 * <p>演示语义说明：simulate-conflict 只发布版本、不写 t_course——模拟值仅进入缓存与
 * t_cache_version，课程表数据不变（缓存 TTL 过期回源后会还原为库中值）。
 * 本类方法无事务上下文，VersionChangeEventListener 以 fallbackExecution=true 兜底，
 * 事件发布后立即被消费（无需等待事务提交）。
 *
 * <p>线程安全性：无实例状态；并发调用 simulate-conflict 会发布多个并发版本，
 * 消费端按向量时钟逐条合并，最终一致。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    /**
     * 冲突模拟请求体。
     *
     * @param courseId   课程 ID，必填（@Valid + @NotNull 生效，缺失返回 400）
     * @param courseName 模拟写入的课程名，可选；空白/缺省时使用「原课程名 + "[冲突模拟]"」
     */
    public record SimulateConflictRequest(@NotNull Long courseId, String courseName) {
    }

    /** 课程表访问接口：读取被模拟的课程当前数据 */
    private final CourseMapper courseMapper;

    /** 版本读取器：查询 t_cache_version 中的存量版本（用于响应展示） */
    private final VersionReader versionReader;

    /** 版本变更生产者：发布模拟版本，经进程内事件总线立即被消费 */
    private final VersionChangeProducer producer;

    /** 缓存门面：提供仅失效 L1 的 evict-local 钩子 */
    private final CacheManager cacheManager;

    /**
     * 构造注入。
     *
     * @param courseMapper  课程表访问接口
     * @param versionReader 存量版本读取器
     * @param producer      版本变更生产者
     * @param cacheManager  缓存门面（提供仅失效 L1 的钩子）
     */
    public DebugController(CourseMapper courseMapper,
                           VersionReader versionReader,
                           VersionChangeProducer producer,
                           CacheManager cacheManager) {
        this.courseMapper = courseMapper;
        this.versionReader = versionReader;
        this.producer = producer;
        this.cacheManager = cacheManager;
    }

    /**
     * 模拟另一节点（node-sim）对同一课程的并发写。
     *
     * <p>机制：
     * <ol>
     *   <li>发布版本携带的时钟只含自身分量 {@code {"node-sim":1}}，不叠加存量时钟——
     *       与存量时钟（含 node-1 分量）互不支配，消费端 compare 判定 CONCURRENT，
     *       必然走字段级 LWW 合并分支；</li>
     *   <li>模拟值时间戳取当前毫秒，晚于存量版本 → 合并后 courseName 取模拟值、
     *       其余字段保留旧值（FieldLwwMergeStrategy：值不同的字段按时间戳裁决）；</li>
     *   <li>前置条件：该课程此前至少发生过一次选课/发布（t_cache_version 已有记录）。
     *       若无存量版本，消费端以空时钟比较，incoming 支配 → 按新版本直接应用，不触发冲突，
     *       此时响应中 storedVersionBefore 返回提示文案。</li>
     * </ol>
     *
     * <p>请求示例：{@code POST /api/v1/debug/simulate-conflict}，
     * 请求体 {@code {"courseId":1,"courseName":"并发改名"}}
     *
     * @param request 冲突模拟请求体（@Valid 校验 courseId 非空）
     * @return data 含 published（恒 true）、simNodeId（node-sim）、simClock（时钟 JSON 字符串）、
     *         storedVersionBefore（调用时刻的存量版本号，无则返回提示文案）、hint（验证指引）
     * @throws BusinessException code=400（HTTP 400）courseId 缺失；
     *                           code=404（HTTP 404），课程 ID 不存在时抛出
     */
    @PostMapping("/simulate-conflict")
    public R<Map<String, Object>> simulateConflict(@RequestBody @Valid SimulateConflictRequest request) {
        Course course = courseMapper.selectById(request.courseId());
        if (course == null) {
            throw new BusinessException(404, "课程不存在: " + request.courseId());
        }
        String key = CacheKeys.course(request.courseId());
        // 存量版本仅用于响应展示调用时刻的快照；真正的冲突判定由消费端读取 t_cache_version 最新记录进行
        CacheVersion stored = versionReader.find(key).orElse(null);

        // 时钟只含 node-sim 自身分量（不叠加存量时钟），与存量时钟（node-1 分量）互不支配 → 必走 CONCURRENT 分支
        VectorClock simClock = new VectorClock().increment("node-sim");
        course.setCourseName(StringUtils.hasText(request.courseName())
                ? request.courseName()
                : course.getCourseName() + "[冲突模拟]");
        // 时间戳取当前毫秒：晚于存量版本 updateTime → 字段级 LWW 裁决时模拟值获胜
        producer.publish(new VersionedValue(key, JsonUtil.toJson(course),
                "node-sim", System.currentTimeMillis(), simClock));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("published", true);
        data.put("simNodeId", "node-sim");
        data.put("simClock", simClock.toJson());
        data.put("storedVersionBefore", stored == null ? "(无存量版本，本次按新版本应用，未触发冲突)" : stored.getCurrentVersion());
        data.put("hint", "消费端已同步应用，GET /api/v1/courses/" + request.courseId() + " 查看字段级合并结果");
        return R.ok(data);
    }

    /**
     * 手动失效本机 L1 缓存（不触碰 L2 Redis）。
     *
     * <p>请求示例：{@code POST /api/v1/debug/evict-local?key=course:1}
     * <br>演示「L1 失效 → L2 回填」：下次读取该键时 L1 未命中、L2 命中并回填 L1，
     * 对应 /api/v1/cache/stats 中 l1Miss 与 l2Hit 各加一。
     *
     * @param key 缓存键（如 course:1）
     * @return 统一响应体，data 含 evicted（被失效的键名）
     */
    @PostMapping("/evict-local")
    public R<Map<String, Object>> evictLocal(@RequestParam String key) {
        cacheManager.invalidateLocal(key);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evicted", key);
        return R.ok(data);
    }
}
