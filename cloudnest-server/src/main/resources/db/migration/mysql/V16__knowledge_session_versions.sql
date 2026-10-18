ALTER TABLE ai_kb_session ADD COLUMN model_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_kb_session ADD COLUMN connection_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_kb_session ADD COLUMN connection_id VARCHAR(36);
