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
- Git Markdown 个人知识库：支持独立索引、标题层级切片、增量更新、敏感信息脱敏和索引进度展示
- 代码项目、文档、方法切片和调用关系的数据模型；已支持关键词 + 可选 pgvector 语义召回的 Code RAG、文件级完整检索、调用链追踪和一跳继承扩展
- AI 角色可选择代码项目、个人知识库或两类来源；对话统一召回并按来源分区注入上下文
- 多项目工作台和 `/api/system/health` 服务探针：支持按项目查看索引、角色与问答入口
- 代码项目元数据管理、归属校验和索引任务排队入口
- 基础 Git 临时工作区索引：浅克隆、文件元数据、切片源码、结构化符号切片、可选 embedding、Commit 和状态回写；项目页展示索引统计
- 对话时按「问题改写 / 问题向量化 → 混合召回 → 注入模型」检索项目内代码切片，回答只输出业务解释，源码由安全拦截挡住

原拷贝项目中的论坛、聊天室、私信、板块和帖子功能已移除，前端工作台聚焦系统状态、多项目索引视角、AI 对话和 AI 角色入口。

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

   可选增强（数据库允许安装扩展时执行；失败会输出 NOTICE，不影响关键词召回）：

   ```bash
   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261008-trgm-indexes.sql   # ILIKE 检索加速
   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261008-pgvector.sql       # pgvector 语义召回
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
   AES_KEY=at-least-32-byte-secret
   JWT_SECRET=at-least-32-byte-jwt-secret
   AI_EMBEDDING_ENABLED=false
   AI_EMBEDDING_BASE_URL=
   AI_EMBEDDING_API_KEY=
   AI_EMBEDDING_MODEL=text-embedding-3-small
   AI_EMBEDDING_DIMENSION=1536
   AI_INDEX_MAX_FILES=2000
   AI_INDEX_MAX_FILE_BYTES=2097152
   ```

   启用语义召回时需要先执行 `migration-20261008-pgvector.sql`，并保证 `AI_EMBEDDING_DIMENSION` 与脚本中的 `VECTOR(1536)` 一致。
   如果数据库不允许安装 `pgvector` 扩展，脚本会跳过向量列与索引，系统继续使用关键词召回；不要开启 `AI_EMBEDDING_ENABLED` 即可。

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
100 个这样的项目约 1.2 GB。单项目默认上限是 `AI_INDEX_MAX_FILES=2000` × 每文件最多 201 切片，
单文件默认上限是 `AI_INDEX_MAX_FILE_BYTES=2097152`（2MB），可按部署机器容量调高或调低。
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
- [x] 完成 JavaParser AST、Mapper XML DOM、Vue SFC 与 JS/TS 函数的结构化符号提取
- [x] 支持代码项目索引文件数、切片数和符号数统计展示
- [x] 切换 PostgreSQL，并提供可重复执行的建表脚本
- [x] 增加 Prompt 注入检测和模型输出安全拦截
- [x] 建立开发文档和开发日志
- [x] 统一项目命名：BizLens AI、Maven 坐标、前端包名和 Spring Boot 启动类
- [x] 通过后端 Maven 编译和前端 Vite 生产构建
- [x] 角色与代码项目**多对多**：一个角色可覆盖多个项目（如前后端共用一个角色），一个项目也可建多个角色；关联项目必须全部完成索引才能对话
- [x] 索引切片落源码片段，对话前用模型把问题改写成候选标识符，并可按 pgvector 语义召回融合注入
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
- [x] 索引进度可见：列表显示文件级处理百分比、跳过数量、已用多久 / 预计还需多久，完成显示完成时间，中断显示中断时间并可一键重排
- [x] Git 索引命令超时与有限重试：单条命令 120 秒超时；瞬时网络错误最多重试 1 次，确定性错误不重复执行
- [x] 代码项目页支持「一键索引排队」：把全部项目重新排队，正在索引的自动跳过
- [x] 核心安全审计：记录提示词注入、模型成功/失败、流式结果、输出拦截和持久化失败，审计内容脱敏且不阻断主流程
- [x] 服务端分页：项目、AI 角色、会话列表支持分页和会话状态筛选，角色页可跨页复用进行中会话
- [x] 文件级完整检索：支持按文件名/路径定位目标文件，并列出该文件全部已识别接口、方法和操作
- [x] 候选 Code Graph 检索：建立 Controller → Service → Mapper → SQL/Table 关系，支持最多 4 层调用链追踪和一跳继承扩展
- [x] 模型 Key 按角色隔离：移除服务级 `DEEPSEEK_API_KEY` 兜底，模型调用必须使用角色配置的 Key
- [x] 索引前敏感信息扫描与脱敏：常见密码、Token、API Key、私钥块不直接进入源码切片
- [x] Git Commit 增量跳过：同一项目同一成功 Commit 已有索引时跳过重复解析
- [x] 方法级基础业务摘要：根据方法名和路由注解生成动作、路由摘要并写入 `business_summary`
- [x] 结构化符号提取：Java 使用 JavaParser AST，Mapper XML 使用安全 DOM，Vue 按 SFC script/template 提取
- [x] pgvector 语义召回：可选接入 OpenAI-compatible embedding，索引写入 `embedding_vec`，问答融合向量召回与关键词召回
- [x] 补测试文档 `docs/测试文档.md`，覆盖本轮全部改动与发现的问题
- [x] 对话页展示会话角色覆盖的代码项目；工作台支持全部项目/单项目视角，聚合索引统计、关联角色和下一步入口
- [x] 规范 `UserMapper` 资源文件命名：`svc/src/main/resources/mapper/UserMapper.xml.xml` 改为 `UserMapper.xml`
- [x] pgvector 改为可选增强：基础建库不依赖 `vector` 扩展，迁移无权限时跳过并保留关键词召回
- [x] 提供 `pg_trgm` 可选迁移加速 ILIKE 关键词召回；无扩展权限时查询仍按原逻辑运行
- [x] `AiProvider.listModels` 默认实现改为通用 OpenAI 兼容 `/models` 调用，并补默认实现单测
- [x] 查询改写 TTL 缓存补单测，覆盖模型成功与 fallback 两条路径
- [x] 早期未绑定角色的历史会话支持手动绑定已就绪角色后继续对话；删除当前页最后一条会话时自动回退上一页
- [x] 接入 Git Markdown 个人知识库：支持 HTTPS 仓库、分支、索引子目录、凭据隔离、增量索引、Markdown 标题切片和敏感信息脱敏
- [x] AI 角色支持关联代码项目、个人知识库或两类来源；创建/编辑时校验归属与 READY 状态，编辑时整体替换关联
- [x] 统一代码项目与个人知识库 RAG：分别进行关键词/可选向量召回，按来源分区注入 AI 对话上下文，支持知识库-only 角色
- [x] 增加个人知识库管理页、索引进度轮询和角色来源选择，并补充后端知识库接口与 PostgreSQL 表结构

