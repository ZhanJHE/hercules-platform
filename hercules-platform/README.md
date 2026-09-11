# hercules-platform

这是工程本体（Maven 多模块）。项目说明、怎么跑、接口清单、当前进度都在仓库根目录的 [README.md](../README.md)，这里只放工程相关的信息。

## 模块

| 模块 | 状态 | 里面是什么 |
| :--- | :--- | :--- |
| hercules-common | 已完成 | 实体、异常、JSON 工具、缓存键约定、Mapper |
| hercules-cache | 已完成 | 多级缓存（Caffeine → Redis → 数据库）、过期时间策略、熔断、防击穿、命中统计 |
| hercules-sync | 已完成 | 向量时钟、版本消息的生产和消费、字段级合并、Binlog 消费 |
| hercules-agent | 已完成 | 四个智能体（路由、推荐、排课、执行）和编排器 |
| hercules-gateway | 已完成 | 独立进程的网关，端口 8081 |
| hercules-application | 已完成 | 唯一可执行的模块，接口、安全、SSE、种子数据 |
| hercules-inspector | 空模块 | 巡检自愈，没有代码 |
| hercules-rag | 空模块 | RAG 检索，没有代码 |
| hercules-observability | 空模块 | 可观测性，没有代码 |
| hercules-admin | 空模块 | 管控台后端，没有代码 |
| hercules-ui | 已完成 | Vue3 前端，npm 工程 |
| loadtest | 已完成 | JMeter 压测 |
| deploy | 已完成 | 十个容器的编排与配置 |

依赖方向：common ← cache ← sync ← agent ← application。gateway 是独立进程，和前端一样只通过 HTTP 交互，不参与 Maven 依赖。

## 构建

```powershell
cd hercules-platform
.\mvnw.cmd test                    # 全模块 113 个测试，用 H2 内存库，不依赖本机 MySQL/Redis
.\mvnw.cmd package -DskipTests
java -jar hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar
```

数据库账号配在 `hercules-application/src/main/resources/application-local.yml`（已 gitignore，不会提交）。

`mvnw.cmd` 需要 PATH 里有 Windows PowerShell。没有的话用 `mvn -f pom.xml test`。

单模块时期的历史见 git 提交 `bfb4b6e`，当时的 `hercules/` 目录已经删掉。

## 其他文档

- 每个包和类干什么：[开发文档/包结构说明.md](../开发文档/包结构说明.md)
- 进度和没做的事：[开发文档/路线图.md](../开发文档/路线图.md)
- 本机和容器怎么部署：[开发文档/环境配置与部署.md](../开发文档/环境配置与部署.md)
