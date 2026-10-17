ALTER TABLE upload_session ADD target_id VARCHAR(36);
ALTER TABLE upload_session ADD target_revision BIGINT;
ALTER TABLE download_job ADD completed_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE download_job ADD total_bytes BIGINT NOT NULL DEFAULT 0;
