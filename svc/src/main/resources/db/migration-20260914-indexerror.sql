-- ============================================================================
-- BizLens AI 升级脚本：ai_code_project 增加 last_error，记录索引失败原因
-- ----------------------------------------------------------------------------
-- 适用：已经执行过旧版 schema-postgresql.sql 的库（ai_code_project 上还没有 last_error 列）。
-- 用法：psql "$DATABASE_URL" -f migration-20260914-indexerror.sql
-- 全新库不需要执行本脚本 —— schema-postgresql.sql 已直接建好该列。
--
-- 背景：私有仓库未配置访问凭据、分支不存在、网络不通等都会让索引失败，
--       但此前只把状态置为 FAILED，失败原因只写进了服务端日志，
--       前端只能看到一个「失败」标签，无从排查。
--
-- 注意：本脚本只加列，不删任何数据，可重复执行。
-- ============================================================================

BEGIN;

ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS last_error TEXT;

COMMIT;
