# BizLens AI

BizLens AI 是面向企业代码知识库的业务问答系统。系统通过 AI 对话解释代码背后的业务规则和处理流程，并将源码理解能力与源码输出权限分离，避免向客户泄露源代码、SQL、配置和密钥。

## 当前能力

- 用户注册、登录、JWT 会话和 Redis 限流
- AI 会话、历史消息、SSE 对话和 AI 角色管理
- DeepSeek 兼容的模型 Provider（API Key 通过环境变量提供）
- Prompt 注入检测与 AI 输出安全拦截
- PostgreSQL 持久化：用户、AI 会话、代码知识库索引和安全审计
- 代码项目、文档、方法切片和调用关系的数据模型，为后续 Code RAG/Graph 接入预留
- 基础工作台和 `/api/system/health` 服务探针
- 代码项目元数据管理、归属校验和索引任务排队入口
- 基础 Git 临时工作区索引：浅克隆、文件元数据、轻量方法/符号级切片、Commit 和状态回写；项目页展示索引统计

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

1. 初始化数据库：

   ```bash
   psql -h localhost -U postgres -d bizlens -f svc/src/main/resources/db/schema-postgresql.sql
   ```

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

测试现状：当前未建立前端测试用例，`pnpm run test` 会因现有 Vitest/Vite 包导出兼容问题在启动阶段失败；本次未升级依赖，后续 CI 建设时统一处理。

### 待完成

1. 增强 Git 临时工作区：接入 Secret Manager 凭据、超时取消和任务重试。
2. 将轻量符号提取升级为 Java AST、Mapper XML、Vue 文件的完整结构化解析。
3. 增加方法级业务摘要生成、源码安全扫描和按 Git Commit 的增量索引。
4. 将 embedding 接入 pgvector 或独立向量数据库，实现 Code RAG。
5. 实现 Controller/Service/Mapper/SQL/Table 的 Code Graph 检索。
6. 持久化 `ai_security_audit` 审计记录并增加权限分级、告警和运营监控。
7. 增加后端单元测试、接口测试、前端组件测试和 CI 构建流程。

每完成一项，请同步更新本节勾选状态，并在 [开发日志](docs/开发日志.md) 中记录日期、变更和验证结果。

## 文档

- [开发文档](docs/开发文档.md)
- [开发日志](docs/开发日志.md)
- [AI 代码知识库与业务问答系统设计方案](doc/AI%20代码知识库与业务问答系统设计方案.md)
