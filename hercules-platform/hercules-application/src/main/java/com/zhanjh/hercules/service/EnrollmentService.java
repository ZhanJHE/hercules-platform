package com.zhanjh.hercules.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhanjh.hercules.common.BusinessException;
import com.zhanjh.hercules.common.CacheKeys;
import com.zhanjh.hercules.common.JsonUtil;
import com.zhanjh.hercules.mapper.CourseMapper;
import com.zhanjh.hercules.mapper.EnrollmentMapper;
import com.zhanjh.hercules.model.Course;
import com.zhanjh.hercules.model.Enrollment;
import com.zhanjh.hercules.sync.config.HerculesSyncProperties;
import com.zhanjh.hercules.sync.model.VersionedValue;
import com.zhanjh.hercules.sync.producer.VersionChangeProducer;
import com.zhanjh.hercules.sync.clock.VectorClock;
import com.zhanjh.hercules.sync.support.VersionReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 选课写路径服务：选课（enroll）与退课（withdraw），事务内写库，提交后经向量时钟同步链刷新缓存。
 *
 * <p>核心机制——「先提交、后同步」：
 * <ol>
 *   <li>人数增减与选课记录状态流转在数据库事务内完成；容量约束不依赖「先查后写」，
 *       而由带 WHERE 条件的原子 UPDATE 兜底，并发下不会超选、也不会减为负数；</li>
 *   <li>事务内 publishCourseVersion 以「存量时钟 ⊕ 本节点自增」构建版本并发布事件；</li>
 *   <li>事件由 VersionChangeEventListener 在 AFTER_COMMIT 阶段驱动消费者：
 *       更新 Redis（course:{id}）、失效本机 L1、upsert t_cache_version。</li>
 * </ol>
 *
 * <p>线程安全性：无实例状态；并发防超选依赖数据库行级锁下的条件更新，天然安全。
 *
 * <p>扩展点：MVP 经进程内事件总线（InProcessEventBusProducer）发布，
 * Sprint 2 计划替换为 Canal 监听 Binlog → RocketMQ 顺序消息，本层代码不变。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Service
public class EnrollmentService {

    /** 课程表访问接口（t_course）：含防超选的 increaseEnrolled / decreaseEnrolled 原子增减 */
    private final CourseMapper courseMapper;

    /** 选课记录表访问接口（t_enrollment） */
    private final EnrollmentMapper enrollmentMapper;

    /** 版本读取器：加载缓存键的存量向量时钟（t_cache_version.vector_clock_json） */
    private final VersionReader versionReader;

    /** 版本变更生产者：MVP 实现为 Spring 进程内事件总线，事务提交后才被消费 */
    private final VersionChangeProducer producer;

    /** 同步链配置：本节点时钟分量名（hercules.sync.node-id，默认 node-1） */
    private final HerculesSyncProperties syncProps;

    /**
     * 构造注入（final 字段持有不可变引用）。
     *
     * @param courseMapper     课程表访问接口（含原子增减）
     * @param enrollmentMapper 选课记录表访问接口
     * @param versionReader    存量版本/向量时钟读取器
     * @param producer         版本变更生产者
     * @param syncProps        同步链配置（node-id、max-nodes）
     */
    public EnrollmentService(CourseMapper courseMapper,
                             EnrollmentMapper enrollmentMapper,
                             VersionReader versionReader,
                             VersionChangeProducer producer,
                             HerculesSyncProperties syncProps) {
        this.courseMapper = courseMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.versionReader = versionReader;
        this.producer = producer;
        this.syncProps = syncProps;
    }

    /**
     * 选课：扣减课程余量并写入选课记录（事务内完成）。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>查课程，不存在 → 404；</li>
     *   <li>{@code increaseEnrolled}：{@code UPDATE t_course SET enrolled = enrolled + 1
     *       WHERE id = ? AND enrolled < capacity}，0 行受影响说明余量已满 → 409（防超选核心）；</li>
     *   <li>插入 t_enrollment：status=1（已选），createTime/updateTime 取当前时间；</li>
     *   <li>发布课程版本事件（事务提交后才被消费，刷新 course:{id} 缓存）。</li>
     * </ol>
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 新建的选课记录（含自增主键，status=1 已选）
     * @throws BusinessException code=404（HTTP 404）课程不存在；
     *                           code=409（HTTP 409）课程容量已满（enrolled 已达 capacity）
     */
    @Transactional
    public Enrollment enroll(Long studentId, Long courseId) {
        Course course = courseMapper.selectById(courseId);
        // 此处查询仅提供 404 语义与满员提示文案；真正的防超选由下一步条件 UPDATE 保证
        if (course == null) {
            throw new BusinessException(404, "课程不存在: " + courseId);
        }
        // 原子条件更新：并发扣减由数据库行锁串行化，余量不足时 0 行受影响 → 409
        int rows = courseMapper.increaseEnrolled(courseId);
        if (rows == 0) {
            throw new BusinessException(409, "课程容量已满: " + course.getCourseName());
        }
        LocalDateTime now = LocalDateTime.now();
        Enrollment enrollment = new Enrollment();
        enrollment.setStudentId(studentId);
        enrollment.setCourseId(courseId);
        enrollment.setStatus(Enrollment.STATUS_ENROLLED);
        enrollment.setCreateTime(now);
        enrollment.setUpdateTime(now);
        enrollmentMapper.insert(enrollment);
        publishCourseVersion(courseId);
        return enrollment;
    }

