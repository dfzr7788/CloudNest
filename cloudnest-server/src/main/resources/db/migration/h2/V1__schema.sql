CREATE TABLE sys_user (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 nickname VARCHAR(180) NOT NULL, root_id VARCHAR(36) NOT NULL, storage_quota BIGINT NOT NULL,
 storage_used BIGINT NOT NULL DEFAULT 0, storage_reserved BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL,
 CHECK(storage_used>=0 AND storage_reserved>=0 AND storage_used+storage_reserved<=storage_quota)
);
CREATE TABLE auth_session (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),expires_at BIGINT NOT NULL);
CREATE TABLE file_blob (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),object_key VARCHAR(180) NOT NULL UNIQUE,backend VARCHAR(16) NOT NULL,size BIGINT NOT NULL,sha256 VARCHAR(64) NOT NULL,mime VARCHAR(180) NOT NULL,status VARCHAR(20) NOT NULL,created_at BIGINT NOT NULL);
CREATE INDEX ix_blob_hash ON file_blob(user_id,sha256,size,status);
CREATE TABLE drive_entry (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),parent_id VARCHAR(36) REFERENCES drive_entry(id),kind VARCHAR(8) NOT NULL,name VARCHAR(180) NOT NULL,
 blob_id VARCHAR(36) REFERENCES file_blob(id),size BIGINT NOT NULL DEFAULT 0,mime VARCHAR(180),deleted BOOLEAN NOT NULL DEFAULT FALSE,delete_batch VARCHAR(36),deleted_at BIGINT,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,
 active_name VARCHAR(180) GENERATED ALWAYS AS (CASE WHEN deleted=FALSE THEN name ELSE NULL END),
 CONSTRAINT uq_entry_name UNIQUE(user_id,parent_id,kind,active_name),CHECK(kind IN ('FILE','FOLDER'))
);
CREATE INDEX ix_entry_parent ON drive_entry(user_id,parent_id,deleted);
CREATE INDEX ix_entry_batch ON drive_entry(user_id,delete_batch);
CREATE TABLE upload_session (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),folder_id VARCHAR(36) NOT NULL,name VARCHAR(180) NOT NULL,size BIGINT NOT NULL,sha256 VARCHAR(64) NOT NULL,
 part_size BIGINT NOT NULL,part_count INT NOT NULL,status VARCHAR(20) NOT NULL,object_key VARCHAR(180) NOT NULL,backend VARCHAR(16) NOT NULL,result_id VARCHAR(36),created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL
);
CREATE INDEX ix_upload_user ON upload_session(user_id,status,expires_at);
CREATE TABLE upload_part (session_id VARCHAR(36) NOT NULL REFERENCES upload_session(id),part_number INT NOT NULL,size BIGINT NOT NULL,sha256 VARCHAR(64) NOT NULL,PRIMARY KEY(session_id,part_number));
CREATE TABLE file_share (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),file_id VARCHAR(36) NOT NULL REFERENCES drive_entry(id),token_hash VARCHAR(64) NOT NULL UNIQUE,code_hash VARCHAR(100),allow_download BOOLEAN NOT NULL,expires_at BIGINT NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,created_at BIGINT NOT NULL);
CREATE TABLE share_grant (id VARCHAR(64) PRIMARY KEY,share_id VARCHAR(36) NOT NULL REFERENCES file_share(id),expires_at BIGINT NOT NULL);
CREATE TABLE download_job (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),status VARCHAR(16) NOT NULL,entry_ids TEXT NOT NULL,error VARCHAR(200),created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL);
