-- BizLens AI: optional pgvector semantic retrieval.
-- Execute only when the database allows installing extensions:
--   psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261008-pgvector.sql
--
-- This migration is intentionally non-fatal when pgvector cannot be installed.
-- In restricted PostgreSQL environments, it prints a NOTICE and leaves the base
-- schema usable; the application will continue with keyword retrieval.

DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS vector;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pgvector extension is unavailable or cannot be installed: %. Vector columns/indexes were skipped; keyword retrieval remains available.', SQLERRM;
        RETURN;
    END;

    ALTER TABLE ai_code_chunk
        ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);

    IF to_regclass('public.ai_knowledge_chunk') IS NOT NULL THEN
        ALTER TABLE ai_knowledge_chunk
            ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);
    END IF;

    CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_vec
        ON ai_code_chunk USING hnsw (embedding_vec vector_cosine_ops);

    IF to_regclass('public.ai_knowledge_chunk') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding_vec
            ON ai_knowledge_chunk USING hnsw (embedding_vec vector_cosine_ops);
    END IF;
END $$;
