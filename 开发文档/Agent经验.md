# Agent 经验文档

这份文件记录 Agent 在本环境/本项目工作中会反复碰到的问题、原因和避免方法。给所有在本仓库工作的 Agent 看：遇到问题先查这里，别重新踩一遍。

它不是规范。规范是根目录的 [Agent.md](../Agent.md)，这里只记"怎么少犯错"。每条经验都来自实际发生过的案例，出处见 [工作日志.md](工作日志.md) 和对应文档。

最后更新：2026-09-11

---

## 一、PowerShell 与脚本执行

1. **编码集问题（GBK/UTF-8）是最常见的坑**。
   - Windows 控制台和 cmd 默认按 GBK 解析字节。`.bat` 里内嵌中文 + UTF-8 保存会被 cmd 按字节错位解析，rem/echo 行被截断成碎片命令。
   - 做法：批处理壳保持纯 ASCII（逻辑放 .ps1），或文件用 UTF-8 BOM 保存；写文件时显式指定编码。
   - 往容器/数据库传中文同理：`docker exec` 经 PowerShell 传中文 SQL 会被按 GBK 写入而乱码。做法：中文 SQL 写成 SQL 文件，用 `--default-character-set=utf8mb4` 执行。
2. **PowerShell 执行策略会禁止运行 .ps1 脚本**（默认 Restricted，报"禁止运行脚本"）。
   - 做法：`powershell -ExecutionPolicy Bypass -File xxx.ps1`，或 `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`。本项目根目录 `start.bat` 调 `start-hercules.ps1`，遇到该报错按此处理。
3. **行尾符**：write/编辑工具产出 LF 行尾，批处理必须 CRLF，否则行为异常。写入 .bat 后显式转成 CRLF。
4. **PowerShell 进程无状态**：每次调用是新进程，cwd、变量、函数都不保留。用 `workdir` 参数指定目录，不要依赖上一次的 `cd`。
5. **后台进程会被前台命令回收**：用 `Start-Process` 起的应用进程会随启动它的命令结束被回收（健康检查曾出现"短暂 UP 后连接被拒"）。做法：用后台任务（job）常驻，验证完再停。
6. **输出重定向与管道**：在受限沙箱里，捕获子进程输出的管道会失败（`CreatePipe error=5`、`拒绝访问`），表现为 `git log | Select-String` 这类命令直接报错。做法：不要管道拼接 git 等外部程序输出；直接运行命令读原始输出，或先重定向到文件再读。这不是命令写错，是环境边界。

## 二、沙箱与进程权限

7. **受限沙箱下 surefire 分叉 JVM 必失败**（`CreatePipe error=5`），Mockito 内联 mock maker 自附加也会失败。
   - 做法：`$env:MAVEN_OPTS="-Djdk.attach.allowAttachSelf=true"`，再 `mvn -B -f pom.xml test -DforkCount=0`，可在单个 JVM 里跑通全量。
8. **前端测试的 esbuild 需要起子进程**，受限沙箱下报 `spawn EPERM`。放宽进程权限后才能跑 `npm test`。
9. **git-filter-repo 底层要开子进程管道**，受限沙箱下必然 `PermissionError: [WinError 5]`。重写 git 历史前先确认权限足够。
10. **Maven wrapper 首次运行要写 `~/.m2/wrapper/dists`**，工作区写权限不够会失败；且 `mvnw.cmd` 依赖 PATH 里有 Windows PowerShell，没有就改用 `mvn -f pom.xml`。

## 三、Git 与版本控制

11. **gitignore 对已跟踪文件无效**：文件先被 `git add` 过，之后在 .gitignore 里写它也不起作用，`git add .` 仍会带上。做法：先 `git rm --cached <文件>` 停止跟踪，gitignore 才生效。
12. **gitignore 模式含中间分隔符时锚定在 .gitignore 所在目录**：`deploy/.env` 匹配不到 `hercules-platform/deploy/.env`。要匹配任意层级用 `**/deploy/.env`。写完用 `git check-ignore -v <路径>` 验证。
13. **敏感信息一旦进 git 历史，删文件不算清除**：API-Key、密钥仍留在历史提交里，公开仓库等于泄露。彻底清除要重写历史（`git filter-repo --path <路径> --invert-paths --replace-text <替换表> --force`）。
    - 注意：filter-repo 会移除 origin remote，重写后要重新 `git remote add origin <地址>`；所有提交哈希都会变，需要强推，所有克隆要 `git fetch --force`。
    - 更省事的做法是源头避免：密钥一律走忽略文件（`deploy/.env`、`application-local.yml`），提交前看 `git status` 确认没有它们。
