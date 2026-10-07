# BizLens AI

BizLens AI 是面向企业代码知识库的业务问答系统。系统通过 AI 对话解释代码背后的业务规则和处理流程，并将源码理解能力与源码输出权限分离，避免向客户泄露源代码、SQL、配置和密钥。

## 当前能力

- 用户注册、登录、JWT 会话和 Redis 限流
- AI 会话、历史消息、SSE 对话和 AI 角色管理
- 会话历史可回溯、可续聊：列表返回全部状态（含已关闭），已关闭会话能翻回来看历史，发送消息时自动重新打开继续
- 回答按 Markdown 渲染（自带轻量渲染器，先转义再套标签，不引第三方库）；首字未到时显示「思考中」实时计时，结束后给出「思考 x.xs ·
  本次回答 x.xs」
- 消息与会话都可右键删除：删消息只删单条，删会话连带其全部消息一起删（都同步清理 Redis 热上下文）
- 操作前置校验统一：建会话/建角色时即校验 API Key、项目索引状态，不满足就地提示；未就绪项目不允许建角色
- 角色与代码项目多对多：一个角色可覆盖多个项目（如前后端共用一个角色），一个项目也可建多个角色；
  对话时在角色的**全部关联项目**内一起检索；代码项目页列出每个项目关联的全部角色
- DeepSeek 兼容的模型 Provider（API Key 通过环境变量提供）
- Prompt 注入检测与 AI 输出安全拦截；流式为逐段推送（打字机），命中拦截时用替换事件纠正
- 全局异常兜底，接口异常统一返回 `R` 结构
- PostgreSQL 持久化：用户、AI 会话、代码知识库索引和安全审计
- 代码项目、文档、方法切片和调用关系的数据模型；已支持关键词召回式 Code RAG（无向量），Code Graph 仍待接入
- 基础工作台和 `/api/system/health` 服务探针
- 代码项目元数据管理、归属校验和索引任务排队入口
- 基础 Git 临时工作区索引：浅克隆、文件元数据、切片源码、轻量方法/符号级切片、Commit 和状态回写；项目页展示索引统计
- 对话时按「问题改写 → 关键词召回 → 注入模型」检索项目内代码切片，回答只输出业务解释，源码由安全拦截挡住

原拷贝项目中的论坛、聊天室、私信、板块和帖子功能已移除，前端工作台仅保留系统状态、AI 对话和 AI 角色入口。

## 目录

```text
svc/     Spring Boot 后端
web/     Vue 3 + Vite 前端
doc/     原始 AI 方案
docs/    项目开发文档与开发日志
```

## 环境要求

- JDK 8
- Maven 3.8+
- Node.js 20+
- PostgreSQL 14+
- Redis 6+

## 启动步骤

1. 初始化数据库（全新库）：

   ```bash
   psql -h localhost -U postgres -d bizlens -f svc/src/main/resources/db/schema-postgresql.sql
   ```

   **已经建过库的不要重复执行上面的建库脚本**，按顺序执行增量脚本即可（均可重复执行）：

   ```bash
   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20260914-projectid.sql      # AiAgent.ProjectId
   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20260914-sourcesnippet.sql  # ai_code_chunk.source_snippet
   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20260914-indexerror.sql     # ai_code_project.last_error
   ```

   `migration-*-projectid.sql` 会清理与新模型不兼容的历史角色（旧模型下角色不与项目绑定），**执行前请备份 `"AiAgent"`**。
   `sourcesnippet` 只加列不删数据，但历史切片的该列为空，需要在「代码项目」页对每个项目**重新排队索引**
   一次才会填充——不重新索引会导致对话时召回不到任何内容。

   新出现的列若未同步，接口会直接报 `column ... does not exist`（如「代码项目」页加载时就调用的 `/ai/project/list`）。

2. 设置后端环境变量（不要写入配置文件或 Git）：

   ```text
   DATABASE_URL=jdbc:postgresql://localhost:5432/bizlens
   DATABASE_USERNAME=postgres
   DATABASE_PASSWORD=change-me
   REDIS_HOST=localhost
   REDIS_PORT=6379
   REDIS_PASSWORD=
   DEEPSEEK_API_KEY=your-key
   AES_KEY=at-least-32-byte-secret
   JWT_SECRET=at-least-32-byte-jwt-secret
   ```

   可选（**克隆 Git 仓库时的出口网络**）：索引克隆**不继承运行机器的代理设置**，默认直连。
   如果服务所在机器访问外网 Git（如 github.com）必须经代理，就在这里显式声明：

   ```text
   BIZLENS_GIT_PROXY=http://proxy.corp:8080
   BIZLENS_GIT_NO_PROXY=git.inksdev.com,192.168.99.92,localhost,127.0.0.1
   ```

   两个都可留空。**只设 `BIZLENS_GIT_PROXY` 会把内网 Git 也送去代理**（表现为 502 Bad Gateway），
   所以内网地址一定要写进 `BIZLENS_GIT_NO_PROXY`。

