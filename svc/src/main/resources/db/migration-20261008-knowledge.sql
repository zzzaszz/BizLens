-- Personal knowledge base tables for Git-hosted Markdown sources.
-- pgvector is optional. Run migration-20261008-pgvector.sql separately when the
-- database allows installing extensions and vector retrieval is needed.

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