已补充前端 Markdown 单测和后端符号提取、敏感扫描、向量工具单测；本轮后端 13 个测试、前端 2 个单元测试和生产构建均通过。真实 PostgreSQL、私有 Git 和端到端对话仍未完整跑通，具体缺口见下方「待测试 / 未验证」。

### 待完成

**功能性缺口**（有入口、但后端能力不完整）

当前暂无已确认的功能性缺口。

### 已知限制与待优化

1. **语义召回是可选增强**。基础建库不依赖 `pgvector`；数据库不允许安装扩展时，`migration-20261008-pgvector.sql` 会跳过向量列与索引，系统继续关键词召回。真实 pgvector 迁移与向量召回仍需上线前验证。
2. **关键词召回加速依赖可选 `pg_trgm`**。已提供 `migration-20261008-trgm-indexes.sql`，但数据库不允许安装扩展时仍会回到普通 ILIKE 查询；数据量上万后建议在允许扩展的环境执行该迁移。
3. **索引上限已可配置**：默认 `AI_INDEX_MAX_FILES=2000`、`AI_INDEX_MAX_FILE_BYTES=2097152`，超出的文件仍会跳过并统计到索引进度；大仓库可按部署容量调高，超大仓库仍建议拆分项目或分阶段索引。
4. **索引进度仍是文件级进度**。当前按可索引文件扫描结果显示已处理/总数百分比，并显示正在处理的文件路径；单文件内部解析仍没有更细粒度进度。
5. **索引中断的判定依赖独立心跳**（30 秒一次，超过 180 秒无心跳即视为进程已死）。心跳字段已与普通更新时间分离，单文件解析较慢时不会因为没有进度写入而误判；若数据库长时间不可写，任务仍可能被判定为中断。
6. **调用链关系属于候选图**。当前关系通过符号名与源码引用推导，适合辅助检索，
    仍可能因同名方法、反射、动态 SQL 或字符串拼接产生误连；更深层继承、多接口实现和 Kotlin
    复杂继承语法也尚未纳入图谱。已修的切片缺陷（切片丢注解、文件头过短、按长度升序排序、
    换行口径不一致导致的空片段）见 `docs/测试文档.md` 第 5.13 节。