3. 启动后端：

   ```bash
   mvn -pl svc spring-boot:run
   ```

4. 启动前端：

   ```bash
   cd web
   pnpm install --frozen-lockfile
   pnpm run dev
   ```

默认地址：<http://localhost:3000>；后端端口：`10610`。
后端基础探针：`GET http://localhost:10610/api/system/health`。

## 容量与清理

长期运行会持续占空间，这里记下**实测的单位成本**与**已经做的清理**，便于按需估算和排查。

**数据库（实测 2026-10-07）**：库总量 11 MB（其中约 7 MB 是 PostgreSQL 空库基线）；
单个切片平均行 624 字节（源码片段均值 282 字符、上限 1209；业务摘要 83；元数据 125）；
单个文档行约 1.3 KB；表实际占用约为行数据之和的 2.5 倍（索引 + 页填充 + 死元组）。

按"每个文件约 5 KB 逻辑、约 12 KB 落盘"估算：1000 文件的中型仓库约 12 MB，
100 个这样的项目约 1.2 GB。单项目硬顶是 `MAX_FILES=2000` × 每文件最多 201 切片。
设计上只存**截断后的片段**（单片段 1200 字符、单文件累计 20000 字符），不存整份源码，
所以增长是线性、可预算的；删除项目会级联清掉文档与切片。

**已做的两项清理**（都是防"只增不减"）：

1. **索引临时工作区**：克隆下来的代码放在系统临时目录（`bizlens-index-*`），用完即删；
   另外**启动时和每次索引前**会扫一遍临时目录，清掉超过 1 小时仍未清理的残留
   （跳过本进程正在用的）。
   > 这一步不只是"保险"：git 在 Windows 上会把 `.git/objects/pack` 下的 `.pack/.idx/.rev`
   > 设成**只读**，而 `Files.deleteIfExists` 遇到只读文件直接抛 `AccessDeniedException`，
   > 导致**清理从来没成功过**——实测累积 25 个目录、309 MB，最早的是两周前。
   > 现在删之前会先清只读属性。
2. **Redis 热上下文**：每个会话缓存的最近 50 条消息，TTL 7 天，每次写入续期；
   删消息/删会话会立即清掉对应 key。

**扩容时注意**：临时工作区清理依赖进程内的"正在使用"标记，多实例共享同一个临时目录时，
理论上可能把另一个实例超过 1 小时的在跑工作区清掉（正常浅克隆远用不到 1 小时）。
多实例部署建议各自给独立的 `java.io.tmpdir`。

## 开发进度

### 已完成

