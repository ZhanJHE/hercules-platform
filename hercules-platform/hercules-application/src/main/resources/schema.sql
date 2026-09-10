-- Hercules MVP schema
-- 兼容 MySQL 8 与 H2 (MODE=MySQL)。
-- 注：文档中的 JSON 列在本 MVP 统一用 TEXT 承载（应用层经 Jackson 校验），
--     待 Sprint 2 引入 JSON 函数查询需求时再切换，届时需同时调整 H2 测试脚本。

CREATE TABLE IF NOT EXISTS t_course (
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    course_code        VARCHAR(32)  NOT NULL,
    course_name        VARCHAR(128) NOT NULL,
    teacher_name       VARCHAR(64),
    credit             DECIMAL(3,1) NOT NULL,
    capacity           INT          NOT NULL,
    enrolled           INT          NOT NULL DEFAULT 0,
    schedule_json      TEXT,
    prerequisites_json TEXT,
    syllabus_url       VARCHAR(256),
    update_time        DATETIME(3)  NOT NULL,
    CONSTRAINT uk_course_code UNIQUE (course_code)
);

CREATE TABLE IF NOT EXISTS t_enrollment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id  BIGINT      NOT NULL,
    course_id   BIGINT      NOT NULL,
    status      TINYINT     NOT NULL,          -- 0-预选 1-已选 2-退选
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    -- 每对学生-课程至多一行，状态原地流转（选→退→再选为 UPDATE 回 status=1，不新增行）；
    -- 重复选课由应用层守卫拦截（EnrollmentService.enroll），本约束作为并发双击的数据库兜底
    CONSTRAINT uk_student_course UNIQUE (student_id, course_id)
);
-- 存量库升级提示（MySQL 8 的 CREATE TABLE IF NOT EXISTS 不会为已存在的表补约束，
-- 且不支持 ADD CONSTRAINT IF NOT EXISTS；已有数据的环境需手动执行一次）：
--   ALTER TABLE t_enrollment ADD CONSTRAINT uk_student_course UNIQUE (student_id, course_id);
--   （若历史数据已存在重复的 (student_id, course_id) 行，需先去重再执行）

CREATE TABLE IF NOT EXISTS t_cache_version (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    cache_key         VARCHAR(255) NOT NULL,
    node_id           VARCHAR(50)  NOT NULL,
    current_version   BIGINT       NOT NULL,
    vector_clock_json TEXT,
    update_time       DATETIME(3)  NOT NULL,
    CONSTRAINT uk_cache_key UNIQUE (cache_key)
);

-- 以下两张为占位表（Sprint 4 巡检引擎 / Sprint 5 智能体使用），MVP 不写入
CREATE TABLE IF NOT EXISTS t_repair_log (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    cache_key      VARCHAR(255) NOT NULL,
    mysql_value    TEXT,
    redis_value    TEXT,
    conflict_type  TINYINT,                     -- 1-版本冲突 2-数据丢失 3-数据不一致
    repair_status  TINYINT DEFAULT 0,           -- 0-待修复 1-自动修复成功 2-人工介入
    compensate_sql TEXT,
    retry_count    INT DEFAULT 0,
    create_time    DATETIME(3) NOT NULL,
    repair_time    DATETIME(3)
);

CREATE TABLE IF NOT EXISTS t_agent_trace (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id        VARCHAR(64) NOT NULL,
    session_id      VARCHAR(64) NOT NULL,
    agent_name      VARCHAR(64) NOT NULL,
    input_prompt    TEXT,
    output_content  TEXT,
    llm_model       VARCHAR(32),
    llm_latency_ms  INT,
    status          TINYINT,
    create_time     DATETIME(3) NOT NULL
);

-- 认证用户表（阶段 A+ 新增，起步文档 §5 设计增补）
-- 种子账号由应用启动器（AuthUserSeeder）幂等写入（BCrypt 密文启动时生成，避免静态弱密文）
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(60)  NOT NULL,          -- BCrypt（强度 10）
    role        VARCHAR(20)  NOT NULL,          -- STUDENT / ADMIN
    student_id  BIGINT,                         -- 学生业务号（选课用）；管理员为 NULL
    status      TINYINT      NOT NULL DEFAULT 1,-- 1 启用 0 停用
    create_time DATETIME(3)  NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
);
