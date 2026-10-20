CREATE TABLE folder_snapshot (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, folder_id VARCHAR(36) NOT NULL,
 name VARCHAR(180) NOT NULL, created_at BIGINT NOT NULL, total_bytes BIGINT NOT NULL, entry_count INT NOT NULL
);
CREATE INDEX ix_snapshot_owner ON folder_snapshot(user_id,folder_id,created_at);
CREATE TABLE folder_snapshot_entry (
 snapshot_id VARCHAR(36) NOT NULL REFERENCES folder_snapshot(id), entry_id VARCHAR(36) NOT NULL,
 parent_entry_id VARCHAR(36), ordinal INT NOT NULL, kind VARCHAR(8) NOT NULL, name VARCHAR(180) NOT NULL,
 blob_id VARCHAR(36) REFERENCES file_blob(id), size BIGINT NOT NULL, mime VARCHAR(180), revision BIGINT NOT NULL,
 PRIMARY KEY(snapshot_id,entry_id), UNIQUE(snapshot_id,ordinal)
);
CREATE INDEX ix_snapshot_blob ON folder_snapshot_entry(blob_id);
CREATE TABLE snapshot_retention_policy (
 user_id VARCHAR(36) NOT NULL, folder_id VARCHAR(36) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 interval_hours INT NOT NULL DEFAULT 24, keep_count INT NOT NULL DEFAULT 30, next_run BIGINT NOT NULL,
 protection VARCHAR(16) NOT NULL DEFAULT 'NORMAL', last_error VARCHAR(100),
 PRIMARY KEY(user_id,folder_id)
);
CREATE TABLE resilience_task (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, kind VARCHAR(24) NOT NULL,
 request_key VARCHAR(80) NOT NULL, request_scope VARCHAR(64), status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 progress INT NOT NULL DEFAULT 0, total INT NOT NULL, attempts INT NOT NULL DEFAULT 0,
 error_code VARCHAR(80), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(user_id,kind,request_key)
);
CREATE INDEX ix_resilience_queue ON resilience_task(status,created_at);
CREATE TABLE snapshot_restore_job (
 task_id VARCHAR(36) PRIMARY KEY REFERENCES resilience_task(id), snapshot_id VARCHAR(36) NOT NULL REFERENCES folder_snapshot(id),
 target_folder VARCHAR(36) NOT NULL, selected_entry VARCHAR(36), restored_root VARCHAR(36)
);
CREATE TABLE snapshot_restore_entry (
 task_id VARCHAR(36) NOT NULL REFERENCES resilience_task(id), entry_id VARCHAR(36) NOT NULL,
 restored_id VARCHAR(36) NOT NULL, PRIMARY KEY(task_id,entry_id)
);
CREATE TABLE integrity_scan_item (
 task_id VARCHAR(36) NOT NULL REFERENCES resilience_task(id), ordinal INT NOT NULL, blob_id VARCHAR(36) NOT NULL,
 backend VARCHAR(80) NOT NULL, object_key VARCHAR(180) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 error_code VARCHAR(80), repair_task VARCHAR(36), checked_at BIGINT,
 PRIMARY KEY(task_id,ordinal)
);
CREATE TABLE integrity_scan_policy (
 id INT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE, interval_hours INT NOT NULL DEFAULT 168,
 next_run BIGINT NOT NULL DEFAULT 0, bytes_per_second BIGINT NOT NULL DEFAULT 8388608,
 administrator_id VARCHAR(36), last_error VARCHAR(80)
);
INSERT INTO integrity_scan_policy(id) VALUES(1);
CREATE TABLE team_workspace (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(180) NOT NULL, owner_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
 storage_user VARCHAR(36) NOT NULL UNIQUE REFERENCES sys_user(id), created_at BIGINT NOT NULL
);
CREATE TABLE team_member (
 team_id VARCHAR(36) NOT NULL REFERENCES team_workspace(id), user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),
 file_role VARCHAR(16) NOT NULL, contributor BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(team_id,user_id)
);
CREATE TABLE team_invitation (
 id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL REFERENCES team_workspace(id), token_hash VARCHAR(64) NOT NULL UNIQUE,
 file_role VARCHAR(16) NOT NULL, contributor BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, consumed_by VARCHAR(36), revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE team_acl (
 team_id VARCHAR(36) NOT NULL REFERENCES team_workspace(id), entry_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id), permission VARCHAR(16) NOT NULL,
 PRIMARY KEY(team_id,entry_id,user_id)
);
CREATE TABLE team_audit (
 id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL REFERENCES team_workspace(id), actor_id VARCHAR(36) NOT NULL,
 action VARCHAR(40) NOT NULL, target_id VARCHAR(80), created_at BIGINT NOT NULL
);
CREATE TABLE team_upload_actor (
 session_id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL
);
CREATE TABLE storage_contribution (
 id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL REFERENCES team_workspace(id), contributor_id VARCHAR(36) NOT NULL,
 name VARCHAR(180) NOT NULL, backend VARCHAR(80), endpoint VARCHAR(500) NOT NULL, bucket VARCHAR(180) NOT NULL,
 access_key TEXT NOT NULL, secret_key TEXT NOT NULL, capacity_bytes BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', failure_domain VARCHAR(80), created_at BIGINT NOT NULL,
 last_heartbeat BIGINT NOT NULL DEFAULT 0, token_hash VARCHAR(64) NOT NULL
);
ALTER TABLE storage_node ADD performance_class VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE storage_node ADD supports_range BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE storage_node ADD public_upload BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_user ADD workspace_account BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE file_blob ALTER COLUMN backend VARCHAR(80);
ALTER TABLE upload_session ALTER COLUMN backend VARCHAR(80);
ALTER TABLE maintenance_job ALTER COLUMN source_backend VARCHAR(80);
ALTER TABLE maintenance_job ALTER COLUMN target_backend VARCHAR(80);
