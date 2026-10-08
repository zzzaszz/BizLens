-- BizLens AI：代码切片 pgvector 语义召回
-- 执行：psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261008-pgvector.sql
-- 前提：数据库已安装或允许安装 pgvector 扩展。

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE ai_code_chunk ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);

CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_vec
    ON ai_code_chunk USING hnsw (embedding_vec vector_cosine_ops);