- [x] 清理论坛项目拷贝遗留代码、页面、Mock 和无关静态资源
- [x] 建立 Spring Boot 后端基础结构和 Vue 3 前端基础结构
- [x] 完成用户注册、登录、JWT 和 Redis 会话基础能力
- [x] 完成 AI 会话、历史消息、SSE 输出和 AI 角色管理
- [x] 增加系统工作台和后端健康检查接口
- [x] 完成代码项目基础 CRUD、用户归属校验和索引排队状态
- [x] 完成基础 Git 临时工作区索引和源码目录清理
- [x] 完成 Java/Kotlin 方法、Mapper XML 操作、Vue/JS/TS 函数的轻量符号提取
- [x] 支持代码项目索引文件数、切片数和符号数统计展示
- [x] 切换 PostgreSQL，并提供可重复执行的建表脚本
- [x] 增加 Prompt 注入检测和模型输出安全拦截
- [x] 建立开发文档和开发日志
- [x] 统一项目命名：BizLens AI、Maven 坐标、前端包名和 Spring Boot 启动类
- [x] 通过后端 Maven 编译和前端 Vite 生产构建
- [x] 角色与代码项目**多对多**：一个角色可覆盖多个项目（如前后端共用一个角色），一个项目也可建多个角色；关联项目必须全部完成索引才能对话
- [x] 索引切片落源码片段，对话前用模型把问题改写成候选标识符，再按关键词召回并注入
- [x] 建会话时即校验角色可用（API Key + 项目已索引），不再等到发消息才失败；未就绪项目不允许建角色
- [x] 会话历史可回溯可续聊：列表返回全部状态，已关闭会话可查看历史并在发送时自动重新打开
- [x] 消息落库时回写会话更新时间（会话列表按它倒序，此前不回写导致排序与时间失真）
- [x] Git 凭据填写后仍失败时给出明确原因；角色接口全部出口脱敏 API Key
- [x] 修正「Git 凭据」列的误导文案：不再暗示"没填就等于没凭据访问"
- [x] 对话页：默认筛选「进行中」、Markdown 渲染（含列表续行处理）、思考中与耗时提示、消息与会话右键删除
- [x] 放开「一个项目一个角色」的限制（含 `migration-20261007-multiagent.sql` 删唯一索引），代码项目页展示全部角色
- [x] 统一「代码项目」与「AI 角色」两页布局；角色弹窗垂直居中；左侧菜单改为 工作台 / 业务问答 / AI 角色 / 代码项目
- [x] **切断索引克隆对运行机器凭据的继承**（不再借用服务账号身份读私有仓库）
- [x] **按项目配置 Git 访问令牌**（AES 加密落库、经 credential.helper 注入）：GitHub / Gitee / GitLab / 自建 GitLab
  的私有仓库可正常索引，机器凭据依旧不参与
- [x] 流式接口鉴权失败返回 SSE error 事件（此前是空 body 的 HTTP 500）
- [x] SSE 打字机效果：逐段推送 `delta` 事件；安全拦截命中时用 `reset` 事件整段替换为拒答
- [x] 新增全局异常处理器兜底，接口异常统一返回 `R`，不再出现空 body 的 HTTP 500
- [x] 索引任务并发防护 + 卡死恢复：状态新近才拒绝重排，超时视为任务已死并放行
- [x] 索引心跳与中断收敛：运行期每 30 秒推一次心跳，服务重启时把中断的索引自动置为失败（不再永远停在"索引中"）
- [x] 索引进度可见：列表显示「已用多久 / 预计还需多久」，完成显示完成时间，中断显示中断时间并可一键重排
- [x] 代码项目页支持「一键索引排队」：把全部项目重新排队，正在索引的自动跳过
- [x] 补测试文档 `docs/测试文档.md`，覆盖本轮全部改动与发现的问题

当前没有可用的自动化测试，主流程也未完整跑通；具体缺口见下方「待测试 / 未验证」。

### 待完成

**功能性缺口**（有入口、但后端能力不完整）

1. **Git 凭据**（最高优先级项，已完成主体）。

   **已做一（2026-10-07）：切断对运行机器凭据的继承。**
   原先 `runGitClone` 虽然没传任何鉴权参数，但 `git` 会自己去找运行机器的凭据
   （`credential.helper`、`~/.git-credentials`、SSH key、系统凭据管理器）——
   实测一个**私有**仓库在项目未配任何凭据时照样被克隆成功（用的就是本机
   `~/.git-credentials` 里那条 github.com 凭据），等于**任何注册用户都能借服务账号身份读私有代码**。
   现在克隆前会给 git 一套干净环境（`AiCodeIndexServiceImpl.applyIsolatedGitEnv`）：
   `HOME`/`USERPROFILE`/`XDG_CONFIG_HOME` 指向空临时目录、`GIT_CONFIG_GLOBAL` 指向不存在的文件、
   `GIT_CONFIG_NOSYSTEM=1`、`GIT_CONFIG_COUNT` 显式把 `credential.helper` 置空、
   `GIT_TERMINAL_PROMPT=0`、并移除 `SSH_AUTH_SOCK` / `GIT_SSH_COMMAND` / `GIT_ASKPASS`。

   **已做二（2026-10-07）：按项目注入访问令牌。**
   `credential_enc`（AES 加密）+ `credential_user` 两列存项目自己的令牌，
   克隆时在隔离环境里追加一条 `credential.helper`，从环境变量 `BIZLENS_GIT_TOKEN` 取令牌
   ——**刻意不写进命令行**，否则令牌会出现在进程列表里被同机其他用户看到。
   用户名优先用配置值，留空按主机名取默认（GitHub → `x-access-token`，其余 → `oauth2`；
   Gitee 必须填账号名）。公开仓库令牌留空即走匿名。
   实测：同一台机器上不可匿名访问的私有仓库 `404-forum`
   （匿名 `api.github.com` 返回 404）配令牌后索引到 `READY`（239 文件 / 1410 切片）。

   **未做：SSH 地址。** 隔离环境不继承 `~/.ssh`（密钥、known_hosts），也不支持部署密钥，
   所以 `ssh://` 与 `git@host:path` 形式的地址当前必然失败，失败原因里会直接说明。
   另外仍未做超时取消与任务重试。

   > 影响：项目「444」指向私有仓库，当前状态为失败；**在项目里填入访问令牌后重新排队即可恢复**
   > （已索引的切片仍在库里，所以现有对话本身不受影响）。
