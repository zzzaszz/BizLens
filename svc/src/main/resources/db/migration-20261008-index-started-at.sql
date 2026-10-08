-- Separate index duration from heartbeat time so each project's elapsed time
-- remains stable while the heartbeat is refreshed (可重复执行).
ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS index_started_at TIMESTAMP;
