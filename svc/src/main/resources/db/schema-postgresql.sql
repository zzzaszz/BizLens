-- BizLens AI PostgreSQL schema
-- 用法：psql "$DATABASE_URL" -f schema-postgresql.sql
-- 脚本可重复执行，不包含业务数据清理。

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

-- 角色由代码项目创建：一个项目最多一个角色（ProjectId 唯一，约束在文件末尾的升级块中补齐）。
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
    CONSTRAINT fk_ai_agent_user FOREIGN KEY ("UserId") REFERENCES "User" (id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_agent_user ON "AiAgent" ("UserId", "Enabled");
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_code_user ON "AiAgent" ("UserId", "Code") WHERE "Code" IS NOT NULL;

CREATE TABLE IF NOT EXISTS "AiConversation" (
    "Id"             BIGINT PRIMARY KEY,
    "UserId"         BIGINT NOT NULL,
    "AgentId"        BIGINT,
    "Title"          VARCHAR(128) NOT NULL DEFAULT 'AI 对话',
    "Status"         SMALLINT NOT NULL DEFAULT 1,
    "CreatedAt"      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UpdatedAt"      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY ("UserId") REFERENCES "User" (id) ON DELETE CASCADE,
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

-- 代码知识库：只保存索引、摘要和元数据，源码默认不落库。
CREATE TABLE IF NOT EXISTS ai_code_project (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    git_url         VARCHAR(512) NOT NULL,
    git_branch      VARCHAR(128) NOT NULL DEFAULT 'main',
    git_commit      VARCHAR(64),
    credential_ref  VARCHAR(256),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_indexed_at TIMESTAMP,
    created_by      BIGINT REFERENCES "User" (id) ON DELETE SET NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_code_project_status ON ai_code_project (status, updated_at DESC);

-- ============ AiAgent ← → ai_code_project 一对一绑定（升级块，可重复执行） ============
-- 业务规则：角色必须由代码项目创建，一个项目最多创建一个角色，删除项目级联删除角色。
-- 旧版本存在"无归属的系统预置角色"，在新模型下既无项目也无 Key、无法参与对话，先清理再加约束。
ALTER TABLE "AiAgent" ADD COLUMN IF NOT EXISTS "ProjectId" BIGINT;
DELETE FROM "AiAgent" WHERE "Id" = 1 AND "UserId" IS NULL;
DELETE FROM "AiAgent" WHERE "ProjectId" IS NULL;

ALTER TABLE "AiAgent" DROP CONSTRAINT IF EXISTS fk_ai_agent_project;
ALTER TABLE "AiAgent" ADD CONSTRAINT fk_ai_agent_project
    FOREIGN KEY ("ProjectId") REFERENCES ai_code_project (id) ON DELETE CASCADE;

ALTER TABLE "AiAgent" ALTER COLUMN "ProjectId" SET NOT NULL;

DROP INDEX IF EXISTS uk_ai_agent_project;
CREATE UNIQUE INDEX uk_ai_agent_project ON "AiAgent" ("ProjectId");

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

CREATE TABLE IF NOT EXISTS ai_code_chunk (
    id              BIGINT PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES ai_code_document (id) ON DELETE CASCADE,
    chunk_type      VARCHAR(32) NOT NULL,
    symbol_name     VARCHAR(256),
    business_summary TEXT,
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
    user_id         BIGINT REFERENCES "User" (id) ON DELETE SET NULL,
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

-- 说明：不再预置系统角色。所有角色都必须由代码项目创建，因此没有可脱离项目的初始数据。
