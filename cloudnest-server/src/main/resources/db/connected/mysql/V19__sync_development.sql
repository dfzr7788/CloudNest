CREATE TABLE sync_device (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  name VARCHAR(80) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  last_seen_at BIGINT NOT NULL DEFAULT 0,
  revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX sync_device_user ON sync_device(user_id);
CREATE TABLE sync_clock (
  user_id VARCHAR(36) PRIMARY KEY,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  generation VARCHAR(36) NOT NULL,
  seq BIGINT NOT NULL DEFAULT 0,
  floor_seq BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE sync_entry (
  user_id VARCHAR(36) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  entry_id VARCHAR(36) NOT NULL,
  change_token VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(user_id,entry_id)
);
CREATE TABLE drive_change (
  user_id VARCHAR(36) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  seq BIGINT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY(user_id,seq)
);
CREATE TABLE sync_operation (
  device_id VARCHAR(36) NOT NULL,
  FOREIGN KEY (device_id) REFERENCES sync_device(id) ON DELETE CASCADE,
  operation_id VARCHAR(36) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  result_json TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY(device_id,operation_id)
);
CREATE TABLE sync_upload (
  upload_id VARCHAR(36) PRIMARY KEY,
  device_id VARCHAR(36) NOT NULL,
  FOREIGN KEY (device_id) REFERENCES sync_device(id) ON DELETE CASCADE,
  created_at BIGINT NOT NULL
);
