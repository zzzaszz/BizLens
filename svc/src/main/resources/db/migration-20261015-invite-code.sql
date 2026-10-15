-- 邀请码注册安全增强。
-- 可重复执行：已有数据库执行本迁移，新数据库同步执行 schema-postgresql.sql。

CREATE TABLE IF NOT EXISTS ai_invite_code (
    id         BIGINT PRIMARY KEY,
    code       VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES "User" ("Id") ON DELETE CASCADE,
    used_by    BIGINT REFERENCES "User" ("Id") ON DELETE SET NULL,
    status     SMALLINT NOT NULL DEFAULT 0,
    expire_at  TIMESTAMP NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_invite_code_status CHECK (status IN (0, 1, 2))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invite_code
    ON ai_invite_code (code);

CREATE INDEX IF NOT EXISTS idx_invite_creator
    ON ai_invite_code (created_by, status);
