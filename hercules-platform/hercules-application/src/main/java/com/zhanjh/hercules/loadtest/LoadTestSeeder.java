package com.zhanjh.hercules.loadtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 压测数据播种器（阶段 H-4 前置，默认关闭）。
 *
 * <p>解决的问题：演示种子仅 50 门课程，全量可被 L1 容量装下——压测会退化为
 * 「纯缓存测试」，DB 回源/单飞/列表分页深度/写并发行锁分布均无法真实观测。
 *
 * <p>机制：开关开启（hercules.loadtest.seed=true，压测部署的 compose 环境注入）时，
 * 启动期用 JdbcTemplate 批量插入压测课程（默认补至 5000 门，INSERT IGNORE 幂等，
 * 可随重启续播）；课程行分散使 500 写并发的行锁竞争均匀分布。演示种子（50 门）
 * 与压测数据（LOADTEST-* 前缀）互不干扰。
 *
 * <p>容量评估：5000 行批量插入约为秒级启动开销；不上压测环境时开关关闭，零成本。
 *
 * <p>线程安全性：启动期单线程执行。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
@ConditionalOnProperty(name = "hercules.loadtest.seed", havingValue = "true")
public class LoadTestSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestSeeder.class);

    private static final String COURSE_CODE_PREFIX = "LOADTEST-";
    /** 批量插入的批次大小。 */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final int courseCount;

    public LoadTestSeeder(JdbcTemplate jdbcTemplate,
                          @org.springframework.beans.factory.annotation.Value("${hercules.loadtest.course-count:5000}")
                          int courseCount) {
        this.jdbcTemplate = jdbcTemplate;
        this.courseCount = courseCount;
    }

    /**
     * 启动时补齐压测课程至目标数量（幂等：已有 LOADTEST-% 行计入、INSERT IGNORE 兜底）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_course WHERE course_code LIKE ?",
                Long.class, COURSE_CODE_PREFIX + "%");
        int remaining = courseCount - (existing == null ? 0 : existing.intValue());
        if (remaining <= 0) {
            log.info("[hercules-loadtest] load-test courses already sufficient ({} >= {}), skip",
                    existing, courseCount);
            return;
        }
        log.info("[hercules-loadtest] seeding {} load-test courses...", remaining);

        Random random = new Random(42L); // 固定种子：容量/学分分布可复现
        String sql = "INSERT IGNORE INTO t_course (course_code, course_name, teacher_name, credit, "
                + "capacity, enrolled, schedule_json, prerequisites_json, syllabus_url, update_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))";

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        long baseId = existing; // course_code 编号从已有数量续起，保证幂等
        for (int i = 1; i <= remaining; i++) {
            long n = baseId + i;
            // 容量 100000 + 已选 ≤2000：500 并发 10 分钟压测绝不会打满（409 不污染错误率统计）
            batch.add(new Object[]{
                    COURSE_CODE_PREFIX + n,
                    "压测课程-" + n,
                    "压测教师-" + (n % 50 + 1),
                    1.0 + (n % 8) * 0.5,
                    100000,
                    random.nextInt(2000),
                    "{\"day\":" + (n % 5 + 1) + ",\"sections\":[" + (n % 4 + 1) + "," + (n % 4 + 2) + "]}",
                    "[]",
                    "/loadtest/" + n + ".pdf"
            });
            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        log.info("[hercules-loadtest] seeded {} load-test courses (total LOADTEST rows now ~{})",
                remaining, courseCount);
    }
}