2. **流式对话的"事前拦截"能力受限**。打字机效果意味着内容已经开始外送，`AiSafetyGuard` 只能做到
   "判到就断"——命中所在的这一段增量不会推送出去，已推送的部分再用 `reset` 事件整段替换为拒答。
   如果业务上要求"零泄露"（一个字符都不能先到客户端），应改回"整段生成完再一次性发送"。
3. 将轻量符号提取升级为 Java AST、Mapper XML、Vue 文件的完整结构化解析。
4. 增加方法级业务摘要生成、源码安全扫描和按 Git Commit 的增量索引。
5. 将 embedding 接入 pgvector 或独立向量数据库，把当前的关键词召回升级为语义召回。
6. 实现 Controller/Service/Mapper/SQL/Table 的 Code Graph 检索。
   **优先做其中的「一跳继承扩展」**（2026-10-07 实测暴露，改动可控、针对性强）：
   命中某个类的文件切片时，顺着类声明里的 `extends X` 把同项目 `X.java` 的方法切片
   一并带进召回。不做的话"子类调用、父类实现"的接口会**时对时错**——
   问「`/system/SYSM03B4/getEntity` 的接口权限」，`@PreAuthorize` 在父类 `PipermissionController`
   上，能否答对取决于父类切片有没有撞进 6 个召回名额（实测一次答对、一次答错）。
   实现要点：`AiCodeChunkHit` 需要带上 `documentId` / `projectId`（现在没有），
   再按 `项目 + 文件名` 找到父类文档、取其符号名命中检索词的方法切片；比如代码关联检索 / 二次召回机制。比如:关键词检索-> 发现
   extends PipermissionController->自动检索。
   PipermissionController ->发现 getEntity->继续获取父类方法和注解->交给 AI
   只做一跳、不递归，并限制条数避免挤掉原有命中。详见 `docs/测试文档.md` 5.13。
7. 持久化 `ai_security_audit` 审计记录并增加权限分级、告警和运营监控。
8. 列表接口分页。`pagehelper` 因与 mybatis-plus 的版本冲突已移除，`/ai/conversations`、
   `/ai/project/list`、`/ai/agent/list` 目前都是全量返回，数据量上来后需要补分页。
9. 文件级完整检索，1、用户要求“某个文件全部接口/方法”时，不使用有限 chunk 召回2、直接获取目标文件完整内容或完整方法摘要
   支持： “列出 PipermissionController 全部接口”  “列出这个 Controller 所有方法” “这个文件有哪些接口” 防止因 chunk 未全部召回导致遗漏接口
10. 方法调用链检索：Controller → Service → Mapper → SQL、支持继续向下追踪

### 已知限制与待优化

1. **关键词召回依赖模型改写**。中文问题会被转成候选标识符再去 ILIKE 匹配，业务术语只出现在代码
   注释或字符串字面量里时召回不全（例如 `// 订单超过 30 分钟未支付自动关单`）。根治要等向量化。
2. **每条消息多一次模型调用**（查询改写），延迟增加约 1~2 秒、token 成本相应上升；
   且改写结果没有缓存，同一问题重复提问会重复改写。
3. **召回查询是全表 ILIKE**，`ai_code_chunk` 上没有索引。数据量小时无感，切片数上万后需要
   引入 `pg_trgm` 索引或走向量化。
4. **索引静默截断**：`MAX_FILES = 2000`、`MAX_FILE_BYTES = 2MB`，超出的文件直接跳过，
   且完成日志只记录已索引数量（`files=...`），不体现被跳过的量。大仓库会出现"索引成功但内容不全"
   而没有任何提示。
5. **索引没有进度上报**。`PENDING → QUEUED → INDEXING → READY/FAILED` 之间没有中间态，
   大仓库索引期间前端只能看到"索引中"三个字，不知道跑到哪一步。
