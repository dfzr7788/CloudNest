ALTER TABLE download_job ADD entry_revisions TEXT;
UPDATE download_job SET status='FAILED',error='升级后请重新创建打包任务';
