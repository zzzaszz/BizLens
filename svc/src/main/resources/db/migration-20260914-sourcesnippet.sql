-- ============================================================================
-- BizLens AI 升级脚本：ai_code_chunk 增加 source_snippet，让对话能检索到源码
-- ----------------------------------------------------------------------------
-- 适用：已经执行过旧版 schema-postgresql.sql 的库（ai_code_chunk 上还没有 source_snippet 列）。
-- 用法：psql "$DATABASE_URL" -f migration-20260914-sourcesnippet.sql
-- 全新库不需要执行本脚本 —— schema-postgresql.sql 已直接建好该列。
--
-- 背景：旧索引器只落 path / language / symbol_name / 行号，没有源码，也没有向量，
--       导致对话时即便接上检索也召回不到任何有业务含义的内容。
--       本次新增 source_snippet 存切片源码原文（索引时按长度预算截断）。
--
-- 注意：本脚本只加列，不删任何数据，可重复执行。
--       但历史切片的新列为空，需要在「代码项目」页对每个项目重新排队索引一次才会填充。
-- ============================================================================

BEGIN;

ALTER TABLE ai_code_chunk ADD COLUMN IF NOT EXISTS source_snippet TEXT;

COMMIT;

-- 重新索引（在页面操作即可，或直接在库里把项目状态打回待索引）：
--   UPDATE ai_code_project SET status = 'PENDING' WHERE status = 'READY';
