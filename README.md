# Hercules —— 智慧校园选课服务平台

面向高校选课场景的一个后端为主的练手项目。要解决的问题是：课程数据被频繁读取，同时又要被频繁修改，缓存和数据库之间怎么保持一致。

技术栈：Java 21 · Spring Boot 3.5 · MyBatis-Plus · Caffeine + Redis · MySQL 8 · Vue 3

---

## 现在做到哪了

| 部分 | 状态 |
| :--- | :--- |
| 多级缓存 + 向量时钟冲突消解 | 已完成 |
| Canal 监听 Binlog 同步缓存 | 已完成 |
| 双 Token 认证、独立网关 | 已完成 |
| 多智能体对话（推荐/排课/选课） | 基本完成，推荐理由的非结构化数据依据没做 |
| Vue3 前端（学生端 + 管理端） | 已完成 |
| 容器部署、压测 | 部署已完成；压测数据口径已失效，需要重测 |
| 巡检引擎、RAG、可观测性补齐 | 没做 |

逐条需求的完成情况见 [开发文档/路线图.md](./开发文档/路线图.md)。

测试：后端 108 个用例、前端 17 个用例，全部通过（`mvn test`、`npm test`）。

---

## 怎么跑起来

### 本机直跑（最少依赖）

需要 JDK 21+、MySQL 8、Redis。

```powershell
cd hercules-platform

# 1) 先配本机数据库账号
#    新建 hercules-platform/hercules-application/src/main/resources/application-local.yml
#    内容模板见《开发文档/环境配置与部署.md》§1.3

# 2) 跑测试（用 H2 内存库，不依赖本机 MySQL 和 Redis）
.\mvnw.cmd test

# 3) 打包并启动
.\mvnw.cmd package -DskipTests
java -jar hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar
```

启动成功的标志：`GET http://127.0.0.1:8080/actuator/health` 返回 `UP`。

也可以直接双击根目录的 `start.bat`：它会打包、启动、检查健康状态，然后打开浏览器。

说明：`mvnw.cmd` 需要 PATH 里有 Windows PowerShell（Maven Wrapper 自带的行为）。如果没有，改用 `mvn -f pom.xml test`。

### 容器（完整形态，十个容器）

需要 Docker Desktop。会起 MySQL、Redis、应用、网关、前端、Prometheus、Grafana、RocketMQ、Canal。

```powershell
cd hercules-platform/deploy
docker compose up -d --build
```

浏览器入口 `http://localhost:8090`。详见《开发文档/环境配置与部署.md》第二节。

演示账号：`admin/admin123`、`st001`~`st003/123456`（启动时自动创建）。

---

## 接口

除白名单外，所有接口都要带 `Authorization: Bearer <accessToken>`。

| 方法 | 路径 | 说明 | 权限 |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/auth/login` | 登录，返回两个令牌 | 公开 |
| POST | `/api/v1/auth/refresh` | 刷新令牌（换新的，旧的作废） | 公开 |
| POST | `/api/v1/auth/logout` | 登出 | 登录即可 |
| GET | `/api/v1/courses?page=&size=&keyword=` | 课程列表和搜索 | 登录即可 |
| GET | `/api/v1/courses/{id}` | 课程详情 | 登录即可 |
| POST | `/api/v1/enrollment` | 选课 | 学生 |
| DELETE | `/api/v1/enrollment?courseId=` | 退课 | 学生 |
| GET | `/api/v1/enrollment/mine` | 我的选课 | 学生 |
| POST | `/api/v1/chat` | 对话（流式返回） | 登录即可 |
| GET | `/api/v1/chat/history/{sessionId}` | 会话历史 | 仅会话本人 |
| GET | `/api/v1/cache/stats` | 缓存命中率和同步计数 | 管理员 |
| POST | `/api/v1/debug/simulate-conflict` | 模拟两个节点同时写同一条数据 | 管理员 |
| POST | `/api/v1/debug/evict-local?key=` | 清掉本机一级缓存，用来演示二级缓存回填 | 管理员 |
| GET | `/actuator/prometheus` | 监控指标 | 公开 |

---

## 数据是怎么保持一致的

课程详情这类数据走两级缓存：Caffeine（进程内）→ Redis → MySQL，逐级回填。列表数据不放进这套版本机制，只给 10 秒的过期时间。

数据被修改时（比如选课扣人数），事务提交后会产生一条带版本号的消息，消费者拿它和数据库里存的历史版本比：

- 新的比旧的更新 → 用它刷新缓存；
- 一样或更旧 → 丢掉，不重复处理；
- 两边互有大小（说明是并发写的）→ 按字段比时间戳，谁的时间新用谁的，然后把两边的版本号合并。

发送消息的通道有两种，配置项 `hercules.sync.transport` 切换：`in-process` 是进程内直接发（本机跑和测试用），`canal-mq` 是从 MySQL 的 Binlog 经 Canal 和 RocketMQ 传过来（容器部署用）。后者可以做到完全绕开应用直接改数据库，缓存也会自动更新，实测 250ms。

详细说明见 [开发文档/包结构说明.md](./开发文档/包结构说明.md)。

---

## 文档怎么看

| 文档 | 里面是什么 | 什么时候看 |
| :--- | :--- | :--- |
| [路线图.md](./开发文档/路线图.md) | 每条需求做了没有，没做的差在哪 | 想知道进度 |
| [起步文档.md](./开发文档/起步文档.md) | 开工时写的需求和设计，作为基线不再改动 | 想知道原本打算做什么 |
| [包结构说明.md](./开发文档/包结构说明.md) | 每个包和类干什么，缓存键有哪些 | 要读代码或改代码 |
| [设计类.md](./开发文档/设计类.md) | 开工时画的类图（含未实现的模块） | 想看设计思路 |
| [认证设计.md](./开发文档/认证设计.md) | 两套令牌怎么分工 | 改登录相关功能 |
| [日志设计.md](./开发文档/日志设计.md) | 日志怎么分文件、traceId 怎么传 | 查线上问题 |
| [前端设计.md](./开发文档/前端设计.md) | 页面、路由、状态管理怎么分 | 改前端 |
| [环境配置与部署.md](./开发文档/环境配置与部署.md) | 本机和容器怎么装、怎么起、出错了怎么办 | 第一次跑起来 |
| [测试报告.md](./开发文档/测试报告.md) | 跑了哪些测试、结果是多少 | 想复核数据 |
| [工作日志.md](./开发文档/工作日志.md) | 哪天做了什么、踩了哪些坑 | 回顾过程 |
| [项目介绍.md](./开发文档/项目介绍.md) | 简历用的一段话 | 写简历 |

已知的重复：架构和模块清单在《起步文档》《包结构说明》《设计类》里各有一份，改架构时要一起改。

## 已知没做和没做对的

- **巡检引擎没做**（`hercules-inspector` 模块是空的）。导致缓存和数据库真出现不一致时没有检测手段。
- **eBPF 采集没做**，实际用的是 Micrometer 手工埋点。这一条在起步文档里标的是 P0。
- **分布式链路追踪没做**。只有 traceId 写进日志，能按它串日志，但没有各段耗时。
- **一致性健康度评分没做**。
- **性能数据要重测**。现有的压测结果测于 2026-09-07，之后加了选课唯一键、把写路径改成异步、加了智能体和前端，口径变了。
- 内存里的会话数据在应用重启后会丢；应用本身是单实例部署，没有多节点验证。

完整清单和原因见 [开发文档/路线图.md](./开发文档/路线图.md)。
