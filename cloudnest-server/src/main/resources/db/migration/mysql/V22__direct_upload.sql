CREATE TABLE direct_upload_ticket (
 session_id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, backend VARCHAR(80) NOT NULL,
 object_key VARCHAR(180) NOT NULL, size BIGINT NOT NULL, expires_at BIGINT NOT NULL
);
