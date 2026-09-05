<div align="center">

# Hercules —— 智慧校园多智能体服务平台

**基于多级缓存一致性治理 + 多智能体协作的高校选课服务平台**

`Java 21` · `Spring Boot 3.5.x` · `Maven 多模块` · `Caffeine + Redis 多级缓存` · `向量时钟冲突消解`

</div>

---

## 📖 项目简介

Hercules 是一个面向高校选课场景的毕业设计项目，采用**四层分离架构**：

- **L1 数据存储层**：MySQL 8 + Redis 7（后续接入 Milvus / Neo4j）；
- **L2 一致性治理层**：多级缓存（Caffeine L1 → Redis L2 → MySQL）+ 基于向量时钟的并发冲突消解同步链（核心创新点）；
- **L3 多智能体决策层**：路由 / 推荐 / 排课冲突 / 执行四类智能体协作（规划中）；
- **L4 用户接入层**：Spring Cloud Gateway + Vue3 双端界面（规划中）。

当前已完成**分布式缓存一致性治理核心**的可运行实现：课程查询走多级缓存、选课事务提交后由同步链刷新缓存、并发写冲突走字段级 LWW 合并，全链路可观测、可演示、35 个自动化测试全覆盖。

## ✨ 核心特性

- 🗂 **多级缓存**：Caffeine（L1）→ Redis（L2）→ MySQL 的 cache-aside 读路径，逐级回填，命中率统计；
- 🕰 **向量时钟冲突消解**：每次缓存变更携带向量时钟，消费者判定「新版本应用 / 旧版本幂等丢弃 / 并发冲突字段级 LWW 合并」，版本持久化于 `t_cache_version`；
- 🔒 **防超选选课**：条件 `UPDATE ... WHERE enrolled < capacity` 原子扣减 + 事务提交后（AFTER_COMMIT）同步刷新缓存，先提交、后同步；
- 🔐 **双 Token 认证**：JWT（30min，jjwt 0.13.0）+ Refresh Token（7d，Redis 旋转式刷新），登出 jti 黑名单，BCrypt 密码，水平越权收敛（studentId 取自 token），应用内鉴权（阶段 H 迁网关统一验签）；
- 🧪 **可测试性**：单元测试不依赖外部中间件（H2 内存库 + 内存桩 L2），端到端冒烟覆盖完整链路；
- 📊 **可观测**：Micrometer 指标族（`hercules_cache_*` / `hercules_sync_*`）经 `/actuator/prometheus` 暴露，自定义命中率统计接口；
- 🎬 **答辩演示钩子**：`simulate-conflict` 一键模拟双节点并发写冲突、`evict-local` 演示 L1 失效后 L2 回填；
- 🚀 **一键启动**：根目录 `start.bat` 双击即起（打包 → 启动 → 健康检查 → 自动打开浏览器）。

## 🧰 技术栈与组件

| 分类 | 组件 | 版本 | 用途 |
| :--- | :--- | :--- | :--- |
| 语言 | Java | 21（兼容 JDK 25 运行） | 编译目标 `java.version=21` |
| 核心框架 | Spring Boot | 3.5.16 | Web / Validation / Actuator |
| 构建工具 | Maven (wrapper) | 3.9.16 | 11 模块多模块工程 |
| 持久层 | MyBatis-Plus | 3.5.17（+ jsqlparser 模块） | ORM / 分页 / 条件构造 |
| 数据库 | MySQL | 8.x | 业务库（连接串自动建库 + 幂等初始化脚本） |
| 缓存 L1 | Caffeine | 3.2.x（Boot BOM 管理） | 进程内缓存，自定义 Expiry 逐键 TTL |
| 缓存 L2 | Redis + Lettuce | 服务端 7.x / 客户端 6.6 | 分布式缓存（仅用 GET/SET EX/DEL 基础命令） |
| 指标 | Micrometer | 1.15.x（+ prometheus registry） | 计数器 / Prometheus 端点 |
| 高可用 | Resilience4j | 2.4.0 | Redis 熔断降级装饰器（OPEN 时读直连 DB，快速失败） |
| 认证 | Spring Security + jjwt | 6.5 / 0.13.0 | 双 Token（JWT + Refresh）、BCrypt、jti 黑名单 |
| 测试 | JUnit 5 + Mockito + AssertJ + MockMvc | Boot BOM 管理 | 单元 / 集成 / 端到端冒烟 |
| 测试数据库 | H2 | 2.3.x（test scope） | MySQL 兼容模式内存库 |
| 同步链传输（规划） | mysql-binlog-connector-java（嵌入式） | 最新稳定 | 直连本机 MySQL Binlog，替换进程内事件总线（Canal/RocketMQ 见选型变更记录） |
| 智能体（规划） | Spring AI（OpenAI 兼容接入） | 1.x | 路由 / 推荐 / 排课 / 执行四类智能体 |
| RAG（规划） | 嵌入式向量检索 + Neo4j | Spring AI VectorStore / 5.x | 向量检索（预留 Milvus 扩展点）+ 图检索 |
| 网关（规划） | Spring Cloud Gateway | 2025.0.x | 路由 / JWT 鉴权 / 限流 |
| 前端（规划） | Vue 3 + Vite + Element Plus + ECharts | 3.4+ | 学生端对话助手 + 治理驾驶舱 |

## 🏗 项目结构

