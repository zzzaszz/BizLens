# BizLens AI

BizLens AI 是面向企业代码知识库的业务问答系统。系统通过 AI 对话解释代码背后的业务规则和处理流程，并将源码理解能力与源码输出权限分离，避免向客户泄露源代码、SQL、配置和密钥。

## 当前能力

- 用户注册、登录、JWT 会话和 Redis 限流
- AI 会话、历史消息、SSE 对话和 AI 角色管理
- DeepSeek 兼容的模型 Provider（API Key 通过环境变量提供）
- Prompt 注入检测与 AI 输出安全拦截
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

   `migration-*-projectid.sql` 会清理与新模型不兼容的历史角色（旧模型下角色不与项目绑定），**执行前请备份 `"AiAgent"`**。`sourcesnippet` 只加列不删数据，但历史切片的该列为空，需要在「代码项目」页对每个项目**重新排队索引**一次才会填充——不重新索引会导致对话时召回不到任何内容。

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
- [x] 角色与代码项目一对一绑定；未配 API Key、项目未完成索引时不允许对话
- [x] 索引切片落源码片段，对话前用模型把问题改写成候选标识符，再按关键词召回并注入

当前没有可用的自动化测试，主流程也未完整跑通；具体缺口见下方「待测试 / 未验证」。

### 待完成

**功能性缺口**（有入口、但后端能力不完整）

1. **私有仓库克隆未接入凭据**（优先级最高）。`ai_code_project.credential_ref` 目前只被 CRUD 读写，
   克隆时完全没用到：
   ```java
   runCommand(workspace.getParent(), "git", "clone", "--depth", "1", "--branch",
           project.getGitBranch(), project.getGitUrl(), workspace.toString());   // 没有任何鉴权参数
   ```
   结果是**私有仓库无论凭据填没填都克隆不了**，该字段目前是纯装饰。需要接入 Secret Manager 或
   凭据注入（如 `GIT_ASKPASS` / 带凭据的 URL / SSH key），并补超时取消与任务重试。
2. **SSE 打字机效果未实现**。`/ai/chat/stream` 注释写着"打字机效果"，但 `onToken` 只累积不推送
   （`reply.append(delta)` 后没有 `emitter.send`），整段回复是生成完才一次性到达的，中间气泡一直为空。
   改造时注意：`done` 事件不能再重发全文，否则前端会重复追加。
3. 将轻量符号提取升级为 Java AST、Mapper XML、Vue 文件的完整结构化解析。
4. 增加方法级业务摘要生成、源码安全扫描和按 Git Commit 的增量索引。
5. 将 embedding 接入 pgvector 或独立向量数据库，把当前的关键词召回升级为语义召回。
6. 实现 Controller/Service/Mapper/SQL/Table 的 Code Graph 检索。
7. 持久化 `ai_security_audit` 审计记录并增加权限分级、告警和运营监控。
8. 列表接口分页。`pagehelper` 因与 mybatis-plus 的版本冲突已移除，`/ai/conversations`、
   `/ai/project/list`、`/ai/agent/list` 目前都是全量返回，数据量上来后需要补分页。

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
5. **索引失败只记录不重试**，也没有进度上报（`PENDING → QUEUED → INDEXING → READY/FAILED` 是终态）。
6. **`DeepSeekProvider.resolveApiKey` 仍保留系统 Key 兜底**（`request.getApiKey()` 为空时回退
   `DEEPSEEK_API_KEY`）。服务层已强制角色必须配 Key，这层兜底当前不可达，但新增调用方时会静默生效。
7. **`AiProvider.listModels` 的 default 实现返回空列表**，各 Provider 需要自己实现 OpenAI 兼容的
   `/models` 调用。已在 default 里加 WARN 日志避免静默降级，但新增 Provider 时容易漏。
8. 前端对话页不展示会话所属项目；工作台是静态卡片，不支持多项目视角。
9. `svc/src/main/resources/mapper/UserMapper.xml.xml` 是双 `.xml` 后缀（拷贝项目遗留），
   能正常加载但命名不规范。

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
- [AI 代码知识库与业务问答系统设计方案](doc/AI%20代码知识库与业务问答系统设计方案.md)
