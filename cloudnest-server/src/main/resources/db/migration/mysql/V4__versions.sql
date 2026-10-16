ALTER TABLE drive_entry ADD revision BIGINT NOT NULL DEFAULT 1;
CREATE TABLE file_version (id VARCHAR(36) PRIMARY KEY,entry_id VARCHAR(36) NOT NULL REFERENCES drive_entry(id),blob_id VARCHAR(36) NOT NULL REFERENCES file_blob(id),size BIGINT NOT NULL,mime VARCHAR(180) NOT NULL,created_at BIGINT NOT NULL);
CREATE INDEX ix_version_entry ON file_version(entry_id,created_at);
ALTER TABLE file_share ADD pinned_blob VARCHAR(36) REFERENCES file_blob(id);
UPDATE file_share s JOIN drive_entry e ON e.id=s.file_id SET s.pinned_blob=e.blob_id;
ALTER TABLE download_job ADD share_id VARCHAR(36);