6. **`DeepSeekProvider.resolveApiKey` 仍保留系统 Key 兜底**（`request.getApiKey()` 为空时回退
   `DEEPSEEK_API_KEY`）。服务层已强制角色必须配 Key，这层兜底当前不可达，但新增调用方时会静默生效。
7. **`AiProvider.listModels` 的 default 实现返回空列表**，各 Provider 需要自己实现 OpenAI 兼容的
   `/models` 调用。已在 default 里加 WARN 日志避免静默降级，但新增 Provider 时容易漏。
8. 前端对话页不展示会话所属项目；工作台是静态卡片，不支持多项目视角。
9. `svc/src/main/resources/mapper/UserMapper.xml.xml` 是双 `.xml` 后缀（拷贝项目遗留），
   能正常加载但命名不规范。
10. **索引没有进度百分比**。能显示"已用多久/预计还需多久"（预计基于上一次成功索引的耗时），
    但拿不到"解析到第几个文件"这种真实进度，大仓库仍只能盲等。
11. **索引中断的判定依赖心跳**（30 秒一次，超过 180 秒无心跳即视为进程已死）。
    若数据库与索引进程之间的写入被长时间阻塞，可能把还活着的任务误判为中断。
12. 早期会话（`AgentId` 为空的那些）只能查看历史，无法继续对话——界面已明确提示并禁用输入框。
13. 列表接口未分页：会话积累多了之后左栏会越来越长（右键可删除单个会话）。
14. **不建模继承关系，跨类的答案会不稳定**。实测问「`/system/SYSM03B4/getEntity` 的接口权限」：
    子类只告知"继承自 `PipermissionController`"、`@PreAuthorize` 注解在父类的方法上，
    而召回只有 6 个名额，**同一次提问会时对时错**——父类切片撞进名额就答对，否则答错。
    修法见「待完成」第 6 条的**一跳继承扩展**（尚未实现）。已修的相关缺陷
    （切片丢注解、文件头过短、按长度升序排序、换行口径不一致导致的空片段）
    见 `docs/测试文档.md` 第 5.13 节。

### 待测试 / 未验证

**未在本机跑通的**

1. **三个迁移脚本均未在本机执行**。本机无 `psql`（`Program Files` 下也没有），
   所有 SQL 只做了括号平衡、外键引用顺序、语句数等静态校验，没有真正打过数据库。
2. **端到端主流程未走通**：注册 → 建代码项目 → 排队索引 → 建 AI 角色 → 对话。
   各环节分别验证过编译或单点行为，但没有一次完整的串联验证。
3. **召回 SQL 未在真实库执行过**。`AiCodeChunkMapper.searchByKeywords` 的 `<foreach>` 展开、
   `metadata->>'startLine'` 取值、`ILIKE` 命中权重都只经过人工审查，没有跑过一次真实查询。
4. **重新索引后 `source_snippet` 是否正常写入未验证**——这是召回能否工作的前提。
5. **SSE 多行修复未运行时验证**。上次启动验证时 10610 被占用，未完成启动。
6. **索引失败原因落库与前端展示未验证**。

**已静态验证、但未运行时验证的**

7. 无向量召回的实际命中率没有评测，"问题改写 → 关键词匹配"这条路径对中文业务问题的效果未知。
8. **源码注入上下文后 `AiSafetyGuard` 的拦截效果未验证**：源码现在会进 prompt，
   模型是否真按约束用业务语言转述而不复述源码，需要构造用例实测。
9. `/ai/agent/models` 只实测过 `deepseek` 分支（真实返回 `deepseek-flash`、`deepseek-v4-pro`）；
   `openai` / `qwen` / `custom` 分支未验证，其中 qwen 的预置列表还是旧值，需以真实接口为准。

**工程基础**

10. 前端无任何测试文件，`pnpm run test` 会因 Vitest/Vite 包导出兼容问题在启动阶段失败；
    后端同样没有单元测试与接口测试。后续建 CI 时统一处理。

每完成一项，请同步更新本节勾选状态，并在 [开发日志](docs/开发日志.md) 中记录日期、变更和验证结果。

## 文档

- [开发文档](docs/开发文档.md)
- [开发日志](docs/开发日志.md)
- [测试文档](docs/测试文档.md)
- [AI 代码知识库与业务问答系统设计方案](doc/AI%20代码知识库与业务问答系统设计方案.md)
