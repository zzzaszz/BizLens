-- 代码项目支持按项目配置 Git 访问凭据，使 github / gitee / gitlab / 自建 GitLab 的**私有仓库**也能索引。
--
-- 背景：上一轮已切断克隆对运行机器 Git 凭据的继承（不再借用服务账号身份读私有代码），
-- 代价是私有仓库一律失败。这里补上"按项目凭据注入"：索引时把该项目的令牌经 credential.helper
-- 注入给 git，机器自身的凭据依旧不参与。
--
-- 令牌用 AES 加密后落库（复用 CryptoService，与角色 API Key 同一套机制），不存明文。
-- credential_user 是令牌对应的用户名：GitHub 用 x-access-token、GitLab 用 oauth2，Gitee 必须填账号名。
--
-- credential_ref 是早期设计里"指向 Secret Manager 的引用"占位字段，从未参与过克隆（本机数据全为 NULL），
-- 由上面两列取代，故直接删除，避免留下一个永远不会有值的迷惑字段。
--
-- 本脚本可重复执行。
--
-- 执行：psql "$DATABASE_URL" -f svc/src/main/resources/db/migration-20261007-gitcredential.sql

ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS credential_enc  VARCHAR(512);
ALTER TABLE ai_code_project ADD COLUMN IF NOT EXISTS credential_user VARCHAR(128);
ALTER TABLE ai_code_project DROP COLUMN IF EXISTS credential_ref;
