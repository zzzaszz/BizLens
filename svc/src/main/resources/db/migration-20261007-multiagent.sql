-- 放开「一个项目只能创建一个角色」：改为一个项目可以配置多个角色。
-- 建库脚本里原本是 CREATE UNIQUE INDEX uk_ai_agent_project ("ProjectId")，
-- 只要这个唯一索引还在，同一个项目建第二个角色就会报唯一约束冲突，所以必须删掉它。
-- 删掉后改为普通索引 idx_ai_agent_project，保证按项目查角色的性能。
-- 本脚本可重复执行，并取代 migration-20260914-projectid.sql 里第 5 步建立的"一项目一角色"约束。
--
-- 执行：psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261007-multiagent.sql

DROP INDEX IF EXISTS uk_ai_agent_project;

CREATE INDEX IF NOT EXISTS idx_ai_agent_project ON "AiAgent" ("ProjectId");
