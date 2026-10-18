CREATE TABLE ai_connection (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),name VARCHAR(60) NOT NULL,
 base_url VARCHAR(2048) NOT NULL,secret_cipher TEXT,enabled BOOLEAN NOT NULL,config_version BIGINT NOT NULL,
 last_test_status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED',created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_connection_user ON ai_connection(user_id);
CREATE TABLE ai_model (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),connection_id VARCHAR(36) NOT NULL REFERENCES ai_connection(id),
 name VARCHAR(60) NOT NULL,model_id VARCHAR(200) NOT NULL,context_limit INT NOT NULL,max_output INT NOT NULL,
 output_param_mode VARCHAR(30) NOT NULL,enabled BOOLEAN NOT NULL,config_version BIGINT NOT NULL
);
CREATE INDEX ix_ai_model_user ON ai_model(user_id,connection_id);
CREATE TABLE ai_preference (user_id VARCHAR(36) PRIMARY KEY REFERENCES sys_user(id),default_chat_model_id VARCHAR(36));
CREATE TABLE ai_consent (user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),connection_id VARCHAR(36) NOT NULL REFERENCES ai_connection(id),destination_fingerprint VARCHAR(64) NOT NULL,notice_version INT NOT NULL,accepted_at BIGINT NOT NULL,PRIMARY KEY(user_id,connection_id));
CREATE TABLE ai_task (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),kind VARCHAR(20) NOT NULL,file_id VARCHAR(36),revision BIGINT,
 model_id VARCHAR(36) NOT NULL,connection_id VARCHAR(36) NOT NULL,model_version BIGINT NOT NULL,connection_version BIGINT NOT NULL,
 model_name VARCHAR(60) NOT NULL,status VARCHAR(16) NOT NULL,stage VARCHAR(30) NOT NULL,request_hash VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL,cache_key VARCHAR(64) NOT NULL,cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 request_json TEXT NOT NULL,result_json TEXT,coverage_json TEXT,error_code VARCHAR(60),error_message VARCHAR(250),
 completed_calls INT NOT NULL DEFAULT 0,total_calls INT,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,
 UNIQUE(user_id,idempotency_key)
);
CREATE INDEX ix_ai_task_user ON ai_task(user_id,status,created_at);
CREATE TABLE ai_call (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),task_id VARCHAR(36) NOT NULL,call_index INT NOT NULL,
 model_name VARCHAR(60) NOT NULL,status VARCHAR(20) NOT NULL,input_tokens BIGINT,output_tokens BIGINT,total_tokens BIGINT,
 estimated_input BIGINT NOT NULL,latency BIGINT,error_code VARCHAR(60),created_at BIGINT NOT NULL,UNIQUE(task_id,call_index)
);
CREATE INDEX ix_ai_call_user ON ai_call(user_id,created_at);
CREATE TABLE ai_idempotency (user_id VARCHAR(36) NOT NULL REFERENCES sys_user(id),request_key VARCHAR(100) NOT NULL,request_hash VARCHAR(64) NOT NULL,task_id VARCHAR(36) NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(user_id,request_key));
