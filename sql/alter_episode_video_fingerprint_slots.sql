-- 成片正式槽与待审槽分别记录内容指纹，避免同一成片重复进入审核。
SET @schema_name = DATABASE();

SELECT COUNT(*) INTO @final_fingerprint_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_episode_editor'
  AND COLUMN_NAME = 'final_video_fingerprint';
SET @ddl = IF(@final_fingerprint_exists = 0,
    'ALTER TABLE aid_episode_editor ADD COLUMN final_video_fingerprint varchar(64) NULL COMMENT ''正式成片素材指纹(SHA-256)'' AFTER final_video_url',
    'SELECT 1');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SELECT COUNT(*) INTO @pending_fingerprint_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_episode_editor'
  AND COLUMN_NAME = 'pending_video_fingerprint';
SET @ddl = IF(@pending_fingerprint_exists = 0,
    'ALTER TABLE aid_episode_editor ADD COLUMN pending_video_fingerprint varchar(64) NULL COMMENT ''待审成片素材指纹(SHA-256)'' AFTER pending_video_url',
    'SELECT 1');
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 历史数据只在槽位归属明确时回填，避免把待审指纹误记到已发布旧片。
UPDATE aid_episode_editor
SET final_video_fingerprint = export_fingerprint
WHERE final_video_url IS NOT NULL
  AND final_video_url <> ''
  AND (pending_video_url IS NULL OR pending_video_url = '')
  AND final_video_fingerprint IS NULL
  AND export_fingerprint IS NOT NULL;

UPDATE aid_episode_editor
SET pending_video_fingerprint = export_fingerprint
WHERE pending_video_url IS NOT NULL
  AND pending_video_url <> ''
  AND pending_video_fingerprint IS NULL
  AND export_fingerprint IS NOT NULL;
