-- BizLens AI complete PostgreSQL schema.
-- Intended for a new database. Existing databases should use the migration files.
-- Optional pgvector and pg_trgm support is attempted at the end of this script.
--
-- Usage:
--   psql "$DATABASE_URL" -f schema-postgresql-complete.sql
--
-- The application generates BIGINT identifiers itself. No sequences are required.

BEGIN;

-- Core users.
CREATE TABLE IF NOT EXISTS "User" (
    Id           BIGINT PRIMARY KEY,
    DeviceId     VARCHAR(128) NOT NULL DEFAULT '',
    Username     VARCHAR(32) NOT NULL,
    PasswordHash VARCHAR(255) NOT NULL,
    Nickname     VARCHAR(64) NOT NULL DEFAULT '',
    Avatar       VARCHAR(255) NOT NULL DEFAULT '',
    ThemeColor   VARCHAR(32),
    Status       SMALLINT NOT NULL DEFAULT 1,
    Role         SMALLINT NOT NULL DEFAULT 0,
    LastLoginIp  VARCHAR(64),
    LastLoginAt  TIMESTAMP,
    CreatedAt    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Deleted      SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username
    ON "User" (Username);
CREATE INDEX IF NOT EXISTS idx_user_status
    ON "User" (Status, Deleted);

-- Default administrator account: admin / 123456.
INSERT INTO "User" (
    Id, DeviceId, Username, PasswordHash, Nickname, Avatar, ThemeColor,
    Status, Role, Deleted
)
VALUES (
    1,
    'admin',
    'admin',
    'VGVuYW50QWRtaW5TZWVkIQ==:XYKHaSL54fk63fO2WuWBmUoh5fnPrXZlSmcQIzGV8go=',
    'admin',
    '',
    '#2563EB',
    1,
    1,
    0
)
ON CONFLICT (Username) DO NOTHING;

CREATE TABLE IF NOT EXISTS ai_invite_code (
    id         BIGINT PRIMARY KEY,
    code       VARCHAR(16) NOT NULL,
     created_by BIGINT NOT NULL REFERENCES "User" (Id) ON DELETE CASCADE,
     used_by    BIGINT REFERENCES "User" (Id) ON DELETE SET NULL,
    status     SMALLINT NOT NULL DEFAULT 0 CHECK (status IN (0, 1, 2)),
    expire_at  TIMESTAMP NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invite_code
    ON ai_invite_code (code);
CREATE INDEX IF NOT EXISTS idx_invite_creator
    ON ai_invite_code (created_by, status);

-- Git code projects and indexing state.
CREATE TABLE IF NOT EXISTS ai_code_project (
    id                     BIGINT PRIMARY KEY,
    name                   VARCHAR(128) NOT NULL,
    git_url                VARCHAR(512) NOT NULL,
    git_branch             VARCHAR(128) NOT NULL DEFAULT 'main',
    git_commit             VARCHAR(64),
    credential_enc         VARCHAR(512),
    credential_user        VARCHAR(128),
    status                 VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_error             TEXT,
    last_indexed_at        TIMESTAMP,
    last_index_ms          BIGINT,
    index_total_files      INTEGER NOT NULL DEFAULT 0,
    index_processed_files  INTEGER NOT NULL DEFAULT 0,
    index_skipped_files    INTEGER NOT NULL DEFAULT 0,
    index_started_at       TIMESTAMP,
    index_heartbeat_at     TIMESTAMP,
    index_current_file     VARCHAR(1024),
    created_by             BIGINT REFERENCES "User" (Id) ON DELETE SET NULL,
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_project_status
    ON ai_code_project (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_code_project_user
    ON ai_code_project (created_by, updated_at DESC);

-- AI roles. Project and knowledge source ownership is represented by
-- ai_agent_project and ai_agent_knowledge, respectively.
CREATE TABLE IF NOT EXISTS "AiAgent" (
    "Id"           BIGINT PRIMARY KEY,
    "UserId"       BIGINT,
    "Name"         VARCHAR(64) NOT NULL,
    "Code"         VARCHAR(32),
    "Model"        VARCHAR(64),
    "SystemPrompt" TEXT,
    "Temperature"  NUMERIC(3,2) NOT NULL DEFAULT 0.70
                   CHECK ("Temperature" >= 0 AND "Temperature" <= 2),
    "MaxTokens"    INTEGER NOT NULL DEFAULT 2048
                   CHECK ("MaxTokens" > 0),
    "Enabled"      SMALLINT NOT NULL DEFAULT 1,
    "IsSystem"     SMALLINT NOT NULL DEFAULT 0,
    "Avatar"       VARCHAR(255),
    "Provider"     VARCHAR(32),
    "ApiBaseUrl"   VARCHAR(255),
    "ApiKeyEnc"    VARCHAR(512),
    "CreatedAt"    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UpdatedAt"    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_agent_user
        FOREIGN KEY ("UserId") REFERENCES "User" (Id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_user
    ON "AiAgent" ("UserId", "Enabled");
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_agent_code_user
    ON "AiAgent" ("UserId", "Code")
    WHERE "Code" IS NOT NULL;

CREATE TABLE IF NOT EXISTS ai_agent_project (
    agent_id   BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_agent_project
        PRIMARY KEY (agent_id, project_id),
    CONSTRAINT fk_aap_agent
        FOREIGN KEY (agent_id) REFERENCES "AiAgent" ("Id") ON DELETE CASCADE,
    CONSTRAINT fk_aap_project
        FOREIGN KEY (project_id) REFERENCES ai_code_project (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_project_project
    ON ai_agent_project (project_id);

-- Git-hosted personal knowledge sources.
CREATE TABLE IF NOT EXISTS ai_knowledge_source (
    id                     BIGINT PRIMARY KEY,
    name                   VARCHAR(128) NOT NULL,
    source_type            VARCHAR(32) NOT NULL DEFAULT 'GIT_MARKDOWN',
    git_url                VARCHAR(512) NOT NULL,
    git_branch             VARCHAR(128) NOT NULL DEFAULT 'main',
    git_commit             VARCHAR(64),
    root_path              VARCHAR(256) NOT NULL DEFAULT '',
    credential_enc         VARCHAR(512),
    credential_user        VARCHAR(128),
    status                 VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_error             TEXT,
    last_indexed_at        TIMESTAMP,
    last_index_ms          BIGINT,
    index_total_files      INTEGER NOT NULL DEFAULT 0,
    index_processed_files  INTEGER NOT NULL DEFAULT 0,
    index_skipped_files    INTEGER NOT NULL DEFAULT 0,
    index_heartbeat_at     TIMESTAMP,
    index_current_file     VARCHAR(1024),
    created_by             BIGINT REFERENCES "User" (Id) ON DELETE SET NULL,
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_source_status
    ON ai_knowledge_source (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_user
    ON ai_knowledge_source (created_by, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai_knowledge_document (
    id          BIGINT PRIMARY KEY,
    source_id   BIGINT NOT NULL
                REFERENCES ai_knowledge_source (id) ON DELETE CASCADE,
    path        VARCHAR(1024) NOT NULL,
    title       VARCHAR(512),
    checksum    VARCHAR(128) NOT NULL,
    size_bytes  INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_document_source_path
        UNIQUE (source_id, path)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_source
    ON ai_knowledge_document (source_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id            BIGINT PRIMARY KEY,
    document_id   BIGINT NOT NULL
                  REFERENCES ai_knowledge_document (id) ON DELETE CASCADE,
    chunk_index   INTEGER NOT NULL,
    heading_path  VARCHAR(512),
    heading_level SMALLINT,
    content       TEXT NOT NULL,
    metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_document
    ON ai_knowledge_chunk (document_id, chunk_index);

CREATE TABLE IF NOT EXISTS ai_agent_knowledge (
    agent_id    BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_agent_knowledge
        PRIMARY KEY (agent_id, knowledge_id),
    CONSTRAINT fk_aak_agent
        FOREIGN KEY (agent_id) REFERENCES "AiAgent" ("Id") ON DELETE CASCADE,
    CONSTRAINT fk_aak_knowledge
        FOREIGN KEY (knowledge_id) REFERENCES ai_knowledge_source (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_knowledge_source
    ON ai_agent_knowledge (knowledge_id);

-- Conversations and chat messages.
CREATE TABLE IF NOT EXISTS "AiConversation" (
    "Id"        BIGINT PRIMARY KEY,
    "UserId"    BIGINT NOT NULL,
    "AgentId"   BIGINT,
    "Title"     VARCHAR(128) NOT NULL DEFAULT 'AI Conversation',
    "Status"    SMALLINT NOT NULL DEFAULT 1,
    "CreatedAt" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UpdatedAt" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_conversation_user
        FOREIGN KEY ("UserId") REFERENCES "User" (Id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_conversation_agent
        FOREIGN KEY ("AgentId") REFERENCES "AiAgent" ("Id") ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_user
    ON "AiConversation" ("UserId", "Status", "UpdatedAt" DESC);

CREATE TABLE IF NOT EXISTS "AiChatMessage" (
    "Id"             BIGINT PRIMARY KEY,
    "ConversationId" BIGINT NOT NULL,
    "SenderType"     VARCHAR(16) NOT NULL
                     CHECK ("SenderType" IN ('USER', 'AI', 'SYSTEM')),
    "SenderId"       VARCHAR(64),
    "Content"        TEXT,
    "CreateTime"     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_message_conversation
        FOREIGN KEY ("ConversationId")
        REFERENCES "AiConversation" ("Id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_message_conversation
    ON "AiChatMessage" ("ConversationId", "CreateTime");

-- Code documents and chunks.
CREATE TABLE IF NOT EXISTS ai_code_document (
    id          BIGINT PRIMARY KEY,
    project_id  BIGINT NOT NULL
                REFERENCES ai_code_project (id) ON DELETE CASCADE,
    path        VARCHAR(1024) NOT NULL,
    language    VARCHAR(32) NOT NULL,
    checksum    VARCHAR(128) NOT NULL,
    symbol_name VARCHAR(256),
    summary     TEXT,
    start_line  INTEGER,
    end_line    INTEGER,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_code_document_project_path_checksum
        UNIQUE (project_id, path, checksum)
);

CREATE INDEX IF NOT EXISTS idx_code_document_project
    ON ai_code_document (project_id, language);

CREATE TABLE IF NOT EXISTS ai_code_chunk (
    id              BIGINT PRIMARY KEY,
    document_id     BIGINT NOT NULL
                    REFERENCES ai_code_document (id) ON DELETE CASCADE,
    chunk_type      VARCHAR(32) NOT NULL,
    symbol_name     VARCHAR(256),
    business_summary TEXT,
    source_snippet  TEXT,
    embedding       JSONB,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_chunk_document
    ON ai_code_chunk (document_id, chunk_type);

CREATE TABLE IF NOT EXISTS ai_code_relation (
    id              BIGINT PRIMARY KEY,
    project_id      BIGINT NOT NULL
                    REFERENCES ai_code_project (id) ON DELETE CASCADE,
    source_chunk_id BIGINT NOT NULL
                    REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    target_chunk_id BIGINT NOT NULL
                    REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    relation_type   VARCHAR(32) NOT NULL,
    CONSTRAINT uk_code_relation
        UNIQUE (source_chunk_id, target_chunk_id, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_code_relation_project
    ON ai_code_relation (project_id);
CREATE INDEX IF NOT EXISTS idx_code_relation_source
    ON ai_code_relation (source_chunk_id, relation_type);
CREATE INDEX IF NOT EXISTS idx_code_relation_target
    ON ai_code_relation (target_chunk_id, relation_type);

-- Security audit records. Sensitive values and raw model responses are not stored.
CREATE TABLE IF NOT EXISTS ai_security_audit (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT
                    REFERENCES "User" (Id) ON DELETE SET NULL,
    project_id      BIGINT
                    REFERENCES ai_code_project (id) ON DELETE SET NULL,
    question        TEXT NOT NULL,
    retrieval_meta  JSONB NOT NULL DEFAULT '{}'::jsonb,
    model           VARCHAR(64),
    response_status VARCHAR(32) NOT NULL,
    triggered_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    blocked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_security_audit_user_time
    ON ai_security_audit (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_project_time
    ON ai_security_audit (project_id, created_at DESC);

-- Optional pgvector support. The base schema remains usable without it.
-- The vector dimension must match AI_EMBEDDING_DIMENSION in application config.
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS vector;

        ALTER TABLE ai_code_chunk
            ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);
        ALTER TABLE ai_knowledge_chunk
            ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);

        CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_vec
            ON ai_code_chunk USING hnsw (embedding_vec vector_cosine_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding_vec
            ON ai_knowledge_chunk USING hnsw (embedding_vec vector_cosine_ops);
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE
            'pgvector is unavailable or cannot be installed: %. Vector columns and indexes were skipped.',
            SQLERRM;
    END;
END
$$;

-- Optional pg_trgm support for ILIKE-based keyword retrieval.
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;

        CREATE INDEX IF NOT EXISTS idx_code_document_path_trgm
            ON ai_code_document USING gin (path gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_code_chunk_symbol_trgm
            ON ai_code_chunk USING gin (symbol_name gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_code_chunk_snippet_trgm
            ON ai_code_chunk USING gin (source_snippet gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_document_path_trgm
            ON ai_knowledge_document USING gin (path gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_document_title_trgm
            ON ai_knowledge_document USING gin (title gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_heading_trgm
            ON ai_knowledge_chunk USING gin (heading_path gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_trgm
            ON ai_knowledge_chunk USING gin (content gin_trgm_ops);
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE
            'pg_trgm is unavailable or cannot be installed: %. Trigram indexes were skipped.',
            SQLERRM;
    END;
END
$$;

COMMIT;
