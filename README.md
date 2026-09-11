# Hercules —— 智慧校园选课服务平台

面向高校选课场景的一个后端为主的项目。要解决的问题是：课程数据被频繁读取，同时又要被频繁修改，缓存和数据库之间怎么保持一致。

技术栈：Java 21 · Spring Boot 3.5 · MyBatis-Plus · Caffeine + Redis · MySQL 8 · Vue 3

## 现在做到哪了

| 部分 | 状态 |
| :--- | :--- |
| 多级缓存 + 向量时钟冲突消解 | 已完成 |
| Canal 监听 Binlog 同步缓存 | 已完成 |
| 双 Token 认证、独立网关 | 已完成 |
| 多智能体对话（推荐/排课/选课） | 基本完成，推荐理由的非结构化数据依据没做 |
| Vue3 前端（学生端 + 管理端） | 已完成 |
| 容器部署、压测 | 部署已完成；压测数据口径已失效，需要重测 |
| 巡检引擎、RAG、eBPF 采集、分布式追踪、一致性健康度 | 没做 |

逐条需求的完成情况（含每条差在哪）见 [开发文档/路线图.md](./开发文档/路线图.md)。

测试：后端 108 个用例、前端 17 个用例，全部通过（`mvn test`、`npm test`）。

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

## 数据是怎么保持一致的

课程详情这类数据走两级缓存：Caffeine（进程内）→ Redis → MySQL，逐级回填。列表数据不放进这套版本机制，只给 10 秒的过期时间。

数据被修改时（比如选课扣人数），事务提交后会产生一条带版本号的消息，消费者拿它和数据库里存的历史版本比：

- 新的比旧的更新 → 用它刷新缓存；
- 一样或更旧 → 丢掉，不重复处理；
- 两边互有大小（说明是并发写的）→ 按字段比时间戳，谁的时间新用谁的，然后把两边的版本号合并。

发送消息的通道有两种，配置项 `hercules.sync.transport` 切换：`in-process` 是进程内直接发（本机跑和测试用），`canal-mq` 是从 MySQL 的 Binlog 经 Canal 和 RocketMQ 传过来（容器部署用）。后者可以做到完全绕开应用直接改数据库，缓存也会自动更新，实测 250ms。

详细说明见 [开发文档/包结构说明.md](./开发文档/包结构说明.md)。

## 文档怎么看

| 文档 | 里面是什么 | 什么时候看 |
| :--- | :--- | :--- |
| [路线图.md](./开发文档/路线图.md) | 每条需求做了没有，没做的差在哪 | 想知道进度 |
| [包结构说明.md](./开发文档/包结构说明.md) | 每个包和类干什么，缓存键有哪些，页面和路由怎么分 | 要读代码或改代码 |
| [架构图.html](./开发文档/架构图.html) | 分层结构、读路径、同步链、认证、前端路由五张图 | 想快速看懂整体怎么走的 |
| [设计说明.md](./开发文档/设计说明.md) | 认证、日志、前端的设计取舍，以及和原设计不一致的地方 | 改这三块功能 |
| [环境配置与部署.md](./开发文档/环境配置与部署.md) | 本机和容器怎么装、怎么起、出错了怎么办 | 第一次跑起来 |
| [测试报告-2026-09-11.md](./开发文档/测试报告-2026-09-11.md) | 跑了哪些测试、结果是多少 | 想复核数据 |
| [工作日志.md](./开发文档/工作日志.md) | 哪天做了什么、踩了哪些坑 | 回顾过程 |
| [历史基线归档.md](./开发文档/历史基线归档.md) | 开工时写的规划和需求，含风险表和论文结构建议 | 想知道原本打算做什么 |
| [Agent规范.md](./开发文档/Agent规范.md) | 让 Agent 改这个项目时要守的规则 | 让 AI 动代码之前 |

文件名里带日期的那份，日期是最后一次更新的日期。更新测试后要改文件名，并同步《路线图》和本文件里的链接。

## 简历口径

**智慧校园多智能体服务平台**（个人项目）· GitHub：https://github.com/ZhanJHE/hercules-platform

技术栈：Java 21 · Spring Boot 3.5 · MyBatis-Plus · Caffeine + Redis · MySQL 8 · Vue 3 · Docker Compose

做过的事：

- 两级缓存（Caffeine 进程内 + Redis）：同时到达的重复请求合并成一次回源；加分布式锁防止缓存被击穿；Redis 挂掉时自动熔断降级，读请求直接查数据库
- 用向量时钟给缓存变更打版本号：多个节点同时改同一条数据时，能判断谁新谁旧；两边互有大小就按字段比时间戳合并，避免互相覆盖
- 用 Canal 监听 MySQL 的 Binlog 刷新缓存：业务事务只管写库，实测改库后 250ms 内缓存更新；绕开应用直接改数据库也能同步
- JWT 双令牌认证：accessToken 短期有效，refreshToken 用一次换一次；登出后令牌进黑名单；学生只能操作自己的数据
- 多智能体对话：用 Spring AI 接智谱 GLM，四个智能体分别负责判断意图、推荐课程、检查冲突、执行选课；选课要先确认才执行
- Vue3 前端分学生端和管理端，对话用流式返回，边生成边显示
- 自动化测试：后端 108 个、前端 17 个，都不依赖本机中间件和网络

被追问数字时的出处：

| 数字 | 出处 |
| :--- | :--- |
| 缓存一致性：选课后 617ms、直接改库 250ms、Canal 重启后 199ms | 《测试报告-2026-09-11》第八节，九个容器实测 |
| Redis 故障后读路径降级 | 《测试报告-2026-09-11》第三节，故障演练 |
| 连接池从 20 调到 40 的效果 | 《测试报告-2026-09-11》第 6.6 节，A/B 对比 |
| 500 并发压测结果 | 口径已失效，需要重测。旧数据在同一份报告的第六节，但那是 2026-09-07 测的，之后加了选课唯一键、把写路径改成 Canal 异步、加了智能体模块和前端，重跑结果不会和旧数字接近 |

## 已知没做和没做对的

- 巡检引擎没做（`hercules-inspector` 模块是空的）。缓存和数据库真出现不一致时，没有检测手段。
- eBPF 采集没做，实际用的是 Micrometer 手工埋点。这一条在开工文档里标的是 P0。
- 分布式链路追踪没做。只有 traceId 写进日志，能按它串日志，但没有各段耗时。
- 一致性健康度评分没做。
- 性能数据要重测，原因见上。
- 内存里的会话数据在应用重启后会丢；应用是单实例部署，没有多节点验证。

完整清单、原因和建议的下一步见 [开发文档/路线图.md](./开发文档/路线图.md)。
