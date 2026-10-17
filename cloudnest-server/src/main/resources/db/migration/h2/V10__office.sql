CREATE TABLE office_session(id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),file_id VARCHAR(36) NOT NULL REFERENCES drive_entry(id),current_revision BIGINT NOT NULL,expires_at BIGINT NOT NULL,created_at BIGINT NOT NULL);
CREATE INDEX ix_office_file ON office_session(user_id,file_id,expires_at);
