# Hercules Platform —— 智慧校园多智能体服务平台（Maven 多模块）

对应《起步文档》的多模块架构（§3.2）。当前已完成：

- **阶段 0**：MVP 纵向切片 + Javadoc 全量注释 + 三项修复（35/35 测试）；
- **阶段 A**：单模块重构为 Maven 多模块（11 模块），功能等价迁移 + 真机冒烟复验通过。

> 单模块历史版本见 git 基线提交 `bfb4b6e`（原 `hercules/` 目录已退役删除）。

## 1. 模块结构

```
hercules-platform/
├── hercules-common/        # 公共：统一响应体/异常/JSON工具/缓存键约定/实体/Mapper
├── hercules-cache/         # 缓存治理：多级缓存门面（Caffeine L1→Redis L2→DB）、TTL策略、统计
├── hercules-sync/          # 数据同步：向量时钟、版本生产/消费、字段级LWW合并（Canal+RocketMQ 为 Sprint 2）
├── hercules-inspector/     # 巡检自愈（阶段 C 填充）
├── hercules-rag/           # RAG 检索（阶段 E 填充）
├── hercules-agent/         # 多智能体（阶段 D 填充）
├── hercules-observability/ # 可观测性（阶段 F 填充）
├── hercules-admin/         # 管控台后端（阶段 F/H 填充）
├── hercules-gateway/       # Spring Cloud Gateway 独立网关（阶段 H 填充）
├── hercules-application/   # ★ 唯一可执行聚合模块：启动类/REST/服务/全部配置
└── hercules-ui/            # Vue3 前端（阶段 G，npm 工程）
```

依赖方向：common ← cache ← sync；application 聚合 common/cache/sync（后续阶段递增）。

## 2. 环境要求（本机已具备）

| 组件 | 说明 |
| :--- | :--- |
| JDK 21+（本机 25 可用） | 编译目标 `java.version=21` |
| MySQL 8 | 本机 `127.0.0.1:3306`，库 `hercules` 首次启动自动创建 |
| Redis | 本机 `127.0.0.1:6379`，无密码 |
| Maven | 用 wrapper（仓库根 `mvnw.cmd`，3.9.16） |

数据库凭据在 `hercules-application/src/main/resources/application-local.yml`（默认 profile=local，已 gitignore）。

## 3. 构建与启动

```powershell
cd hercules-platform
.\mvnw.cmd test            # 全模块 35 个测试（H2 + 内存桩 L2，不依赖本机 MySQL/Redis）
.\mvnw.cmd package -DskipTests
java -jar hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar
# 或：.\mvnw.cmd spring-boot:run -pl hercules-application
```

## 4. 接口清单（与单模块版一致）

| Method | Path | 说明 |
| :--- | :--- | :--- |
| GET | `/api/v1/courses?page=&size=&keyword=` | 课程分页/检索（多级缓存） |
| GET | `/api/v1/courses/{id}` | 课程详情（多级缓存，纳入版本链） |
| POST | `/api/v1/enrollment` | 选课（@Valid 校验 + 防超选事务 + 同步链刷缓存） |
| DELETE | `/api/v1/enrollment?studentId=&courseId=` | 退课 |
| GET | `/api/v1/cache/stats` | 命中率/各级计数/同步与冲突计数 |
| POST | `/api/v1/debug/simulate-conflict` | 模拟 node-sim 并发写（演示 LWW 合并） |
| POST | `/api/v1/debug/evict-local?key=` | 手动失效 L1（演示 L2 回填） |
| GET | `/actuator/prometheus` | Micrometer 指标 |

## 5. 现场演示脚本

```powershell
curl "http://127.0.0.1:8080/api/v1/courses?page=1&size=10"
curl http://127.0.0.1:8080/api/v1/cache/stats
curl -X POST "http://127.0.0.1:8080/api/v1/debug/evict-local?key=course:list:1:10:-"
curl -X POST http://127.0.0.1:8080/api/v1/enrollment -H "Content-Type: application/json" -d '{"studentId":20240001,"courseId":1}'
curl -X POST http://127.0.0.1:8080/api/v1/debug/simulate-conflict -H "Content-Type: application/json" -d '{"courseId":1,"courseName":"程序设计基础(合并后)"}'
curl http://127.0.0.1:8080/api/v1/courses/1
```

详细链路说明、FR 对照表、已知事项见《开发文档/包结构说明.md》与《开发文档/工作日志.md》。

## 6. 已知取舍（随阶段演进消解）

- 列表键短 TTL 现已 L1/L2 双级生效（Caffeine 自定义 Expiry）；
- Canal/RocketMQ 真实 Binlog 链路：阶段 B（`hercules.sync.transport` 开关，in-process 为默认）；
- 巡检/智能体/RAG/网关/前端：按阶段 C/D/E/G/H 依次填充。
