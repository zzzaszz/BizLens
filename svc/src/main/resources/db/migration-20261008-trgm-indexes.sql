-- BizLens AI: optional trigram indexes for keyword retrieval.
-- These indexes speed up ILIKE-based fallback retrieval. If pg_trgm cannot be
-- installed in the target database, this migration prints a NOTICE and exits;
-- queries still work, only without trigram acceleration.

DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_trgm extension is unavailable or cannot be installed: %. Trigram indexes were skipped.', SQLERRM;
        RETURN;
    END;

    CREATE INDEX IF NOT EXISTS idx_code_document_path_trgm
        ON ai_code_document USING gin (path gin_trgm_ops);

    CREATE INDEX IF NOT EXISTS idx_code_chunk_symbol_trgm
        ON ai_code_chunk USING gin (symbol_name gin_trgm_ops);

    CREATE INDEX IF NOT EXISTS idx_code_chunk_snippet_trgm
        ON ai_code_chunk USING gin (source_snippet gin_trgm_ops);

    IF to_regclass('public.ai_knowledge_document') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_knowledge_document_path_trgm
            ON ai_knowledge_document USING gin (path gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_document_title_trgm
            ON ai_knowledge_document USING gin (title gin_trgm_ops);
    END IF;

    IF to_regclass('public.ai_knowledge_chunk') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_heading_trgm
            ON ai_knowledge_chunk USING gin (heading_path gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_trgm
            ON ai_knowledge_chunk USING gin (content gin_trgm_ops);
    END IF;
END $$;
