CREATE TABLE office_session(id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),file_id VARCHAR(36) NOT NULL REFERENCES drive_entry(id),current_revision BIGINT NOT NULL,expires_at BIGINT NOT NULL,created_at BIGINT NOT NULL);
CREATE INDEX ix_office_file ON office_session(user_id,file_id,expires_at);
ALTER TABLE office_session ADD CONSTRAINT fk_office_user FOREIGN KEY(user_id) REFERENCES sys_user(id);
ALTER TABLE office_session ADD CONSTRAINT fk_office_file FOREIGN KEY(file_id) REFERENCES drive_entry(id) ON DELETE CASCADE;
