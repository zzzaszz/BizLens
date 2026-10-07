-- 记录最近一次**成功**索引的耗时（毫秒）。
-- 用途：页面上对正在跑的索引给出"上次约需多久"的参考，让用户能估计还要等多久。
-- 只在成功时写入，失败时保留上一次成功的值（失败往往几秒就返回，拿它当参考会严重低估）。
-- 本脚本可重复执行。
--
-- 执行：psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261007-indexduration.sql

ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS last_index_ms BIGINT;
