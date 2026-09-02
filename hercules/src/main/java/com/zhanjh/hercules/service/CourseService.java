package com.zhanjh.hercules.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhanjh.hercules.cache.core.CacheManager;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.common.PageResult;
import com.zhanjh.hercules.mapper.CourseMapper;
import com.zhanjh.hercules.model.Course;
import org.springframework.stereotype.Service;

/**
 * 课程查询服务：课程读路径统一入口，全部经多级缓存（L1 Caffeine → L2 Redis → MySQL，cache-aside）。
 *
 * <p>职责与缓存键约定：
 * <ul>
 *   <li>详情查询走详情键 {@code course:{id}}：纳入向量时钟版本链（t_cache_version），
 *       写路径变更后由同步链更新 Redis 并失效本机 L1，L2 侧 TTL 300s（hercules.cache.remote-ttl）；</li>
 *   <li>列表查询走列表键 {@code course:list:{page}:{size}:{keyword}}（keyword 空白记 "-"）：
 *       不纳入版本链，靠 10s 短 TTL（hercules.cache.list-ttl）换取最终一致。</li>
 * </ul>
 *
 * <p>缓存值统一以 JSON 字符串承载：查库结果先经 JsonUtil 序列化再逐级回填，
 * 读取后反序列化还原，保证 L1/L2/DB 三层数据形态一致。
 *
 * <p>线程安全性：本类无实例状态，依赖均为无状态 Bean，可安全并发调用；
 * 并发回源由 MultiLevelCacheManager 与 Caffeine 自身语义兜底（MVP 未加分布式互斥锁）。
 *
 * <p>扩展点：列表键的短 TTL 窗口策略由 FixedTTLStrategy 按 key 前缀区分，后续可替换为
 * 其他 TTLStrategy 实现；读路径本身不感知同步链。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Service
public class CourseService {

    /** 课程表访问接口（t_course）：提供 selectById 与分页查询 */
    private final CourseMapper courseMapper;

    /** 多级缓存门面：承担 L1/L2 读取、逐级回填与读写统计计数 */
    private final CacheManager cacheManager;

    /**
     * 构造注入（final 字段持有不可变引用）。
     *
     * @param courseMapper 课程表访问接口
     * @param cacheManager 多级缓存门面
     */
    public CourseService(CourseMapper courseMapper, CacheManager cacheManager) {
        this.courseMapper = courseMapper;
        this.cacheManager = cacheManager;
    }

    /**
     * 按 ID 查询课程详情（详情键 {@code course:{id}}）。
     *
     * <p>cache-aside 读取顺序：L1 → L2 → loader 回源 DB，逐级回填
     * （L2 命中回填 L1；DB 回源同时写 L2 与 L1）。课程不存在时 loader 返回 null，
     * 缓存层不缓存空值、直接透传 null，由 Controller 层转换为 404。
     *
     * @param id 课程主键 ID
     * @return 课程对象；课程不存在时返回 {@code null}
     */
    public Course getCourse(Long id) {
        String key = CacheKeys.course(id);
        String json = cacheManager.get(key, () -> {
            // loader 仅在 L1/L2 均未命中时执行；null 不入缓存，避免空值占位并统一由上层转 404
            Course course = courseMapper.selectById(id);
            return course == null ? null : JsonUtil.toJson(course);
        });
        return JsonUtil.parse(json, Course.class);
    }

    /**
     * 分页 + 关键字查询课程列表（列表键 {@code course:list:{page}:{size}:{keyword}}）。
     *
     * <p>缓存策略：列表键使用 10s 短 TTL（最终一致窗口），不纳入向量时钟版本链——
     * 选课/退课造成的已选人数变化最多延迟 10s 反映到列表，以此换取写路径免逐键失效。
     *
     * @param page    页码，从 1 开始（与 MyBatis-Plus Page 的 current 语义一致）
     * @param size    每页条数
     * @param keyword 搜索关键字；null 或空白时缓存键记 "-"，查询退化为全量分页；
     *                非空白时先 trim 再参与匹配与缓存键（"  foo " 与 "foo" 命中同一键）
     * @return 自定义分页结果（total/current/size/records），经 JSON 往返还原
     */
    public PageResult<Course> list(int page, int size, String keyword) {
        String key = CacheKeys.courseList(page, size, keyword);
        String json = cacheManager.get(key, () -> JsonUtil.toJson(queryDb(page, size, keyword)));
        return JsonUtil.parse(json, new TypeReference<PageResult<Course>>() {
        });
    }

    /**
     * 真正的 DB 分页查询（仅列表缓存未命中时执行）。
     *
     * <p>实现要点：
     * <ol>
     *   <li>MyBatis-Plus {@link Page} 承载分页参数，由 PaginationInnerInterceptor
     *       生成 COUNT + LIMIT 语句（方言按数据源连接自动识别）；</li>
     *   <li>keyword 非空白时对 course_name / course_code / teacher_name 三列做 OR LIKE 模糊匹配；</li>
     *   <li>固定按 id 升序，保证分页结果稳定、各节点缓存到的列表内容一致；</li>
     *   <li>结果映射为自定义 {@link PageResult}，避免直接序列化 MyBatis-Plus Page
     *       （其内部字段与分页插件实现耦合，JSON 形态不稳定）。</li>
     * </ol>
     *
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @param keyword 搜索关键字；null 或空白时不加任何过滤条件
     * @return 当前页课程列表与分页元信息
     */
    private PageResult<Course> queryDb(int page, int size, String keyword) {
        Page<Course> p = new Page<>(page, size);
        QueryWrapper<Course> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // 用 and() 把三个 OR 条件括成一组，避免将来叠加其他 AND 条件时出现优先级歧义
            qw.and(w -> w.like("course_name", kw)
                    .or().like("course_code", kw)
                    .or().like("teacher_name", kw));
        }
        // 固定 id 升序：无 ORDER BY 时分页结果顺序不确定，会导致各节点缓存到不一致的列表
        qw.orderByAsc("id");
        Page<Course> result = courseMapper.selectPage(p, qw);
        // 剥离 MP Page 的内部字段，使缓存 JSON 与接口响应形态保持稳定
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }
}
