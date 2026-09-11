package com.zhanjh.hercules.controller;

import com.zhanjh.hercules.cache.core.CacheManager;
import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.R;
import com.zhanjh.hercules.mapper.CourseMapper;
import com.zhanjh.hercules.model.CacheVersion;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.sync.clock.ClockRelation;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.producer.InProcessEventBusProducer;
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

    /** 模拟节点的标识，与真实节点（hercules.sync.node-id，默认 node-1）区分开，两者分量互不支配。 */
    private static final String SIM_NODE_ID = "node-sim";
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

    /**
     * 版本变更生产者：发布模拟版本，经进程内事件总线立即被消费。
     * 阶段 B 注入具体类型（而非接口）：演示钩子在两种传输模式下都走进程内总线——
     * canal-mq 模式下业务写路径的同步由 binlog 链路负责，本端点语义不变。
     */
    private final InProcessEventBusProducer producer;

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
                           InProcessEventBusProducer producer,
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
     *   <li>模拟版本携带的时钟只含 node-sim 一个分量，不携带真实节点的分量，
     *       因此与「本地已提交的改动」互不支配，消费端 compare 判 CONCURRENT，
     *       走字段级 LWW 合并分支；</li>
     *   <li>node-sim 的取值不是固定的 1，而是「存量时钟里 node-sim 的值 + 1」。
     *       这是为了可重复演示：若固定写 1，第二次调用时存量时钟已经含有 node-sim（值 ≥ 1），
     *       新时钟会被存量时钟支配、判为过期消息直接丢弃，而接口仍然返回成功——
     *       表现为静默失效，现场连点两次就会踩到；</li>
     *   <li>模拟值时间戳取当前毫秒，晚于存量版本 → 合并后 courseName 取模拟值、
     *       其余字段保留旧值（FieldLwwMergeStrategy：值不同的字段按时间戳裁决）；</li>
     *   <li>响应里的 relation 字段直接给出本次的支配关系判定（CONCURRENT/AFTER/EQUAL/BEFORE），
     *       effect 字段说明对应的实际效果。若该课程从未发生过真实写入（t_cache_version 无记录），
     *       没有可冲突的对象，判定会是 AFTER，按新版本直接应用，此时不会有合并效果——这一点在响应里写清楚，
     *       不再用固定文案谎报「已同步应用」。</li>
     * </ol>
     *
     * <p>请求示例：{@code POST /api/v1/debug/simulate-conflict}，
     * 请求体 {@code {"courseId":1,"courseName":"并发改名"}}
     *
     * @param request 冲突模拟请求体（@Valid 校验 courseId 非空）
     * @return data 含 published（恒 true）、simNodeId、simClock、storedClock（调用时刻的存量时钟）、
     *         relation（支配关系）、effect（本次实际效果）、storedVersionBefore（存量版本号，无则提示文案）、hint
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
        CacheVersion stored = versionReader.find(key).orElse(null);
        VectorClock storedClock = stored == null
                ? new VectorClock()
                : VectorClock.fromJson(stored.getVectorClockJson());

        // 只取 node-sim 一个分量，且严格大于存量里 node-sim 的值：
        // 缺掉真实节点的分量 → 与本地改动互不支配（CONCURRENT）；自己分量递增 → 每次都能触发，不会第二次就失效
        long simPrev = storedClock.snapshot().getOrDefault(SIM_NODE_ID, 0L);
        VectorClock simClock = new VectorClock(Map.of(SIM_NODE_ID, simPrev + 1));
        ClockRelation relation = VectorClock.compare(simClock, storedClock);

        course.setCourseName(StringUtils.hasText(request.courseName())
                ? request.courseName()
                : course.getCourseName() + "[冲突模拟]");
        // 时间戳取当前毫秒：晚于存量版本 updateTime → 字段级 LWW 裁决时模拟值获胜
        producer.publish(new VersionedValue(key, JsonUtil.toJson(course),
                SIM_NODE_ID, System.currentTimeMillis(), simClock));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("published", true);
        data.put("simNodeId", SIM_NODE_ID);
        data.put("simClock", simClock.toJson());
        data.put("storedClock", storedClock.toJson());
        data.put("relation", relation.name());
        data.put("effect", describeEffect(relation));
        data.put("storedVersionBefore", stored == null ? "(无存量版本)" : stored.getCurrentVersion());
        data.put("hint", "消费端已按 relation 处理；GET /api/v1/courses/" + request.courseId() + " 查看结果");
        return R.ok(data);
    }

    /**
     * 把支配关系翻译成本次演示的实际效果，避免调用方看不出到底有没有触发合并。
     *
     * @param relation 时钟支配关系
     * @return 一句话说明
     */
    private static String describeEffect(ClockRelation relation) {
        return switch (relation) {
            case CONCURRENT -> "并发冲突：走字段级合并，课程名取模拟值，其它字段保留原值";
            case AFTER -> "不是并发：按新版本直接应用（该课程此前没有真实写入，没有可冲突的对象）";
            case EQUAL -> "与存量版本相同：会被当作重复消息丢弃，本次没有效果";
            case BEFORE -> "比存量版本旧：会被当作过期消息丢弃，本次没有效果";
        };
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