14. **提交规则**：提交前跑全量测试（后端 + 前端）；提交信息格式 `类型: 主题——要点`（feat/fix/docs/chore/refactor）；不提交 target、node_modules、dist、loadtest 运行时产物、logs。
15. **提交时出现 `LF will be replaced by CRLF` 警告是正常的**（Windows autocrlf 行为），不是错误，不需要处理。

## 四、构建与测试

16. **MyBatis-Plus 3.5.9+ 把分页插件拆到独立模块**：升级后编译报找不到分页插件 → 补 `mybatis-plus-jsqlparser` 依赖。
17. **Spring Boot 3 + SSE 异步 × Security**：ASYNC dispatch 会重跑过滤器链而自定义 JWT 过滤器（OncePerRequest）被跳过，出现匿名拒绝叠加已提交响应。做法：`spring.security.filter.dispatcher-types=request,error`。
18. **Mockito `verify(mock)` 默认要求恰好一次**：方法被调用两次会报 `TooManyActualInvocations`，这不是实现 bug 是断言写法问题。确认"至少调用过"用 `verify(mock, atLeastOnce())`。
19. **Hikari 连接池太小会饱和**：20 连接在 150 写并发下出现 waiting=116、连接超时。调大后 A/B 验证（20→40：写吞吐 +86%、连接超时清零）。改连接池后要重新压测确认。
20. **压测残留数据会拖垮真实接口**：曾出现 51 万行选课记录导致对话接口挂 45 秒以上。压测数据要有门禁（唯一键、清理脚本、按有效记录校准计数），不要只靠删除。

## 五、数据库与 SQL

21. **MySQL 8 默认 `caching_sha2_password`，canal 1.1.7 不支持**：认证报 1045。做法：canal 账号改 `mysql_native_password`。
22. **`schema.sql` 用 `CREATE TABLE IF NOT EXISTS`，对存量库不生效**：加了唯一键或列后，已有数据库不会变。做法：表结构改动必须在《环境配置与部署.md》的"存量库升级"里补可直接执行的 SQL。
23. **传中文给 MySQL 乱码**：见第 1 条，用 SQL 文件 + `--default-character-set=utf8mb4`，不要经命令行内联传中文。

## 六、容器与部署

24. **Docker Hub 直连被阻断**（`dial tcp registry-1.docker.io` 超时）：停下报告，不要反复试。做法：配置镜像加速前缀（本项目用 `DOCKER_MIRROR` 环境变量，可换 daocloud 等源）。
25. **容器内 `npm ci` 在网络受限时超时**（实测 600 秒以上）：做法：宿主机先 `npm run build`，容器只放静态文件（nginx 单阶段镜像）。
26. **canal 投递 RocketMQ 报 NPE**：`canal.mq.partitionsNum` 缺省为 null 时在 `partitionHash` 分支拆箱出错。做法：显式配置 `canal.mq.partitionsNum`（与 topic 队列数一致）。
27. **容器内 JVM 默认自适应参数不适用**（Xms250M/Xmx4G 起步过小、上限过大）：做法：显式设 `-Xms/-Xmx` 等，经 `JAVA_TOOL_OPTIONS` 注入。
28. **演练残留的 java 进程会锁住 jar**，重打包报错：做法：打包前结束残留进程。
29. **容器编排里的健康检查顺序**：依赖服务要等上游 `service_healthy` 再启动，就绪判据用应用健康端点，别用进程存活判断。

## 七、智能体与对话

30. **GLM 是思考型模型，token 预算与超时要留足**：thinking 吃满 max_tokens 会正文为空（1024→4096）；首包 18~32 秒，超时 30 秒会误杀正常生成（放宽到 90 秒）。编排器要能处理空响应（自动补发结构化候选列表）。
31. **先修课比对口径**：`prerequisites_json` 存的是课程主键 ID 列表，不是课程编码。按编码比对永远不满足，要按 ID 比对（单测同步修正）。
32. **会话必须绑定用户**：会话号首次使用时和当前用户绑定，否则他人猜到会话号可越权读历史、替人确认选课（返回 403）。改动涉及会话的接口都要做归属校验。
33. **大模型不可用时不能断链**：路由/推荐降级为纯规则 + 结构化候选列表，实测 1 秒内返回降级结果。

## 八、文档

34. **文档与代码不一致比没有文档更糟**：改功能必须在同一个提交里更新《路线图》；发现不一致直接指出，以代码为准。
35. **带日期的文档**（如测试报告）：文件名里的日期是最后更新日期，改了内容要同步改名，并同步所有指向它的链接。
36. **新增文档要进索引**：README 的文档索引表、《项目讲解.html》左侧页栏、路线图的"相关文件"表要同步，否则别人找不到。
