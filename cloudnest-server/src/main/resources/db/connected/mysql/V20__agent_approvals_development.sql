CREATE TABLE agent_approval (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  FOREIGN KEY(user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  request_key VARCHAR(36) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  proposal_hash VARCHAR(64) NOT NULL,
  request_json TEXT NOT NULL,
  plan_json TEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  job_ids_json TEXT,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  decided_at BIGINT,
  approver_id VARCHAR(36),
  UNIQUE(user_id,request_key)
);
CREATE INDEX agent_approval_owner ON agent_approval(user_id,created_at);
