-- 索引真实进度与安全审计写入支持（可重复执行）
ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS index_total_files INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS index_processed_files INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS index_skipped_files INTEGER NOT NULL DEFAULT 0;

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
