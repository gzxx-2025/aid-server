-- AID v2.1.2 数据库增量脚本（MySQL 5.7，可重复执行）。
-- 适用于从 v1.0.0-beta.1 起的官方数据库连续升级；保留用户数据、自定义配置和历史表。

-- ============================================================================
-- 早期 beta 数据库连续升级兼容修复
-- ============================================================================
-- 已发布的历史迁移按文件名和 SHA256 记账，不能依赖修改旧脚本来修正已经成功执行过的库；
-- 因此使用新的迁移 ID 补齐基础字段，并把 pending_video_url 归一到当前 aid-init.sql 基线。

SET @schema_name := DATABASE();

-- 当前服务端的上传内容安全与定时清理链路仍会读写该表；部分新安装基线曾遗漏它。
CREATE TABLE IF NOT EXISTS `aid_image_moderation_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) NULL DEFAULT NULL COMMENT '上传用户ID',
  `biz_source` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '业务来源(如storyboard_upload/common_upload)',
  `file_url` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '被审图片URL',
  `file_md5` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'IMS 回传 FileMD5',
  `suggestion` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'IMS 建议(Pass/Review/Block)',
  `label` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '命中标签',
  `sub_label` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '命中子标签',
  `score` int(11) NULL DEFAULT NULL COMMENT '命中分值',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '决策状态(PASS/BLOCK/REVIEW/ERROR)',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'IMS RequestId',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'ERROR 时的错误信息',
  `elapsed_ms` bigint(20) NULL DEFAULT NULL COMMENT '审查耗时(毫秒)',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_time`(`user_id`, `create_time`) USING BTREE,
  INDEX `idx_status_time`(`status`, `create_time`) USING BTREE,
  INDEX `idx_md5`(`file_md5`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=Dynamic COMMENT='图片内容安全审查日志';

SET @column_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='final_video_url'
);
SET @ddl := IF(@column_exists=0,
    'ALTER TABLE `aid_episode_editor` ADD COLUMN `final_video_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''成片OSS地址(导出成功后回填)''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='pending_video_url'
);
SET @ddl := IF(@column_exists=0,
    'ALTER TABLE `aid_episode_editor` ADD COLUMN `pending_video_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''待审核新成片OSS地址'' AFTER `final_video_url`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='export_fingerprint'
);
SET @ddl := IF(@column_exists=0,
    'ALTER TABLE `aid_episode_editor` ADD COLUMN `export_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''当前导出请求指纹''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='final_video_fingerprint'
);
SET @ddl := IF(@column_exists=0,
    'ALTER TABLE `aid_episode_editor` ADD COLUMN `final_video_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''正式成片素材指纹(SHA-256)'' AFTER `final_video_url`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_needs_normalize := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema=@schema_name
      AND table_name='aid_episode_editor'
      AND column_name='pending_video_url'
      AND (
          column_type <> 'varchar(1000)'
          OR is_nullable <> 'YES'
          OR column_default IS NOT NULL
          OR character_set_name <> 'utf8mb4'
          OR collation_name <> 'utf8mb4_general_ci'
      )
);
SET @ddl := IF(@column_needs_normalize>0,
    'ALTER TABLE `aid_episode_editor` MODIFY COLUMN `pending_video_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''待审核新成片OSS地址'' AFTER `final_video_fingerprint`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='pending_video_fingerprint'
);
SET @ddl := IF(@column_exists=0,
    'ALTER TABLE `aid_episode_editor` ADD COLUMN `pending_video_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''待审核成片素材指纹(SHA-256)'' AFTER `pending_video_url`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `aid_episode_editor`
SET `final_video_fingerprint`=`export_fingerprint`
WHERE `final_video_url` IS NOT NULL
  AND `final_video_url`<>''
  AND (`pending_video_url` IS NULL OR `pending_video_url`='')
  AND `final_video_fingerprint` IS NULL
  AND `export_fingerprint` IS NOT NULL;

UPDATE `aid_episode_editor`
SET `pending_video_fingerprint`=`export_fingerprint`
WHERE `pending_video_url` IS NOT NULL
  AND `pending_video_url`<>''
  AND `pending_video_fingerprint` IS NULL
  AND `export_fingerprint` IS NOT NULL;

-- ============================================================================
-- 模型池能力绑定与失效模型引用清理
-- 只清理活动模型池中已删除、不存在或类型不匹配的模型引用；保留有效模型顺序、
-- 模型配置、SKU、价格、倍率、历史任务及计费快照。
-- ============================================================================
SET NAMES utf8mb4;

SET @aid_old_group_concat_max_len := @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;

DROP TEMPORARY TABLE IF EXISTS tmp_aid_pool_seq;
CREATE TEMPORARY TABLE tmp_aid_pool_seq (
  n SMALLINT UNSIGNED NOT NULL PRIMARY KEY
) ENGINE=MEMORY;
INSERT INTO tmp_aid_pool_seq (n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds;

-- 只把可安全自动修复的数组配置放入临时表。历史库可能仍使用文本列，并留有
-- 非法 JSON、对象或超长数组；这些内容必须原样保留，不能让 JSON 函数终止整批迁移，
-- 也不能因为解析范围有限而误删它们的能力绑定。
DROP TEMPORARY TABLE IF EXISTS tmp_aid_processable_pool;
CREATE TEMPORARY TABLE tmp_aid_processable_pool (
  pool_id BIGINT NOT NULL PRIMARY KEY,
  model_ids JSON NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_aid_processable_pool (pool_id, model_ids)
SELECT config.id, CAST(config.model_ids AS JSON)
FROM aid_ai_model_func_config config
WHERE config.del_flag = '0'
  AND CASE
        WHEN JSON_VALID(config.model_ids) = 1
          THEN JSON_TYPE(CAST(config.model_ids AS JSON)) = 'ARRAY'
               AND JSON_LENGTH(CAST(config.model_ids AS JSON)) <= 1000
        ELSE FALSE
      END;

DROP TEMPORARY TABLE IF EXISTS tmp_aid_valid_pool_model;
CREATE TEMPORARY TABLE tmp_aid_valid_pool_model (
  pool_id BIGINT NOT NULL,
  model_id BIGINT NOT NULL,
  first_position SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (pool_id, model_id),
  KEY idx_tmp_pool_position (pool_id, first_position)
) ENGINE=MEMORY;

INSERT INTO tmp_aid_valid_pool_model (pool_id, model_id, first_position)
SELECT config.id,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(processable.model_ids, CONCAT('$[', seq.n, ']'))) AS UNSIGNED) AS model_id,
       MIN(seq.n) AS first_position
FROM aid_ai_model_func_config config
JOIN tmp_aid_processable_pool processable ON processable.pool_id = config.id
JOIN tmp_aid_pool_seq seq
  ON seq.n < JSON_LENGTH(processable.model_ids)
JOIN aid_ai_model model
  ON model.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(processable.model_ids, CONCAT('$[', seq.n, ']'))) AS UNSIGNED)
 AND model.del_flag = '0'
 AND model.model_type = config.model_type
