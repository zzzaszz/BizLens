-- Code Graph 关系表升级（可重复执行）
-- 已执行过 schema-postgresql.sql 的数据库也需要执行本迁移。
CREATE TABLE IF NOT EXISTS ai_code_relation (
    id              BIGINT PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES ai_code_project (id) ON DELETE CASCADE,
    source_chunk_id BIGINT NOT NULL REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    target_chunk_id BIGINT NOT NULL REFERENCES ai_code_chunk (id) ON DELETE CASCADE,
    relation_type   VARCHAR(32) NOT NULL,
    UNIQUE (source_chunk_id, target_chunk_id, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_code_relation_source
    ON ai_code_relation (source_chunk_id, relation_type);
CREATE INDEX IF NOT EXISTS idx_code_relation_target
    ON ai_code_relation (target_chunk_id, relation_type);
