CREATE TABLE ai_knowledge_base (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,name VARCHAR(100) NOT NULL,
 model_id VARCHAR(36) NOT NULL,scopes_json TEXT NOT NULL,members_json TEXT NOT NULL,
 active_generation VARCHAR(36),pending_generation VARCHAR(36),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_kb_user ON ai_knowledge_base(user_id,updated_at);
CREATE TABLE ai_index_generation (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,kb_id VARCHAR(36) NOT NULL,
 dimensions INT NOT NULL,identity_hash VARCHAR(64) NOT NULL,snapshot_hash VARCHAR(64) NOT NULL,status VARCHAR(20) NOT NULL,created_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_generation_kb ON ai_index_generation(user_id,kb_id);
CREATE TABLE ai_index_chunk (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,kb_id VARCHAR(36) NOT NULL,generation_id VARCHAR(36) NOT NULL,
 file_id VARCHAR(36) NOT NULL,revision BIGINT NOT NULL,ordinal INT NOT NULL,locator VARCHAR(500) NOT NULL,
 content TEXT NOT NULL,content_hash VARCHAR(64) NOT NULL,vector_data BLOB
);
CREATE INDEX ix_ai_chunk_scope ON ai_index_chunk(user_id,kb_id,generation_id);
CREATE INDEX ix_ai_chunk_file ON ai_index_chunk(file_id);
CREATE TABLE ai_index_batch (
 id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36) NOT NULL,generation_id VARCHAR(36) NOT NULL,
 chunk_ids TEXT NOT NULL,status VARCHAR(20) NOT NULL,created_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_batch_generation ON ai_index_batch(generation_id);
CREATE TABLE ai_kb_session (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,kb_id VARCHAR(36) NOT NULL,generation_id VARCHAR(36) NOT NULL,
 model_id VARCHAR(36) NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_kb_session_user ON ai_kb_session(user_id,kb_id);
ALTER TABLE ai_task ADD COLUMN workspace_id VARCHAR(36);
CREATE INDEX ix_ai_task_workspace ON ai_task(user_id,workspace_id,created_at);

CREATE TABLE ai_task_source (
 task_id VARCHAR(36) NOT NULL,user_id VARCHAR(36) NOT NULL,file_id VARCHAR(36) NOT NULL,version_id VARCHAR(36),
 PRIMARY KEY(task_id,file_id)
);
CREATE INDEX ix_ai_task_source_file ON ai_task_source(user_id,file_id);
CREATE TABLE ai_organization_applied (
 task_id VARCHAR(36) NOT NULL,file_id VARCHAR(36) NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(task_id,file_id)
);
