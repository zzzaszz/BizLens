-- ============================================================================
-- BizLens AI 升级脚本：AiAgent 增加 ProjectId，把角色绑定到代码项目
-- ----------------------------------------------------------------------------
-- 适用：已经执行过旧版 schema-postgresql.sql 的库（"AiAgent" 上还没有 "ProjectId" 列）。
-- 用法：psql "$DATABASE_URL" -f migration-20260914-projectid.sql
-- 全新库不需要执行本脚本 —— schema-postgresql.sql 已直接建好该列与全部约束。
--
-- 业务规则：角色必须由代码项目创建；一个项目最多创建一个角色；删除项目级联删除角色。
--
-- ⚠ 破坏性操作，执行前请先备份 "AiAgent"：
--     下面两条 DELETE 会移除
--       ① Id=1 且无归属的系统预置角色（旧版的 business-qa 种子数据）；
--       ② 所有 ProjectId 为空的历史角色 —— 旧模型下角色不与项目绑定，
--          无法自动推断出它该属于哪个项目，只能清理，需要保留请先手工补 ProjectId。
--   脚本整体在事务中执行，任一步失败会整体回滚。可重复执行。
-- ============================================================================

BEGIN;

-- 1. 加列。老库没有该列；重复执行时为 no-op
ALTER TABLE "AiAgent" ADD COLUMN IF NOT EXISTS "ProjectId" BIGINT;

-- 2. 清理与新模型不兼容的数据
DELETE FROM "AiAgent" WHERE "Id" = 1 AND "UserId" IS NULL;
DELETE FROM "AiAgent" WHERE "ProjectId" IS NULL;

-- 3. 外键：删除项目时级联删除其角色
ALTER TABLE "AiAgent" DROP CONSTRAINT IF EXISTS fk_ai_agent_project;
ALTER TABLE "AiAgent" ADD CONSTRAINT fk_ai_agent_project
    FOREIGN KEY ("ProjectId") REFERENCES ai_code_project (id) ON DELETE CASCADE;

-- 4. 角色必须有归属项目
ALTER TABLE "AiAgent" ALTER COLUMN "ProjectId" SET NOT NULL;

-- 5. 一个项目最多一个角色
DROP INDEX IF EXISTS uk_ai_agent_project;
CREATE UNIQUE INDEX uk_ai_agent_project ON "AiAgent" ("ProjectId");

COMMIT;
