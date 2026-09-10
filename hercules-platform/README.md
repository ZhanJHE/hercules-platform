# Hercules Platform —— 智慧校园多智能体服务平台（Maven 多模块）

对应《起步文档》的多模块架构（§3.2）。当前已完成：

- **阶段 0**：MVP 纵向切片（多级缓存 + 向量时钟同步链 + 防超选）；
- **阶段 A**：单模块重构为 Maven 多模块（11 模块），功能等价迁移；
- **阶段 A+**：高可用加固（全链路超时/Redis 熔断降级/防击穿）+ 双 Token 认证 + 日志设计（traceId 贯通/审计落盘）；
- **阶段 H-1~H-4**：WSL2/Docker 全栈部署（六容器）+ 最小网关（JWT 验签/透传头防伪造）+ JMeter 500 并发阶梯压测（5,027 req/s，错误率 0.0007%）与 JVM/连接池调优；
- **阶段 B**：真实 Binlog 同步链（Canal 1.1.7 + RocketMQ 4.9.4，九容器栈）——`hercules.sync.transport=canal-mq` 切换，业务事务只落库，缓存刷新由 binlog 链路异步驱动（实测外部直改库 250ms 同步缓存）；
- **阶段 D**：多智能体对话入口（Spring AI 1.0.6 + GLM glm-4.5-air）——意图路由（规则优先 + LLM 兜底）/ 推荐（候选检索 + 冲突标注 + LLM 流式生成）/ 排课冲突（纯规则）/ 确认制执行，SSE 流式，t_agent_trace 落库，LLM 故障 1s 降级。

自动化测试 **96/96 全绿**（common 4 + cache 21 + sync 36 + agent 13 + application 22，H2 + 内存桩 + LLM 脚本桩，不依赖本机中间件与网络）。

> 单模块历史版本见 git 基线提交 `bfb4b6e`（原 `hercules/` 目录已退役删除）。
> 网页版完整讲解见 `开发文档/项目讲解.html`。

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
.\mvnw.cmd test            # 全模块 96 个测试（H2 + 内存桩 + LLM 脚本桩，不依赖本机 MySQL/Redis/网络）
.\mvnw.cmd package -DskipTests
java -jar hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar
# 或：.\mvnw.cmd spring-boot:run -pl hercules-application
```

## 4. 接口清单（认证后契约）

| Method | Path | 说明 | 权限 |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/auth/login` | 登录（双 Token） | 公开 |
| POST | `/api/v1/auth/refresh` | 刷新（旋转式） | 公开 |
| POST | `/api/v1/auth/logout` | 登出（jti 黑名单） | 登录 |
| GET | `/api/v1/courses?page=&size=&keyword=` | 课程分页/检索（多级缓存；page≥1，1≤size≤100） | 登录 |
| GET | `/api/v1/courses/{id}` | 课程详情（纳入版本链） | 登录 |
| POST | `/api/v1/enrollment` | 选课（body 仅 `{courseId}`，studentId 取自 token；重复选课 409） | STUDENT |
| DELETE | `/api/v1/enrollment?courseId=` | 退课 | STUDENT |
| GET | `/api/v1/enrollment/mine` | 本人选课记录 | STUDENT |
| GET | `/api/v1/cache/stats` | 命中率/各级计数/同步与冲突计数 | ADMIN |
| POST | `/api/v1/debug/simulate-conflict` | 模拟 node-sim 并发写（演示 LWW 合并） | ADMIN |
| POST | `/api/v1/debug/evict-local?key=` | 手动失效 L1（演示 L2 回填） | ADMIN |
| GET | `/actuator/prometheus` | Micrometer 指标 | 公开 |

## 5. 现场演示脚本（登录先行）

```powershell
$token = (Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/auth/login" `
  -ContentType "application/json" -Body '{"username":"st001","password":"123456"}').data.accessToken
curl "http://127.0.0.1:8080/api/v1/courses?page=1&size=10" -H "Authorization: Bearer $token"
curl -X POST http://127.0.0.1:8080/api/v1/enrollment -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d '{"courseId":1}'
curl http://127.0.0.1:8080/api/v1/enrollment/mine -H "Authorization: Bearer $token"
```

部署（Docker 五容器）与压测使用说明见《开发文档/环境配置与部署.md》；
详细链路说明、FR 对照表、已知事项见《开发文档/包结构说明.md》与《开发文档/工作日志.md》。

## 6. 已知取舍（随阶段演进消解）

- Canal/RocketMQ 真实 Binlog 链路：阶段 B（`hercules.sync.transport` 开关，in-process 为默认）；
- 巡检/智能体/RAG/前端：按阶段 C/D/E/G 依次填充；
- 冲突合并旧值取自 L2，L2 逐出后退化为整体采用新值（设计级限制，已记录）；
- 存量 MySQL 库需手动执行一次 `ALTER TABLE t_enrollment ADD CONSTRAINT uk_student_course UNIQUE (student_id, course_id)`（见 schema.sql 注记）。