7. 方法摘要当前是基于名称和路由注解的规则摘要，不代表完整业务语义；复杂方法仍需模型分析。
8. **个人知识库当前仅支持 HTTPS Git + Markdown**，暂不支持 PDF、Wiki、Notion、Web 页面和 SSH 仓库；`source_type` 已为后续扩展预留。
9. **个人知识库与代码项目分别检索后拼接上下文**，当前没有跨两类来源的真正 RRF 统一排序；召回顺序和字符预算仍需结合真实问答效果调优。
10. **个人知识库索引依赖 Git 仓库可达和正确凭据**。索引任务使用隔离 Git 环境，不继承运行机器凭据；私有仓库必须配置访问令牌。
11. **Markdown 分块以标题层级和字符上限为主**，代码块、表格和列表尚未实现完整不可分割处理；当前默认单文件 4MB、文件数 2000，可通过 `AI_KNOWLEDGE_*` 调整。
12. **个人知识库暂未提供文档预览和召回预览接口**，排查某个文件未命中主要依赖索引统计、日志和数据库查询。

### 待测试 / 未验证

**未在本机跑通的**

1. **数据库迁移脚本均未在本机执行**。本机无 `psql`（`Program Files` 下也没有），
   所有 SQL 只做了括号平衡、外键引用顺序、语句数等静态校验，没有真正打过数据库。
2. **端到端主流程未走通**：注册 → 建代码项目 → 排队索引 → 建 AI 角色 → 对话。
   各环节分别验证过编译或单点行为，但没有一次完整的串联验证。
3. **召回 SQL 未在真实库执行过**。关键词召回的 `<foreach>`、`metadata->>'startLine'`、`ILIKE` 权重，以及向量召回的 `embedding_vec <=> query` 都只经过编译与静态审查，没有跑过一次真实查询。
4. **重新索引后 `source_snippet` 是否正常写入未验证**——这是召回能否工作的前提。
5. **SSE 多行修复未运行时验证**。上次启动验证时 10610 被占用，未完成启动。
6. **索引失败原因落库与前端展示未验证**。

**已静态验证、但未运行时验证的**

7. 混合召回的实际命中率没有评测，尤其是 embedding 语义召回对中文业务问题的提升幅度未知。
8. **源码注入上下文后 `AiSafetyGuard` 的拦截效果未验证**：源码现在会进 prompt，
   模型是否真按约束用业务语言转述而不复述源码，需要构造用例实测。
9. `/ai/agent/models` 只实测过 `deepseek` 分支（真实返回 `deepseek-flash`、`deepseek-v4-pro`）；
   `openai` / `qwen` / `custom` 分支未验证，其中 qwen 的预置列表还是旧值，需以真实接口为准。

**工程基础**

10. 已补前端 Markdown 单测和后端符号提取、敏感扫描、向量工具单测；本轮 `mvn -pl svc -am test` 通过（13 tests），`pnpm run test` 通过（2 tests），`pnpm run build` 通过。数据库 Mapper、知识库索引任务、角色来源交互和 SSE 仍缺少真实依赖环境下的集成测试。

本轮继续收敛了索引状态问题：代码项目和个人知识库均已使用独立索引心跳字段，并在两个页面显示当前处理文件；兼容迁移见 `migration-20261008-indexprogress-audit.sql`。真实 PostgreSQL 迁移仍需在目标环境执行。

每完成一项，请同步更新本节勾选状态，并在 [开发日志](docs/开发日志.md) 中记录日期、变更和验证结果。

## 文档

- [开发文档](docs/开发文档.md)
- [开发日志](docs/开发日志.md)
- [测试文档](docs/测试文档.md)
- [AI 代码知识库与业务问答系统设计方案](docs/AI%20代码知识库与业务问答系统设计方案.md)
