# Hercules —— 智慧校园多智能体服务平台（MVP：缓存一致性治理核心）

对应《起步文档》Sprint 0+1 的第一个**可运行纵向切片**：

> 课程查询走「Caffeine(L1) → Redis(L2) → MySQL」多级缓存；选课事务提交后由**向量时钟同步链**刷新缓存；并发写冲突走**字段级 LWW 合并**。Canal/RocketMQ、智能体、RAG、巡检引擎等按冲刺计划延后（接口已预留）。

## 1. 环境要求（本机已具备）

| 组件 | 说明 |
| :--- | :--- |
| JDK 21+（本机 25 可用） | 编译目标 `java.version=21` |
| MySQL 8 | 本机 `127.0.0.1:3306`，库 `hercules` 首次启动自动创建 |
| Redis | 本机 `127.0.0.1:6379`，无密码 |
| Maven | 无需安装，用 wrapper `mvnw.cmd`（3.9.16，首次运行自动下载） |

数据库凭据放在 `src/main/resources/application-local.yml`（默认 profile=local，已 gitignore）。

## 2. 启动

```powershell
cd hercules
.\mvnw.cmd spring-boot:run     # 首次启动自动执行 schema.sql / data.sql（幂等）
```

## 3. 接口清单

| Method | Path | 说明 |
| :--- | :--- | :--- |
| GET | `/api/v1/courses?page=&size=&keyword=` | 课程分页/关键字检索（走多级缓存） |
| GET | `/api/v1/courses/{id}` | 课程详情（走多级缓存） |
| POST | `/api/v1/enrollment` | 选课（事务 + 防超选，提交后同步链刷缓存） |
| DELETE | `/api/v1/enrollment?studentId=&courseId=` | 退课 |
| GET | `/api/v1/cache/stats` | 命中率、各级命中统计、同步/冲突计数 |
| POST | `/api/v1/debug/simulate-conflict` | 模拟另一节点并发写（演示冲突合并） |
| POST | `/api/v1/debug/evict-local?key=` | 手动失效 L1（演示 L2 回填） |
| GET | `/actuator/prometheus` | Micrometer 指标（Grafana Sprint 7 接入） |

## 4. 现场演示脚本（答辩用）

```powershell
# ① 缓存命中：第一次走 DB，第二次 L1 命中
curl http://127.0.0.1:8080/api/v1/courses?page=1&size=10
curl http://127.0.0.1:8080/api/v1/cache/stats           # dbLoad=1, l1Hit 增加

# ② L1 失效 → L2 回填
curl -X POST "http://127.0.0.1:8080/api/v1/debug/evict-local?key=course:list:1:10:-"
curl http://127.0.0.1:8080/api/v1/courses?page=1&size=10
curl http://127.0.0.1:8080/api/v1/cache/stats           # l2Hit=1, dbLoad 不变

# ③ 选课：写库 + 版本同步
curl -X POST http://127.0.0.1:8080/api/v1/enrollment -H "Content-Type: application/json" -d '{"studentId":20240001,"courseId":1}'
curl http://127.0.0.1:8080/api/v1/courses/1             # enrolled=88（同步链已刷缓存）

# ④ 并发冲突合并：node-sim 的时钟与 node-1 互不支配 → 字段级 LWW
curl -X POST http://127.0.0.1:8080/api/v1/debug/simulate-conflict -H "Content-Type: application/json" -d '{"courseId":1,"courseName":"程序设计基础(合并后)"}'
curl http://127.0.0.1:8080/api/v1/courses/1             # courseName 合并、enrolled 保留
curl http://127.0.0.1:8080/api/v1/cache/stats           # conflictDetected=1
```

## 5. 核心链路说明

```
读路径  API → MultiLevelCacheManager: L1(Caffeine) → L2(Redis) → MySQL，逐级回填
写路径  选课事务（防超选 UPDATE ... WHERE enrolled < capacity）
        → AFTER_COMMIT 发布 VersionedValue（时钟 = 存量时钟 ⊕ 本节点自增）
同步链  VersionChangeConsumer 对比 t_cache_version 存量时钟：
        AFTER   → 写 Redis + 失效 Caffeine + upsert 版本记录
        EQUAL/BEFORE → 丢弃（幂等）
        CONCURRENT → 字段级 LWW 合并 → 应用 + 冲突计数 + 告警日志
```

关键类（对应《设计类》蓝图）：`VectorClock`、`VersionedValue`、`MultiLevelCacheManager`、`VersionChangeConsumer`、`FieldLwwMergeStrategy`、`InProcessEventBusProducer`。

## 6. 测试

```powershell
.\mvnw.cmd test    # H2 内存库 + 桩 L2，不依赖本机 MySQL/Redis
```

覆盖：向量时钟（支配/并发/合并/序列化）、字段级 LWW 合并、多级缓存命中/回填/失效、同步消费者三分支（新版本/旧版本/并发冲突）、端到端冒烟（含防超选 409）。

## 7. 已实现 vs 延后（FR 对照）

| 需求 | 状态 | 去向 |
| :--- | :--- | :--- |
| FR-HC-01 多级缓存 | ✅ MVP | — |
| FR-HC-03 向量时钟冲突消解 | ✅ MVP（Canal+MQ 换传输层） | Sprint 2 |
| FR-HC-02 Canal+RocketMQ 真实 Binlog | ⏳ 接口已预留 | Sprint 2 |
| FR-HC-04/05 巡检引擎、补偿 SQL | ⏳ t_repair_log 已建表 | Sprint 4 |
| FR-MA/FR-RAG 智能体/RAG | ⏳ 需 LLM API Key | Sprint 5-6 |
| FR-OB 可观测性 | 部分（actuator+Micrometer） | Sprint 7 |
| 鉴权/网关/UI/K8s | ⏳ | 后续 |

## 8. 已知取舍（MVP 范围外）

- JSON 列以 TEXT 承载（应用层校验），Sprint 2 如需 JSON 函数再切换。
- 列表键走短 TTL（10s）最终一致，不纳入版本链；详情键走完整向量时钟链路。
- Caffeine 为全局固定 TTL；自适应 TTL 策略留接口（`TTLStrategy`）。
- 同步链进程内事件总线为同线程串行消费；吞吐提升（批量/并发消费）随 RocketMQ 一起做。