    /**
     * 退课：将最新一条有效选课记录置为退选，并回退课程已选人数（事务内完成）。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>查该学生该课程最新一条 status=1 记录（id 倒序 LIMIT 1），无 → 404；</li>
     *   <li>{@code decreaseEnrolled}：{@code UPDATE ... SET enrolled = enrolled - 1
     *       WHERE id = ? AND enrolled > 0}，0 行受影响 → 409；</li>
     *   <li>该记录置 status=2（退选）并刷新 updateTime——只做状态流转不删行，保留选课流水；</li>
     *   <li>发布课程版本事件。</li>
     * </ol>
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 更新后的选课记录（status=2 退选）
     * @throws BusinessException code=404（HTTP 404）未找到有效选课记录；
     *                           code=409（HTTP 409）已选人数为 0，无法退课
     */
    @Transactional
    public Enrollment withdraw(Long studentId, Long courseId) {
        // 取最大 id 的一条 status=1 记录作为当前有效记录：兼容「退选后再选」产生的多条历史
        Enrollment existing = enrollmentMapper.selectOne(new QueryWrapper<Enrollment>()
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("status", Enrollment.STATUS_ENROLLED)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (existing == null) {
            throw new BusinessException(404, "未找到有效选课记录");
        }
        // 防御分支：存在 status=1 记录时 enrolled 正常必 > 0；0 行说明数据已被并发操作破坏
        int rows = courseMapper.decreaseEnrolled(courseId);
        if (rows == 0) {
            throw new BusinessException(409, "已选人数为 0，无法退课");
        }
        existing.setStatus(Enrollment.STATUS_WITHDRAWN);
        existing.setUpdateTime(LocalDateTime.now());
        enrollmentMapper.updateById(existing);
        publishCourseVersion(courseId);
        return existing;
    }

    /**
     * 构建并发布课程详情的版本化值（在业务事务内调用，消费推迟到事务提交后）。
     *
     * <p>机制：
     * <ul>
     *   <li>同事务重读课程：与前面的写操作共用同一数据库连接，能读到本事务未提交的
     *       自身更新，valueJson 因此携带本次选/退课后的最新课程状态；</li>
     *   <li>时钟 = 存量时钟（t_cache_version.vector_clock_json，无记录则为空时钟）
     *       ⊕ 本节点分量自增（hercules.sync.node-id，默认 node-1）；</li>
     *   <li>发布 {@link VersionedValue}：valueJson 为课程 JSON，timestamp 取当前毫秒
     *       （供 CONCURRENT 冲突时的字段级 LWW 合并裁决），key 固定为 course:{id}。</li>
     * </ul>
     *
     * <p>事务提交后由 VersionChangeEventListener（AFTER_COMMIT，fallbackExecution 兼容无事务调用方）
     * 驱动 VersionChangeConsumer 消费；事务回滚则事件不被消费，缓存保持旧值。
     *
     * @param courseId 课程 ID
     */
    private void publishCourseVersion(Long courseId) {
        // 同事务重读：读到的是本事务未提交的最新状态（含本次变更后的已选人数）
        Course fresh = courseMapper.selectById(courseId);
        String key = CacheKeys.course(courseId);
        // 存量时钟 ⊕ 本节点自增：新版本与存量保持因果链，消费端据此判定 AFTER / CONCURRENT
        VectorClock incoming = versionReader.loadClock(key).increment(syncProps.getNodeId());
        producer.publish(new VersionedValue(key, JsonUtil.toJson(fresh),
                syncProps.getNodeId(), System.currentTimeMillis(), incoming));
    }
}