WHERE config.del_flag = '0'
GROUP BY config.id,
         CAST(JSON_UNQUOTE(JSON_EXTRACT(processable.model_ids, CONCAT('$[', seq.n, ']'))) AS UNSIGNED);

DROP TEMPORARY TABLE IF EXISTS tmp_aid_clean_pool_model;
CREATE TEMPORARY TABLE tmp_aid_clean_pool_model (
  pool_id BIGINT NOT NULL PRIMARY KEY,
  model_ids LONGTEXT NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_aid_clean_pool_model (pool_id, model_ids)
SELECT pool_id,
       CONCAT('[', GROUP_CONCAT(model_id ORDER BY first_position SEPARATOR ','), ']')
FROM tmp_aid_valid_pool_model
GROUP BY pool_id;

UPDATE aid_ai_model_func_config config
JOIN tmp_aid_processable_pool processable ON processable.pool_id = config.id
LEFT JOIN tmp_aid_clean_pool_model cleaned ON cleaned.pool_id = config.id
SET config.model_ids = COALESCE(cleaned.model_ids, '[]'),
    config.update_by = 'upgrade',
    config.update_time = NOW()
WHERE config.del_flag = '0'
  AND NOT (
    JSON_LENGTH(processable.model_ids) = JSON_LENGTH(CAST(COALESCE(cleaned.model_ids, '[]') AS JSON))
    AND JSON_CONTAINS(processable.model_ids, CAST(COALESCE(cleaned.model_ids, '[]') AS JSON), '$') = 1
    AND JSON_CONTAINS(CAST(COALESCE(cleaned.model_ids, '[]') AS JSON), processable.model_ids, '$') = 1
  );

DELETE binding
FROM aid_ai_business_model_binding binding
LEFT JOIN aid_ai_model model
  ON model.id = binding.model_id
 AND model.del_flag = '0'
LEFT JOIN aid_ai_model_func_config config
  ON config.func_code = binding.func_code
 AND config.del_flag = '0'
LEFT JOIN tmp_aid_processable_pool processable
  ON processable.pool_id = config.id
WHERE model.id IS NULL
   OR config.id IS NULL
   OR model.model_type <> config.model_type
   OR (processable.pool_id IS NOT NULL
       AND JSON_CONTAINS(processable.model_ids, CAST(binding.model_id AS JSON), '$') = 0);

DROP TEMPORARY TABLE IF EXISTS tmp_aid_clean_pool_model;
DROP TEMPORARY TABLE IF EXISTS tmp_aid_valid_pool_model;
DROP TEMPORARY TABLE IF EXISTS tmp_aid_processable_pool;
DROP TEMPORARY TABLE IF EXISTS tmp_aid_pool_seq;

SET SESSION group_concat_max_len = @aid_old_group_concat_max_len;
