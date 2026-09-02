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
    update_time DATETIME(3) NOT NULL
);

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
