CREATE TABLE ai_conversation (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,file_id VARCHAR(36) NOT NULL,revision BIGINT NOT NULL,
 model_id VARCHAR(36) NOT NULL,connection_id VARCHAR(36) NOT NULL,model_version BIGINT NOT NULL,connection_version BIGINT NOT NULL,
 model_name VARCHAR(60) NOT NULL,destination VARCHAR(2048) NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_conversation_user ON ai_conversation(user_id,file_id,updated_at);
ALTER TABLE ai_task ADD COLUMN conversation_id VARCHAR(36);
CREATE INDEX ix_ai_task_conversation ON ai_task(conversation_id,created_at);
CREATE TABLE ai_message (
 id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(36) NOT NULL,task_id VARCHAR(36) NOT NULL,role VARCHAR(12) NOT NULL,
 content TEXT NOT NULL,created_at BIGINT NOT NULL
);
CREATE INDEX ix_ai_message_conversation ON ai_message(conversation_id,created_at);
CREATE TABLE ai_citation (
 id VARCHAR(36) PRIMARY KEY,message_id VARCHAR(36) NOT NULL,source_id VARCHAR(40) NOT NULL,
 locator VARCHAR(500) NOT NULL,excerpt TEXT NOT NULL
);
CREATE INDEX ix_ai_citation_message ON ai_citation(message_id);
