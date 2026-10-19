CREATE TABLE storage_guard (id INT PRIMARY KEY);
INSERT INTO storage_guard VALUES (1);
CREATE TABLE storage_node (
  backend VARCHAR(80) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  management VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  allow_upload BOOLEAN NOT NULL DEFAULT TRUE,
  allow_migration BOOLEAN NOT NULL DEFAULT TRUE,
  budget_bytes BIGINT NOT NULL DEFAULT 0,
  public_endpoint VARCHAR(500),
  direct_download BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE storage_node_health (
  backend VARCHAR(80) PRIMARY KEY,
  state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
  config_version BIGINT NOT NULL DEFAULT 0,
  validated BOOLEAN NOT NULL DEFAULT FALSE,
  failures INT NOT NULL DEFAULT 0,
  successes INT NOT NULL DEFAULT 0,
  checked_at BIGINT NOT NULL DEFAULT 0,
  latency_ms BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE storage_node_event (
  id VARCHAR(36) PRIMARY KEY,
  backend VARCHAR(80) NOT NULL,
  state VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX idx_node_event_time ON storage_node_event(created_at);
-- Durable reservations cover candidates left by failed/cancelled uploads and migrations.
CREATE TABLE storage_reservation (
  id VARCHAR(80) PRIMARY KEY,
  backend VARCHAR(80) NOT NULL,
  bytes BIGINT NOT NULL,
  temp_bytes BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_reservation_backend ON storage_reservation(backend);
-- Migration intentionally retains source objects. Count these physical copies in budgets.
CREATE TABLE storage_copy (
  backend VARCHAR(80) NOT NULL,
  object_key VARCHAR(100) NOT NULL,
  size BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY(backend,object_key)
);
INSERT INTO storage_node(backend,name) VALUES ('local','A 本地存储'),('minio','旧 MinIO');
INSERT INTO storage_node(backend,name,enabled) SELECT CONCAT('s3:',id),name,enabled FROM storage_policy;
INSERT INTO storage_node_health(backend) SELECT backend FROM storage_node;
INSERT INTO storage_reservation(id,backend,bytes,temp_bytes)
  SELECT id,backend,size,size*3 FROM upload_session WHERE status IN ('UPLOADING','ASSEMBLING','CANCELLED');

ALTER TABLE share_event MODIFY action VARCHAR(32) NOT NULL;

ALTER TABLE file_share ADD download_authorizations BIGINT NOT NULL DEFAULT 0;
