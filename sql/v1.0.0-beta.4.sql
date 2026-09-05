-- AID v1.0.0-beta.4 数据库迁移脚本。
-- 可重复执行：补齐风格快照字段与查询索引；不写入任何风格提示词正文。

SET @schema_name = DATABASE();

SELECT COUNT(*) INTO @comic_asset_hidden_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_comic_asset'
  AND COLUMN_NAME = 'hidden_style_prompt_json';
SET @ddl = IF(
    @comic_asset_hidden_exists = 0,
    'ALTER TABLE `aid_comic_asset` ADD COLUMN `hidden_style_prompt_json` JSON NULL COMMENT ''风格快照(character/scene/prop)'' AFTER `prompt_text`',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SELECT COUNT(*) INTO @user_asset_hidden_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_user_comic_asset'
  AND COLUMN_NAME = 'hidden_style_prompt_json';
SET @ddl = IF(
    @user_asset_hidden_exists = 0,
    'ALTER TABLE `aid_user_comic_asset` ADD COLUMN `hidden_style_prompt_json` JSON NULL COMMENT ''风格快照(character/scene/prop)'' AFTER `prompt_text`',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SELECT COUNT(*) INTO @project_hidden_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_comic_project'
  AND COLUMN_NAME = 'hidden_style_prompt_json';
SET @ddl = IF(
    @project_hidden_exists = 0,
    'ALTER TABLE `aid_comic_project` ADD COLUMN `hidden_style_prompt_json` JSON NULL COMMENT ''项目风格快照(character/scene/prop)'' AFTER `video_style_value`',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

ALTER TABLE `aid_comic_project`
    MODIFY COLUMN `video_style_type` varchar(100) NULL COMMENT '视频风格名称',
    MODIFY COLUMN `video_style_value` text NULL COMMENT '视频风格公开提示词快照';

SELECT COUNT(*) INTO @style_query_index_exists
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_extract_task'
  AND INDEX_NAME = 'idx_project_task_status_del';
SET @ddl = IF(
    @style_query_index_exists = 0,
    'ALTER TABLE `aid_extract_task` ADD INDEX `idx_project_task_status_del` (`project_id`, `task_type`, `status`, `del_flag`)',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
