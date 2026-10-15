-- BizLens AI PostgreSQL schema（全新库建库脚本）
-- 用法：psql "$DATABASE_URL" -f schema-postgresql.sql
-- 脚本可重复执行，不包含业务数据清理。
--
-- 本文件只负责「从零建库」：所有表、外键、索引一次建成。
-- 已有库的结构变更请执行同目录下的 migration-*.sql，不要指望重复执行本文件来升级。

CREATE TABLE IF NOT EXISTS "User" (
    Id              BIGINT PRIMARY KEY,
    DeviceId        VARCHAR(128) NOT NULL DEFAULT '',
    Username        VARCHAR(32) NOT NULL,
    PasswordHash    VARCHAR(255) NOT NULL,
    Nickname        VARCHAR(64) NOT NULL DEFAULT '',
    Avatar          VARCHAR(255) NOT NULL DEFAULT '',
    ThemeColor      VARCHAR(32),
    Status          SMALLINT NOT NULL DEFAULT 1,
    Role            SMALLINT NOT NULL DEFAULT 0,
    LastLoginIp     VARCHAR(64),
    LastLoginAt     TIMESTAMP,
    CreatedAt       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Deleted         SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON "User" (Username);
CREATE INDEX IF NOT EXISTS idx_user_status ON "User" (Status, Deleted);

CREATE TABLE IF NOT EXISTS ai_invite_code (
    id         BIGINT PRIMARY KEY,
    code       VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES "User" ("Id") ON DELETE CASCADE,
    used_by    BIGINT REFERENCES "User" ("Id") ON DELETE SET NULL,
    status     SMALLINT NOT NULL DEFAULT 0 CHECK (status IN (0, 1, 2)),
    expire_at  TIMESTAMP NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_invite_code ON ai_invite_code (code);
CREATE INDEX IF NOT EXISTS idx_invite_creator ON ai_invite_code (created_by, status);

-- 代码知识库：保存索引、摘要、元数据和受预算控制的源码片段；源码只作为受控召回上下文。
-- 排在 "AiAgent" 之前，因为角色要外键引用它。
CREATE TABLE IF NOT EXISTS ai_code_project (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    git_url         VARCHAR(512) NOT NULL,
    git_branch      VARCHAR(128) NOT NULL DEFAULT 'main',
    git_commit      VARCHAR(64),
    -- 私有仓库的访问凭据：令牌 AES 加密后存这里（复用 CryptoService，与角色 API Key 同一套），不存明文。
    -- credential_user 是令牌对应的用户名：GitHub/GitLab 用 x-access-token / oauth2，Gitee 需填账号名。
    credential_enc  VARCHAR(512),
    credential_user VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- 最近一次索引失败的原因（已截断、已抹掉可能出现在 Git 输出里的 URL 凭据）；成功时清空
    last_error      TEXT,
    last_indexed_at TIMESTAMP,
    -- 最近一次成功索引的耗时（毫秒），用于页面上估算"还要多久"
    last_index_ms   BIGINT,
    index_total_files INTEGER NOT NULL DEFAULT 0,
    index_processed_files INTEGER NOT NULL DEFAULT 0,
    index_skipped_files INTEGER NOT NULL DEFAULT 0,
    index_started_at TIMESTAMP,
    index_heartbeat_at TIMESTAMP,
    index_current_file VARCHAR(1024),
    created_by      BIGINT REFERENCES "User" ("Id") ON DELETE SET NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_code_project_status ON ai_code_project (status, updated_at DESC);

-- AI 角色：由代码项目创建。角色与项目是**多对多**（见 ai_agent_project）：
-- 一个角色可以覆盖多个项目（例如前后端共用一个角色），一个项目也可以建多个角色。
-- "ProjectId" 是早期的一对一列，已废弃不再读写（保留以便回滚，放开 NOT NULL），
-- 新部署可直接删掉这一列。
-- IsSystem 为历史字段（原「系统预置角色」），当前角色均由项目创建，恒为 0。
CREATE TABLE IF NOT EXISTS "AiAgent" (
    "Id"           BIGINT PRIMARY KEY,
    "UserId"       BIGINT,
    "ProjectId"    BIGINT,
    "Name"         VARCHAR(64) NOT NULL,
    "Code"         VARCHAR(32),
    "Model"        VARCHAR(64),
    "SystemPrompt" TEXT,
    "Temperature"  NUMERIC(3,2) NOT NULL DEFAULT 0.70 CHECK ("Temperature" >= 0 AND "Temperature" <= 2),
    "MaxTokens"    INTEGER NOT NULL DEFAULT 2048 CHECK ("MaxTokens" > 0),
    "Enabled"      SMALLINT NOT NULL DEFAULT 1,
    "IsSystem"     SMALLINT NOT NULL DEFAULT 0,
    "Avatar"       VARCHAR(255),
    "Provider"     VARCHAR(32),
    "ApiBaseUrl"   VARCHAR(255),
    "ApiKeyEnc"    VARCHAR(512),
    "CreatedAt"    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UpdatedAt"    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_agent_user FOREIGN KEY ("UserId") REFERENCES "User" ("Id") ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_agent_user ON "AiAgent" ("UserId", "Enabled");
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_code_user ON "AiAgent" ("UserId", "Code") WHERE "Code" IS NOT NULL;

-- 角色 ↔ 项目 关联表（多对多）
CREATE TABLE IF NOT EXISTS ai_agent_project (
    agent_id   BIGINT    NOT NULL,
    project_id BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_agent_project PRIMARY KEY (agent_id, project_id),
    CONSTRAINT fk_aap_agent   FOREIGN KEY (agent_id)   REFERENCES "AiAgent" ("Id")    ON DELETE CASCADE,
    CONSTRAINT fk_aap_project FOREIGN KEY (project_id) REFERENCES ai_code_project (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_aap_project ON ai_agent_project (project_id);

-- 个人知识库：本期支持 Git 托管 Markdown，与代码项目并列作为 RAG 来源。
-- pgvector 是可选增强，不作为全新库建库前提；需要向量召回时再执行
-- migration-20261008-pgvector.sql。数据库不允许安装扩展时，系统继续走关键词召回。

CREATE TABLE IF NOT EXISTS ai_knowledge_source (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'GIT_MARKDOWN',
    git_url VARCHAR(512) NOT NULL,
    git_branch VARCHAR(128) NOT NULL DEFAULT 'main',
    git_commit VARCHAR(64),
    root_path VARCHAR(256) NOT NULL DEFAULT '',
    credential_enc VARCHAR(512),
    credential_user VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_error TEXT,
    last_indexed_at TIMESTAMP,
    last_index_ms BIGINT,
    index_total_files INTEGER NOT NULL DEFAULT 0,
    index_processed_files INTEGER NOT NULL DEFAULT 0,
    index_skipped_files INTEGER NOT NULL DEFAULT 0,
    index_heartbeat_at TIMESTAMP,
    index_current_file VARCHAR(1024),
    created_by BIGINT REFERENCES "User" ("Id") ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_status ON ai_knowledge_source (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_user ON ai_knowledge_source (created_by);

CREATE TABLE IF NOT EXISTS ai_knowledge_document (
    id BIGINT PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES ai_knowledge_source (id) ON DELETE CASCADE,
    path VARCHAR(1024) NOT NULL,
    title VARCHAR(512),
    checksum VARCHAR(128) NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_id, path)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_source ON ai_knowledge_document (source_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES ai_knowledge_document (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    heading_path VARCHAR(512),
    heading_level SMALLINT,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_document ON ai_knowledge_chunk (document_id, chunk_index);

CREATE TABLE IF NOT EXISTS ai_agent_knowledge (
    agent_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_agent_knowledge PRIMARY KEY (agent_id, knowledge_id),
    CONSTRAINT fk_aak_agent FOREIGN KEY (agent_id) REFERENCES "AiAgent" ("Id") ON DELETE CASCADE,
    CONSTRAINT fk_aak_knowledge FOREIGN KEY (knowledge_id) REFERENCES ai_knowledge_source (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_aak_knowledge ON ai_agent_knowledge (knowledge_id);

CREATE TABLE IF NOT EXISTS "AiConversation" (
    "Id"             BIGINT PRIMARY KEY,
    "UserId"         BIGINT NOT NULL,
    "AgentId"        BIGINT,
    "Title"          VARCHAR(128) NOT NULL DEFAULT 'AI 对话',
    "Status"         SMALLINT NOT NULL DEFAULT 1,
    "CreatedAt"      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UpdatedAt"      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY ("UserId") REFERENCES "User" ("Id") ON DELETE CASCADE,
    CONSTRAINT fk_ai_conversation_agent FOREIGN KEY ("AgentId") REFERENCES "AiAgent" ("Id") ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_conversation_user ON "AiConversation" ("UserId", "Status", "UpdatedAt" DESC);

CREATE TABLE IF NOT EXISTS "AiChatMessage" (
    "Id"             BIGINT PRIMARY KEY,
    "ConversationId" BIGINT NOT NULL,
    "SenderType"     VARCHAR(16) NOT NULL CHECK ("SenderType" IN ('USER', 'AI', 'SYSTEM')),
    "SenderId"       VARCHAR(64),
    "Content"        TEXT,
    "CreateTime"     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY ("ConversationId") REFERENCES "AiConversation" ("Id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_message_conversation ON "AiChatMessage" ("ConversationId", "CreateTime");

CREATE TABLE IF NOT EXISTS ai_code_document (
    id              BIGINT PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES ai_code_project (id) ON DELETE CASCADE,
    path            VARCHAR(1024) NOT NULL,
    language        VARCHAR(32) NOT NULL,
    checksum        VARCHAR(128) NOT NULL,
    symbol_name     VARCHAR(256),
    summary         TEXT,
    start_line      INTEGER,
    end_line        INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, path, checksum)
);
CREATE INDEX IF NOT EXISTS idx_code_document_project ON ai_code_document (project_id, language);

-- source_snippet：切片对应的源码原文（已按长度预算截断）。
-- 对话时按关键词召回后注入模型用于理解业务逻辑；回答侧由 AiSafetyGuard 拦截源码输出。
CREATE TABLE IF NOT EXISTS ai_code_chunk (
    id              BIGINT PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES ai_code_document (id) ON DELETE CASCADE,
    chunk_type      VARCHAR(32) NOT NULL,
    symbol_name     VARCHAR(256),
    business_summary TEXT,
    source_snippet  TEXT,
    embedding       JSONB,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_code_chunk_document ON ai_code_chunk (document_id, chunk_type);

CREATE TABLE IF NOT EXISTS ai_code_relation (
    id              BIGINT PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES ai_code_project (id) ON DELETE CASCADE,
    source_chunk_id BIGINT NOT NULL REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    target_chunk_id BIGINT NOT NULL REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    relation_type   VARCHAR(32) NOT NULL,
    UNIQUE (source_chunk_id, target_chunk_id, relation_type)
);
CREATE INDEX IF NOT EXISTS idx_code_relation_source ON ai_code_relation (source_chunk_id, relation_type);
CREATE INDEX IF NOT EXISTS idx_code_relation_target ON ai_code_relation (target_chunk_id, relation_type);

CREATE TABLE IF NOT EXISTS ai_security_audit (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT REFERENCES "User" ("Id") ON DELETE SET NULL,
    project_id      BIGINT REFERENCES ai_code_project (id) ON DELETE SET NULL,
    question        TEXT NOT NULL,
    retrieval_meta  JSONB NOT NULL DEFAULT '{}'::jsonb,
    model           VARCHAR(64),
    response_status VARCHAR(32) NOT NULL,
    triggered_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    blocked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_security_audit_user_time ON ai_security_audit (user_id, created_at DESC);

-- 说明：不预置任何角色。角色必须由代码项目创建，因此没有可脱离项目的初始数据。
