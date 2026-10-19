ALTER TABLE storage_node ADD failure_domain VARCHAR(80) NOT NULL DEFAULT 'unassigned';
ALTER TABLE storage_node ADD reliability INT NOT NULL DEFAULT 50;
ALTER TABLE storage_node ADD priority INT NOT NULL DEFAULT 50;
ALTER TABLE storage_node ADD cost INT NOT NULL DEFAULT 50;
UPDATE storage_node SET failure_domain='server-a',reliability=90 WHERE backend='local';
CREATE TABLE storage_placement_policy (
  id INT PRIMARY KEY, mode VARCHAR(24) NOT NULL, min_reliability INT NOT NULL
);
INSERT INTO storage_placement_policy VALUES(1,'MANUAL',0);
CREATE TABLE storage_placement_log (
  id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
  backend VARCHAR(80) NOT NULL, mode VARCHAR(24) NOT NULL,
  reason VARCHAR(2000) NOT NULL, created_at BIGINT NOT NULL
);
CREATE INDEX idx_placement_time ON storage_placement_log(created_at);
ALTER TABLE upload_session ADD replica_backend VARCHAR(80);
ALTER TABLE file_blob ADD desired_replicas INT NOT NULL DEFAULT 1;
CREATE TABLE blob_replica (
  blob_id VARCHAR(36) NOT NULL, backend VARCHAR(80) NOT NULL,
  object_key VARCHAR(100) NOT NULL, size BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED', verified_at BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(blob_id,backend)
);
CREATE INDEX idx_replica_backend ON blob_replica(backend,object_key);
-- Historical objects require a real integrity check before becoming failover candidates.
INSERT INTO blob_replica(blob_id,backend,object_key,size)
  SELECT id,backend,object_key,size FROM file_blob WHERE status='READY';
CREATE TABLE storage_job_item (
  id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
  blob_id VARCHAR(36) NOT NULL, kind VARCHAR(24) NOT NULL,
  target_backend VARCHAR(80) NOT NULL, status VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0, error VARCHAR(500),
  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX idx_storage_item_status ON storage_job_item(status,created_at);