```
hercules-platform/               # Maven 多模块父工程
├── hercules-common/             # 公共：统一响应体 / 异常 / JSON 工具 / 实体 / Mapper
├── hercules-cache/              # 缓存治理：多级缓存门面、TTL 策略、命中统计 ★
├── hercules-sync/               # 数据同步：向量时钟、生产/消费、LWW 合并 ★
├── hercules-inspector/          # 巡检自愈（开发中）
├── hercules-rag/                # RAG 检索（规划）
├── hercules-agent/              # 多智能体（规划）
├── hercules-observability/      # 可观测性聚合（规划）
├── hercules-admin/              # 管控台后端（规划）
├── hercules-gateway/            # Spring Cloud Gateway 独立网关（规划）
├── hercules-application/        # ★ 唯一可执行聚合模块（启动类 / REST / 配置）
└── hercules-ui/                 # Vue3 前端（规划）
```

依赖方向：`common ← cache ← sync ← application`，空模块按路线图逐步填充。

## 🚀 快速开始

### 环境要求

| 组件 | 要求 |
| :--- | :--- |
| JDK | 21+ |
| MySQL | 8.x（本机 3306；无需手工建库，连接串自动创建） |
| Redis | 3.x+（本机 6379，无密码） |
| Docker | 仅后续阶段需要，**当前非必需** |

### 一键启动（Windows）

```bat
:: 1) 克隆
git clone https://github.com/ZhanJHE/Hercules.git
cd Hercules

:: 2) 配置本机数据库凭据（已 gitignore）
::    创建 hercules-platform/hercules-application/src/main/resources/application-local.yml
::    模板见《开发文档/环境配置与部署.md》

:: 3) 双击 start.bat（打包 → 启动 → 健康检查 → 自动打开浏览器）
start.bat
```

### 手动命令

```powershell
cd hercules-platform
.\mvnw.cmd test                    # 全量测试（H2 + 内存桩，不依赖本机中间件）
.\mvnw.cmd package -DskipTests     # 打包
java -jar hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar
```

启动成功判据：`GET http://127.0.0.1:8080/actuator/health` 返回 `UP`。

## 🔌 API 概览

| Method | Path | 说明 |
| :--- | :--- | :--- |
| POST | `/api/v1/auth/login` | 登录（用户名密码 → 双 Token） |
| POST | `/api/v1/auth/refresh` | 刷新（旋转式换新 Token 对，白名单） |
| POST | `/api/v1/auth/logout` | 登出（jti 入黑名单 + 吊销 refresh） |
| GET | `/api/v1/courses?page=&size=&keyword=` | 课程分页 / 关键字检索（多级缓存，需登录） |
| GET | `/api/v1/courses/{id}` | 课程详情（纳入向量时钟版本链） |
| POST | `/api/v1/enrollment` | 选课（STUDENT；courseId 传参，studentId 取自 token） |
| DELETE | `/api/v1/enrollment?courseId=` | 退课（STUDENT） |
| GET | `/api/v1/enrollment/mine` | 我的选课记录 |
| GET | `/api/v1/cache/stats` | 命中率 / 各级命中 / 同步与冲突计数（ADMIN） |
| POST | `/api/v1/debug/simulate-conflict` | 模拟双节点并发写冲突（ADMIN） |
| POST | `/api/v1/debug/evict-local?key=` | 手动失效 L1（ADMIN） |
| GET | `/actuator/prometheus` | Micrometer 指标（白名单） |

> 除白名单外所有接口需 `Authorization: Bearer <accessToken>`；演示账号 `admin/admin123`、`st001~003/123456`（由启动器幂等播种）。

## 🕰 缓存一致性同步链（核心机制）

```
写路径  选课事务（防超选条件 UPDATE）
        → AFTER_COMMIT 发布 VersionedValue（时钟 = 存量时钟 ⊕ 本节点自增）
消费端  比对 t_cache_version 存量向量时钟：
        ├─ AFTER      → 写 Redis + 失效 Caffeine + upsert 版本记录
        ├─ EQUAL/BEFORE → 丢弃（幂等）
        └─ CONCURRENT → 字段级 LWW 合并后应用，时钟取并集，冲突计数告警
```

## 🗺 路线图

| 阶段 | 内容 | 状态 |
| :--- | :--- | :--- |
| 阶段 0 | MVP 纵向切片 + 全量 Javadoc + 修复 | ✅ |
| 阶段 A | Maven 多模块化重构（11 模块） | ✅ |
| 阶段 B | 真实 Binlog 同步链（嵌入式 binlog 连接器，免中间件） | ⏳ |
| 阶段 C | 巡检引擎 + 补偿 SQL 自愈 | ⏳ |
| 阶段 D | Spring AI 多智能体（路由/推荐/排课/执行） | ⏳ |
| 阶段 E | RAG（嵌入式向量检索 + Neo4j 图检索） | ⏳ |
| 阶段 F | 可观测性大盘（Prometheus + Zipkin + Grafana，Windows 原生） | ⏳ |
| 阶段 G | Vue3 双端前端（AI 选课助手 + 治理驾驶舱） | ⏳ |
| 阶段 H | Gateway 网关 + JMeter 500 并发压测 + Windows 原生多进程部署 | ⏳ |

## 📚 文档

| 文档 | 说明 |
| :--- | :--- |
| [开发文档/起步文档.md](./开发文档/起步文档.md) | 项目总体规划（需求 / 架构 / 12 周迭代计划） |
| [开发文档/包结构说明.md](./开发文档/包结构说明.md) | 逐类详细注释的包结构 + 7 张类图（`开发文档/images/`） |
| [开发文档/环境配置与部署.md](./开发文档/环境配置与部署.md) | 非 Docker（已验证）/ Docker（规划）双模式部署指南 |
| [开发文档/工作日志.md](./开发文档/工作日志.md) | 开发过程日志与已知事项 |
| [hercules-platform/README.md](./hercules-platform/README.md) | 平台工程说明 |

---

<div align="center">

**Hercules** · 智慧校园多智能体服务平台 · 毕业设计项目

</div>
