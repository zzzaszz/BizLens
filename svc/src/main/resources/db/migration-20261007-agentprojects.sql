-- 角色与代码项目改为**多对多**：一个角色可以覆盖多个项目（例如前后端共用一个角色），
-- 一个项目也可以建多个角色（上一轮放开的规则继续保留）。
--
-- 原模型是 AiAgent.ProjectId 单列（一对多），这里新建关联表并把历史关系搬过去。
--
-- 旧列 "AiAgent"."ProjectId" 保留不删，只把 NOT NULL 放开：
--   * 新代码不再读写这一列，放开后新插入不受影响；
--   * 万一还有旧进程在跑（它仍会写这一列），也不会因为列被删而报错。
--   确认线上都换到新代码后，可手工执行：
--   ALTER TABLE "AiAgent" DROP COLUMN "ProjectId";
--
-- 本脚本可重复执行。
--
-- 执行：psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261007-agentprojects.sql

CREATE TABLE IF NOT EXISTS ai_agent_project (
    agent_id   BIGINT    NOT NULL,
    project_id BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_agent_project PRIMARY KEY (agent_id, project_id),
    CONSTRAINT fk_aap_agent   FOREIGN KEY (agent_id)   REFERENCES "AiAgent" ("Id")     ON DELETE CASCADE,
    CONSTRAINT fk_aap_project FOREIGN KEY (project_id) REFERENCES ai_code_project (id)  ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_aap_project ON ai_agent_project (project_id);

-- 搬历史关系（重复执行不会重复插入）
INSERT INTO ai_agent_project (agent_id, project_id)
SELECT "Id", "ProjectId"
FROM "AiAgent"
WHERE "ProjectId" IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE "AiAgent" ALTER COLUMN "ProjectId" DROP NOT NULL;
