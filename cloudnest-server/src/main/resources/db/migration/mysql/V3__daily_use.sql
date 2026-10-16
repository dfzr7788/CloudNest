ALTER TABLE auth_session ADD created_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE auth_session ADD last_seen BIGINT NOT NULL DEFAULT 0;
ALTER TABLE auth_session ADD client_label VARCHAR(180) NOT NULL DEFAULT '未知客户端';
ALTER TABLE drive_entry ADD favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE drive_entry ADD last_opened BIGINT;
CREATE TABLE activity_log (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),action VARCHAR(40) NOT NULL,target_id VARCHAR(36),created_at BIGINT NOT NULL);
CREATE INDEX ix_activity_user ON activity_log(user_id,created_at);
