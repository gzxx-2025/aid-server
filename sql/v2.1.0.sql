-- AID v2.1.0 数据库升级脚本
-- 适用范围：从已发布稳定版 v2.0.1 升级到 v2.1.0。
-- 兼容 MySQL 5.7；按下列顺序执行；所有迁移均应支持重复执行。
-- 执行前请完整备份数据库，并在维护窗口完成升级。

-- ============================================================================
-- 010-case-plaza.sql
-- ============================================================================

-- v2.1.0：案例广场、作品审核发布、发布快照预览与工程复制。
-- MySQL 5.7；可重复执行；不修改已有发布状态、权限开关或项目内容。

SET NAMES utf8mb4;
SET @schema_name := DATABASE();

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_episode' AND column_name='pending_comic_title');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_episode` ADD COLUMN `pending_comic_title` varchar(100) NULL DEFAULT NULL COMMENT ''待审核单集标题'' AFTER `comic_title`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_episode' AND column_name='pending_comic_desc');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_episode` ADD COLUMN `pending_comic_desc` varchar(500) NULL DEFAULT NULL COMMENT ''待审核单集描述'' AFTER `comic_desc`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_episode' AND column_name='pending_comic_cover_url');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_episode` ADD COLUMN `pending_comic_cover_url` varchar(500) NULL DEFAULT NULL COMMENT ''待审核单集封面'' AFTER `comic_cover_url`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='pending_project_name');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `pending_project_name` varchar(100) NULL DEFAULT NULL COMMENT ''待审核项目名称'' AFTER `project_name`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='pending_project_desc');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `pending_project_desc` varchar(500) NULL DEFAULT NULL COMMENT ''待审核项目描述'' AFTER `project_desc`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='pending_cover_url');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `pending_cover_url` varchar(500) NULL DEFAULT NULL COMMENT ''待审核项目封面'' AFTER `cover_url`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='is_public');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `is_public` char(1) NULL DEFAULT ''0'' COMMENT ''是否公开'' AFTER `status_reason`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='publish_time');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `publish_time` datetime NULL DEFAULT NULL COMMENT ''最近一次公开发布时间'' AFTER `remark`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='allow_preview');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `allow_preview` char(1) NOT NULL DEFAULT ''0'' COMMENT ''是否允许预览已发布流程：0否 1是'' AFTER `publish_time`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='allow_copy');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `allow_copy` char(1) NOT NULL DEFAULT ''0'' COMMENT ''是否允许复制已发布流程：0否 1是'' AFTER `allow_preview`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='published_snapshot_id');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `published_snapshot_id` bigint(20) NULL DEFAULT NULL COMMENT ''当前审核通过的流程快照ID'' AFTER `allow_copy`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='source_project_id');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `source_project_id` bigint(20) NULL DEFAULT NULL COMMENT ''复制来源项目ID'' AFTER `published_snapshot_id`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='source_snapshot_id');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `source_snapshot_id` bigint(20) NULL DEFAULT NULL COMMENT ''复制来源发布快照ID'' AFTER `source_project_id`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='copy_label');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `copy_label` varchar(32) NULL DEFAULT NULL COMMENT ''复制来源标签'' AFTER `source_snapshot_id`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND column_name='copy_count');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_comic_project` ADD COLUMN `copy_count` bigint(20) NOT NULL DEFAULT 0 COMMENT ''累计复制次数'' AFTER `copy_label`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='pending_video_url');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_episode_editor` ADD COLUMN `pending_video_url` varchar(1000) NULL DEFAULT NULL COMMENT ''待审核新成片OSS地址'' AFTER `final_video_fingerprint`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_episode_editor' AND column_name='pending_video_fingerprint');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_episode_editor` ADD COLUMN `pending_video_fingerprint` varchar(64) NULL DEFAULT NULL COMMENT ''待审核成片素材指纹(SHA-256)'' AFTER `pending_video_url`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_user_profile' AND column_name='publish_enabled');
SET @ddl := IF(@column_exists=0, 'ALTER TABLE `aid_user_profile` ADD COLUMN `publish_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT ''作品发布权限(1允许 0禁止)'' AFTER `balance_reminder_available`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `aid_comic_audit_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `target_type` varchar(20) NOT NULL COMMENT '审核对象类型(project项目 episode剧集)',
  `target_id` bigint(20) NOT NULL COMMENT '审核对象ID',
  `owner_user_id` bigint(20) NULL DEFAULT NULL COMMENT '作品所属用户ID',
  `action` tinyint(4) NOT NULL COMMENT '审核动作(1提交 2通过 3驳回)',
  `before_status` tinyint(4) NULL DEFAULT NULL COMMENT '变更前状态',
  `after_status` tinyint(4) NULL DEFAULT NULL COMMENT '变更后状态',
  `audit_reason` varchar(500) NULL DEFAULT NULL COMMENT '审核意见',
  `operator` varchar(64) NULL DEFAULT NULL COMMENT '操作人',
  `del_flag` char(1) NULL DEFAULT '0' COMMENT '删除标志',
  `create_by` varchar(64) NULL DEFAULT '',
  `create_time` datetime NULL DEFAULT NULL,
  `update_by` varchar(64) NULL DEFAULT '',
  `update_time` datetime NULL DEFAULT NULL,
  `remark` varchar(500) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='作品审核记录表';

CREATE TABLE IF NOT EXISTS `aid_publish_whitelist` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `remark` varchar(255) NULL DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `update_by` varchar(64) NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_publish_whitelist_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='作品发布白名单';

CREATE TABLE IF NOT EXISTS `aid_project_publish_snapshot` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '快照ID',
  `project_id` bigint(20) NOT NULL COMMENT '项目ID',
  `owner_user_id` bigint(20) NOT NULL COMMENT '发布时项目所属用户ID',
  `revision_no` int(11) NOT NULL COMMENT '项目内递增版本号',
  `schema_version` int(11) NOT NULL COMMENT '快照文档结构版本',
  `content_hash` char(64) NOT NULL COMMENT '快照JSON的SHA-256',
  `snapshot_json` longtext NOT NULL COMMENT '不可变流程快照文档',
  `node_count` int(11) NOT NULL DEFAULT 0 COMMENT '快照流程节点数',
  `create_time` datetime NOT NULL COMMENT '审核通过并固化时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '固化操作人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_snapshot_revision` (`project_id`,`revision_no`),
  KEY `idx_project_snapshot_owner` (`owner_user_id`,`create_time`,`id`),
  KEY `idx_project_snapshot_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='项目审核通过流程快照';

CREATE TABLE IF NOT EXISTS `aid_project_copy_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '复制操作用户ID',
  `request_id` varchar(64) NOT NULL COMMENT '客户端幂等请求标识',
  `source_project_id` bigint(20) NOT NULL COMMENT '来源项目ID',
  `source_snapshot_id` bigint(20) NOT NULL COMMENT '实际复制的来源快照ID',
  `target_project_id` bigint(20) NOT NULL COMMENT '新建项目ID',
  `create_time` datetime NOT NULL COMMENT '复制时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_copy_request` (`user_id`,`request_id`),
  KEY `idx_project_copy_source` (`source_project_id`,`source_snapshot_id`,`create_time`,`id`),
  KEY `idx_project_copy_target` (`target_project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='项目复制幂等与来源记录';

CREATE TABLE IF NOT EXISTS `aid_project_publish_snapshot_media` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `snapshot_id` bigint(20) NOT NULL COMMENT '发布快照ID',
  `media_url_hash` char(64) NOT NULL COMMENT '规范化媒体地址SHA-256',
  `media_url` varchar(2000) NOT NULL COMMENT '规范化媒体地址',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_media` (`snapshot_id`,`media_url_hash`),
  KEY `idx_snapshot_media_url` (`media_url`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发布快照媒体引用索引';

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND index_name='idx_project_published_snapshot');
SET @ddl := IF(@index_exists=0, 'ALTER TABLE `aid_comic_project` ADD INDEX `idx_project_published_snapshot` (`published_snapshot_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='aid_comic_project' AND index_name='idx_project_copy_source');
SET @ddl := IF(@index_exists=0, 'ALTER TABLE `aid_comic_project` ADD INDEX `idx_project_copy_source` (`source_project_id`,`source_snapshot_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `aid_config`
  (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`)
SELECT 'basic', 'work_publish_enabled', 'true', '作品发布', '0', 16, NOW(), 'system', 'system', NOW(), '案例广场作品发布总开关', 0
WHERE NOT EXISTS (
  SELECT 1 FROM `aid_config` WHERE `category`='basic' AND `config_name`='work_publish_enabled' AND `tenant_id`=0
);

-- ============================================================================
-- 020-tokendance-accounts.sql
-- ============================================================================

-- TokenDance 账户 OAuth、服务端凭证版本与充值会话
-- MySQL 5.7+；可重复执行。

CREATE TABLE IF NOT EXISTS `aid_tokendance_oauth_authorization` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '授权记录ID',
  `authorization_ref` varchar(64) NOT NULL COMMENT '后台授权流程引用',
  `provider_id` bigint(20) NOT NULL COMMENT 'TokenDance供应商ID',
  `admin_user_id` bigint(20) NOT NULL COMMENT '发起授权的管理员ID',
  `authorization_mode` varchar(16) NOT NULL COMMENT 'CALLBACK或HEADLESS',
  `verifier_value` text NULL COMMENT '仅服务端保存的PKCE verifier，终态清除',
  `callback_url` varchar(1024) NULL COMMENT '当前后台管理回调地址',
  `app_url` varchar(512) NOT NULL COMMENT 'TokenDance应用归因地址',
  `key_name` varchar(80) NOT NULL COMMENT '授权页展示的Key名称',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/EXCHANGING/SUCCEEDED/FAILED/EXPIRED',
  `credential_version` int(11) NULL COMMENT '成功绑定的凭证版本',
  `failure_code` varchar(64) NULL COMMENT '脱敏失败编码',
  `expires_at` datetime NOT NULL COMMENT '授权码交换截止时间',
  `exchanged_at` datetime NULL COMMENT '交换完成时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL COMMENT '更新时间',
  `remark` varchar(500) NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_td_oauth_ref` (`authorization_ref`),
  KEY `idx_td_oauth_binding` (`provider_id`, `admin_user_id`, `status`),
  KEY `idx_td_oauth_expire` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TokenDance API Key OAuth授权过程';

CREATE TABLE IF NOT EXISTS `aid_tokendance_credential` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '凭证记录ID',
  `provider_id` bigint(20) NOT NULL COMMENT 'TokenDance供应商ID',
  `credential_version` int(11) NOT NULL COMMENT '供应商内递增凭证版本',
  `admin_user_id` bigint(20) NOT NULL COMMENT '授权管理员ID',
  `credential_value` text NOT NULL COMMENT '仅服务端可访问的API Key（原文保存）',
  `credential_fingerprint` char(64) NOT NULL COMMENT 'API Key不可逆摘要',
  `credential_hint` varchar(16) NOT NULL COMMENT 'API Key脱敏尾号',
  `source_type` varchar(16) NOT NULL DEFAULT 'OAUTH' COMMENT '凭证来源',
  `status` varchar(16) NOT NULL COMMENT 'ACTIVE/RETIRED/REVOKED',
  `authorized_at` datetime NOT NULL COMMENT '授权成功时间',
  `retired_at` datetime NULL COMMENT '被新版本替换时间',
  `revoked_at` datetime NULL COMMENT '管理员显式撤销时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL COMMENT '更新时间',
  `remark` varchar(500) NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_td_credential_version` (`provider_id`, `credential_version`),
  KEY `idx_td_credential_active` (`provider_id`, `status`, `credential_version`),
  KEY `idx_td_credential_fingerprint` (`credential_fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TokenDance服务端凭证版本';

CREATE TABLE IF NOT EXISTS `aid_tokendance_payment_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '本地充值会话ID',
  `provider_id` bigint(20) NOT NULL COMMENT 'TokenDance供应商ID',
  `credential_version` int(11) NOT NULL COMMENT '创建会话使用的凭证版本',
  `admin_user_id` bigint(20) NOT NULL COMMENT '发起充值的管理员ID',
  `request_hash` char(64) NOT NULL COMMENT '前端幂等请求编号摘要',
  `upstream_session_id` varchar(191) NULL COMMENT 'TokenDance充值会话ID',
  `amount` int(11) NOT NULL COMMENT '充值金额，单位元',
  `status` varchar(24) NOT NULL COMMENT '本地或TokenDance会话状态',
  `user_confirmed_at` datetime NOT NULL COMMENT '付款人金额确认时间',
  `payment_url` varchar(2048) NULL COMMENT 'PC聚合码内容',
  `alipay_url` varchar(2048) NULL COMMENT '移动端支付宝深链',
  `status_url` varchar(2048) NULL COMMENT '经官方域名白名单校验的状态地址',
  `upstream_created_at` datetime NULL COMMENT 'TokenDance会话创建时间',
  `expired_at` datetime NULL COMMENT 'TokenDance会话过期时间',
  `paid_at` datetime NULL COMMENT 'TokenDance确认到账时间',
  `last_query_time` datetime NULL COMMENT '最近状态查询时间',
  `balance_refresh_status` varchar(16) NULL COMMENT '到账后唯一余额刷新状态',
  `balance_refreshed_at` datetime NULL COMMENT '到账后余额刷新完成时间',
  `last_error_code` varchar(64) NULL COMMENT '脱敏上游失败编码',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL COMMENT '更新时间',
  `remark` varchar(500) NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_td_payment_request` (`provider_id`, `admin_user_id`, `request_hash`),
  UNIQUE KEY `uk_td_payment_upstream` (`upstream_session_id`),
  KEY `idx_td_payment_status` (`provider_id`, `status`, `expired_at`),
  KEY `idx_td_payment_admin` (`admin_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TokenDance管理员充值会话';

-- ============================================================================
-- 030-model-configuration-version.sql
-- ============================================================================

-- v2.1.0：模型管理并发保存版本；兼容 MySQL 5.7，可重复执行。
SET @aid_model_config_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_ai_model' AND COLUMN_NAME = 'config_version'
);
SET @aid_model_config_ddl = IF(@aid_model_config_column_exists = 0,
    'ALTER TABLE aid_ai_model ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0 COMMENT ''模型管理配置版本''',
    'SELECT 1');
PREPARE aid_model_config_stmt FROM @aid_model_config_ddl;
EXECUTE aid_model_config_stmt;
DEALLOCATE PREPARE aid_model_config_stmt;

-- ============================================================================
-- 040-provider-task-route-snapshot.sql
-- ============================================================================

-- v2.1.0：供应商任务路由与凭证版本快照。兼容 MySQL 5.7，可重复执行。
SET @aid_route_snapshot_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_media_task' AND COLUMN_NAME = 'provider_route_snapshot_json');
SET @aid_route_snapshot_ddl = IF(@aid_route_snapshot_exists = 0,
 'ALTER TABLE aid_media_task ADD COLUMN provider_route_snapshot_json MEDIUMTEXT NULL COMMENT ''供应商路由及凭证版本快照（无密钥）''', 'SELECT 1');
PREPARE aid_route_snapshot_stmt FROM @aid_route_snapshot_ddl;
EXECUTE aid_route_snapshot_stmt;
DEALLOCATE PREPARE aid_route_snapshot_stmt;

-- ============================================================================
-- 050-verified-model-capabilities.sql
-- ============================================================================

-- v2.1.0：按供应商官方资料补齐公开模型的可验证能力边界。
-- 依赖：030-model-configuration-version.sql。
--
-- 安全约束：
-- 1. 只匹配公开供应商代码与精确模型代码；
-- 2. 只补 capability_json 中缺失的顶层字段，已有站长配置始终优先；
-- 3. 非法或非对象 JSON 不自动修复，留给管理端显式处理；
-- 4. 不修改状态、倍率、计费规则、模型池、网关或供应商凭证。

-- 万相 2.6 图片编辑：提示词、参考图格式/大小/边长与 Base64 能力。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 2000,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','jpg','png','bmp','webp'),
        '$.referenceImageMaxFileSizeMb', 10,
        '$.referenceImageMinDimensionPixels', 240,
        '$.referenceImageMaxDimensionPixels', 8000,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'dashscope'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'wan2.6-image'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- 万相 2.2 首尾帧视频：首帧必填/尾帧可选由既有场景配置表达，此处补素材硬限制。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 800,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','jpg','png','bmp','webp'),
        '$.referenceImageMaxFileSizeMb', 10,
        '$.referenceImageMinDimensionPixels', 240,
        '$.referenceImageMaxDimensionPixels', 8000,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$'),
        '$.outputFormatOptions', JSON_ARRAY('mp4'),
        '$.defaultOutputFormat', 'mp4'
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'dashscope'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'wan2.2-kf2v-flash'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.outputFormatOptions') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.defaultOutputFormat') = 0);

-- 万相 2.7 视频编辑：恰好一段视频及最多四张图已配置，补齐每项素材限制。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 5000,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','jpg','png','bmp','webp'),
        '$.referenceImageMaxFileSizeMb', 20,
        '$.referenceImageMinDimensionPixels', 240,
        '$.referenceImageMaxDimensionPixels', 8000,
        '$.referenceImageMinAspectRatio', 0.125,
        '$.referenceImageMaxAspectRatio', 8.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$'),
        '$.referenceVideoFormats', JSON_ARRAY('mp4','mov'),
        '$.referenceVideoMaxFileSizeMb', 100,
        '$.referenceVideoMinDurationSeconds', 2,
        '$.referenceVideoMaxDurationSeconds', 10,
        '$.referenceVideoMaxTotalDurationSeconds', 10,
        '$.referenceVideoMinDimensionPixels', 240,
        '$.referenceVideoMaxDimensionPixels', 4096,
        '$.referenceVideoMinAspectRatio', 0.125,
        '$.referenceVideoMaxAspectRatio', 8.0,
        '$.outputFormatOptions', JSON_ARRAY('mp4'),
        '$.defaultOutputFormat', 'mp4'
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'dashscope'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'wan2.7-videoedit'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxTotalDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.outputFormatOptions') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.defaultOutputFormat') = 0);

-- HappyHorse 参考生视频：官方的语言分段提示词上限无法由单一字段无损表达，故只补素材限制。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','jpg','png','webp'),
        '$.referenceImageMaxFileSizeMb', 20,
        '$.referenceImageMinDimensionPixels', 400,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$'),
        '$.outputFormatOptions', JSON_ARRAY('mp4'),
        '$.defaultOutputFormat', 'mp4'
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'dashscope'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'happyhorse-1.0-r2v'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.outputFormatOptions') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.defaultOutputFormat') = 0);

-- Seedream 5.0 Pro：参考图通用限制；单张参考图宽高乘积不超过 36000000 px。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png','webp','bmp','tiff','gif','heic','heif'),
        '$.referenceImageMaxFileSizeMb', 30,
        '$.referenceImageMinDimensionPixels', 15,
        '$.referenceImageMaxPixels', 36000000,
        '$.referenceImageMinAspectRatio', 0.0625,
        '$.referenceImageMaxAspectRatio', 16.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'volcengine'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'doubao-seedream-5-0-pro-260628'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- Seedance 2.0 / Fast：图片、视频、音频的单项与总时长硬限制。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png','webp','bmp','tiff','gif','heic','heif'),
        '$.referenceImageMaxFileSizeMb', 30,
        '$.referenceImageMinDimensionPixels', 300,
        '$.referenceImageMaxDimensionPixels', 6000,
        '$.referenceImageMinAspectRatio', 0.4,
        '$.referenceImageMaxAspectRatio', 2.5,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$'),
        '$.supportsVideoInput', JSON_EXTRACT('true', '$'),
        '$.maxReferenceVideos', 3,
        '$.referenceVideoFormats', JSON_ARRAY('mp4','mov'),
        '$.referenceVideoMaxFileSizeMb', 200,
        '$.referenceVideoMinDurationSeconds', 2,
        '$.referenceVideoMaxDurationSeconds', 15,
        '$.referenceVideoMaxTotalDurationSeconds', 15,
        '$.referenceVideoMinDimensionPixels', 300,
        '$.referenceVideoMaxDimensionPixels', 6000,
        '$.referenceVideoMinAspectRatio', 0.4,
        '$.referenceVideoMaxAspectRatio', 2.5,
        '$.referenceVideoMinFps', 24.0,
        '$.referenceVideoMaxFps', 60.0,
        '$.referenceAudioMaxFileSizeMb', 15,
        '$.outputFormatOptions', JSON_ARRAY('mp4'),
        '$.defaultOutputFormat', 'mp4'
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'volcengine'
  AND p.`del_flag` = '0'
  AND m.`model_code` IN ('doubao-seedance-2.0','doubao-seedance-2.0-fast')
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsVideoInput') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxReferenceVideos') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxTotalDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinFps') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxFps') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceAudioMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.outputFormatOptions') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.defaultOutputFormat') = 0);

-- 即梦图片 3.1 / 4.0 / 4.6：提示词上限及可精确表达的参考图限制。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 800
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'jimeng'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'jimeng-image-3.1'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0;

UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 800,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png'),
        '$.referenceImageMaxFileSizeMb', 15,
        '$.referenceImageMaxDimensionPixels', 4096,
        '$.referenceImageMinAspectRatio', 0.3333333333,
        '$.referenceImageMaxAspectRatio', 3.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'jimeng'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'jimeng-image-4.0'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 800,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png'),
        '$.referenceImageMaxFileSizeMb', 15,
        '$.referenceImageMaxDimensionPixels', 4096,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'jimeng'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'jimeng-image-4.6'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- 即梦智能超清：4.7MB 的传输方式适用范围尚未结构化，因此不无差别写入通用上限。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png'),
        '$.referenceImageMaxDimensionPixels', 4096,
        '$.referenceImageMinAspectRatio', 0.3333333333,
        '$.referenceImageMaxAspectRatio', 3.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'jimeng'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'jimeng-image-ultra'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- 即梦视频 3.0 系列：官方提示词上限；4.7MB 图片限制等待传输方式契约。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 800,
        '$.referenceImageFormats', JSON_ARRAY('jpeg','png'),
        '$.referenceImageMinDimensionPixels', 320,
        '$.referenceImageMaxDimensionPixels', 4096,
        '$.referenceImageMinAspectRatio', 0.3333333333,
        '$.referenceImageMaxAspectRatio', 3.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'jimeng'
  AND p.`del_flag` = '0'
  AND m.`model_code` IN ('jimeng-video-3.0','jimeng-video-3.0-pro')
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- Vidu 普通图片输入接口：按不同接口的公开边界分组补齐。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('png','jpeg','jpg','webp'),
        '$.referenceImageMaxFileSizeMb', 50,
        '$.referenceImageMinAspectRatio', 0.25,
        '$.referenceImageMaxAspectRatio', 4.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'vidu'
  AND p.`del_flag` = '0'
  AND m.`model_code` IN ('vidu-q3-pro-img2video','vidu-q3-pro-startend2video','vidu-q2-pro-multiframe')
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('png','jpeg','jpg','webp'),
        '$.referenceImageMaxFileSizeMb', 50,
        '$.referenceImageMinDimensionPixels', 128,
        '$.referenceImageMinAspectRatio', 0.25,
        '$.referenceImageMaxAspectRatio', 4.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'vidu'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'vidu-q3-mix-reference2video'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- Vidu 主体参考接口：这里只补通用格式/比例；主体与图片分组数量等待专属字段。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('png','jpeg','jpg','webp'),
        '$.referenceImageMinAspectRatio', 0.25,
        '$.referenceImageMaxAspectRatio', 4.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'vidu'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'vidu-q3-reference2video'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- Vidu Q2 参考生图：提示词、图片格式/大小/边长和比例。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.maxPromptCharacters', 2000,
        '$.referenceImageFormats', JSON_ARRAY('png','jpeg','jpg','webp'),
        '$.referenceImageMaxFileSizeMb', 50,
        '$.referenceImageMinDimensionPixels', 128,
        '$.referenceImageMinAspectRatio', 0.25,
        '$.referenceImageMaxAspectRatio', 4.0,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'vidu'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'viduq2'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.maxPromptCharacters') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- 可灵 3.0 Turbo：图片输入格式与大小。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpg','jpeg','png'),
        '$.referenceImageMaxFileSizeMb', 50
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'kling'
  AND p.`del_flag` = '0'
  AND m.`model_code` = 'kling-3.0-turbo-i2v'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0);

-- 可灵 3.0 Omni 图片输入；素材组合数量由既有 referenceVideoRules 表达。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceImageFormats', JSON_ARRAY('jpg','jpeg','png'),
        '$.referenceImageMaxFileSizeMb', 50,
        '$.referenceImageMinDimensionPixels', 300,
        '$.referenceImageMinAspectRatio', 0.4,
        '$.referenceImageMaxAspectRatio', 2.5,
        '$.supportsBase64Image', JSON_EXTRACT('true', '$')
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'kling'
  AND p.`del_flag` = '0'
  AND m.`model_code` IN ('kling-3.0-omni-i2v','kling-3.0-omni-first-last','kling-3.0-omni-reference','kling-3.0-omni-feature-video','kling-3.0-omni-edit')
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceImageMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.supportsBase64Image') = 0);

-- 可灵 3.0 Omni 视频输入：精确保留官方 15.5 秒单段及总时长上限。
UPDATE `aid_ai_model` AS m
JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
SET m.`capability_json` = JSON_INSERT(
        m.`capability_json`,
        '$.referenceVideoFormats', JSON_ARRAY('mp4','mov'),
        '$.referenceVideoMaxFileSizeMb', 200,
        '$.referenceVideoMinDurationSeconds', 3,
        '$.referenceVideoMaxDurationSeconds', 15.5,
        '$.referenceVideoMaxTotalDurationSeconds', 15.5,
        '$.referenceVideoMinDimensionPixels', 700,
        '$.referenceVideoMaxDimensionPixels', 4553,
        '$.referenceVideoMinAspectRatio', 0.4,
        '$.referenceVideoMaxAspectRatio', 2.0,
        '$.referenceVideoMinFps', 24.0,
        '$.referenceVideoMaxFps', 60.0
    ),
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1
WHERE p.`provider_code` = 'kling'
  AND p.`del_flag` = '0'
  AND m.`model_code` IN ('kling-3.0-omni-feature-video','kling-3.0-omni-edit')
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`capability_json`) = 1
  AND JSON_TYPE(m.`capability_json`) = 'OBJECT'
  AND (JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoFormats') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxFileSizeMb') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxTotalDurationSeconds') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxDimensionPixels') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxAspectRatio') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMinFps') = 0
    OR JSON_CONTAINS_PATH(m.`capability_json`, 'one', '$.referenceVideoMaxFps') = 0);

-- ============================================================================
-- 060-tokendance-default-provider.sql
-- ============================================================================

-- v2.1.0：内置 TokenDance 推荐供应商。MySQL 5.7 幂等。
-- 仅补入缺失记录，不覆盖已有网关、凭证、状态、调度和模型配置。
-- 默认停用，完成账户授权、模型导入与配置核验后由管理员显式启用。
-- 已软删除的同编码记录保留原状，不自动恢复管理员删除的数据。
INSERT INTO `aid_ai_provider`
  (`provider_name`, `provider_code`, `base_url`, `api_key`, `auth_header`, `auth_prefix`,
   `official_doc_url`, `status`, `del_flag`, `create_time`, `create_by`, `remark`, `supports_callback`)
SELECT 'TokenDance', 'tokendance', 'https://tokendance.space', '', 'Authorization', 'Bearer ',
       'https://tokendance.space/docs/ai-integration', '1', '0', NOW(), 'system',
       '推荐多模型供应商；从模型管理推荐区授权并选择导入模型。', 0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `aid_ai_provider` WHERE LOWER(TRIM(`provider_code`)) = 'tokendance'
);

-- ============================================================================
-- 070-tokendance-sku-priority.sql
-- ============================================================================

-- v2.1.0：修复早期 TokenDance 目录编译器产生的全 0 SKU 优先级。
-- MySQL 5.7 幂等；仅处理目录绑定模型，不覆盖已编号或已自定义的 SKU。

DROP PROCEDURE IF EXISTS `aid_fix_tokendance_sku_priority`;

DELIMITER $$

CREATE PROCEDURE `aid_fix_tokendance_sku_priority`()
BEGIN
  DECLARE v_done int DEFAULT 0;
  DECLARE v_model_id bigint;
  DECLARE v_rule longtext;
  DECLARE v_original_rule longtext;
  DECLARE v_sku_count int DEFAULT 0;
  DECLARE v_index int DEFAULT 0;
  DECLARE v_all_zero tinyint DEFAULT 1;
  DECLARE v_priority varchar(64);

  DECLARE model_cursor CURSOR FOR
    SELECT m.`id`, m.`billing_rule_json`
    FROM `aid_ai_model` m
    INNER JOIN `aid_ai_provider` p ON p.`id` = m.`provider_id`
    WHERE LOWER(TRIM(p.`provider_code`)) = 'tokendance'
      AND m.`del_flag` = '0'
      AND m.`billing_mode` = 'SKU'
      AND JSON_VALID(m.`billing_rule_json`) = 1
      AND JSON_VALID(m.`capability_json`) = 1
      AND JSON_CONTAINS_PATH(
        IF(JSON_VALID(m.`capability_json`) = 1, m.`capability_json`, '{}'),
        'all', '$.catalogModelId', '$.catalogProtocol'
      ) = 1
      AND JSON_TYPE(JSON_EXTRACT(
        IF(JSON_VALID(m.`billing_rule_json`) = 1, m.`billing_rule_json`, '{}'), '$.skus'
      )) = 'ARRAY'
      AND JSON_LENGTH(JSON_EXTRACT(
        IF(JSON_VALID(m.`billing_rule_json`) = 1, m.`billing_rule_json`, '{}'), '$.skus'
      )) > 0;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN model_cursor;
  model_loop: LOOP
    FETCH model_cursor INTO v_model_id, v_rule;
    IF v_done = 1 THEN
      LEAVE model_loop;
    END IF;

    SET v_original_rule = v_rule;
    SET v_sku_count = JSON_LENGTH(JSON_EXTRACT(v_rule, '$.skus'));
    SET v_index = 0;
    SET v_all_zero = 1;

    WHILE v_index < v_sku_count DO
      SET v_priority = JSON_UNQUOTE(JSON_EXTRACT(
        v_rule, CONCAT('$.skus[', v_index, '].priority')));
      IF v_priority IS NULL OR v_priority <> '0' THEN
        SET v_all_zero = 0;
      END IF;
      SET v_index = v_index + 1;
    END WHILE;

    IF v_all_zero = 1 THEN
      SET v_index = 0;
      WHILE v_index < v_sku_count DO
        SET v_rule = JSON_SET(
          v_rule,
          CONCAT('$.skus[', v_index, '].priority'),
          v_index + 1
        );
        SET v_index = v_index + 1;
      END WHILE;

      UPDATE `aid_ai_model`
      SET `billing_rule_json` = v_rule,
          `config_version` = COALESCE(`config_version`, 0) + 1,
          `update_time` = NOW()
      WHERE `id` = v_model_id
        AND `billing_rule_json` = v_original_rule;
    END IF;
  END LOOP;
  CLOSE model_cursor;
END$$

DELIMITER ;

CALL `aid_fix_tokendance_sku_priority`();
DROP PROCEDURE IF EXISTS `aid_fix_tokendance_sku_priority`;

-- ============================================================================
-- 080-tokendance-credential-storage.sql
-- ============================================================================

-- v2.1.0：统一 TokenDance OAuth 流程与凭证存储字段，并同步当前活跃 Key 到通用供应商配置。
-- MySQL 5.7 幂等。旧 credential_cipher 表必须为空；发现历史密文时明确中止，禁止把密文当作 API Key。

DROP PROCEDURE IF EXISTS `aid_migrate_tokendance_credential_storage`;

DELIMITER $$

CREATE PROCEDURE `aid_migrate_tokendance_credential_storage`()
BEGIN
  DECLARE v_table_exists int DEFAULT 0;
  DECLARE v_value_column_exists int DEFAULT 0;
  DECLARE v_cipher_column_exists int DEFAULT 0;
  DECLARE v_row_count bigint DEFAULT 0;
  DECLARE v_oauth_table_exists int DEFAULT 0;
  DECLARE v_verifier_value_exists int DEFAULT 0;
  DECLARE v_verifier_cipher_exists int DEFAULT 0;
  DECLARE v_flow_hash_exists int DEFAULT 0;
  DECLARE v_flow_hash_index_exists int DEFAULT 0;
  DECLARE v_oauth_row_count bigint DEFAULT 0;

  SELECT COUNT(*) INTO v_oauth_table_exists
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_oauth_authorization';

  IF v_oauth_table_exists = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '请先执行 020-tokendance-accounts.sql';
  END IF;

  SELECT COUNT(*) INTO v_table_exists
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_credential';

  IF v_table_exists = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '请先执行 020-tokendance-accounts.sql';
  END IF;

  SELECT COUNT(*) INTO v_value_column_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_credential'
    AND COLUMN_NAME = 'credential_value';

  SELECT COUNT(*) INTO v_cipher_column_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_credential'
    AND COLUMN_NAME = 'credential_cipher';

  SELECT COUNT(*) INTO v_row_count FROM `aid_tokendance_credential`;

  -- 所有 DDL 之前先拦截不能安全自动迁移的历史密文，避免脚本只完成一半。
  IF v_value_column_exists = 0 AND v_cipher_column_exists = 1 AND v_row_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '旧 TokenDance 加密凭证表非空，请先人工确认并重新授权';
  END IF;
  IF v_value_column_exists = 0 AND v_cipher_column_exists = 0 AND v_row_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TokenDance 凭证表结构异常且存在数据，请先人工处理';
  END IF;

  SELECT COUNT(*) INTO v_verifier_value_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_oauth_authorization'
    AND COLUMN_NAME = 'verifier_value';

  SELECT COUNT(*) INTO v_verifier_cipher_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_oauth_authorization'
    AND COLUMN_NAME = 'verifier_cipher';

  SELECT COUNT(*) INTO v_flow_hash_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_oauth_authorization'
    AND COLUMN_NAME = 'flow_token_hash';

  SELECT COUNT(*) INTO v_flow_hash_index_exists
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'aid_tokendance_oauth_authorization'
    AND INDEX_NAME = 'uk_td_oauth_flow_hash';

  SELECT COUNT(*) INTO v_oauth_row_count FROM `aid_tokendance_oauth_authorization`;

  IF v_verifier_value_exists = 0 AND v_verifier_cipher_exists = 1 AND v_oauth_row_count = 0 THEN
    ALTER TABLE `aid_tokendance_oauth_authorization`
      CHANGE COLUMN `verifier_cipher` `verifier_value` text NULL
      COMMENT '仅服务端保存的PKCE verifier，终态清除';
    SET v_verifier_cipher_exists = 0;
  ELSEIF v_verifier_value_exists = 0 THEN
    ALTER TABLE `aid_tokendance_oauth_authorization`
      ADD COLUMN `verifier_value` text NULL
      COMMENT '仅服务端保存的PKCE verifier，终态清除' AFTER `authorization_mode`;
  END IF;

  -- 非空旧表中的加密 verifier 无法在取消部署密钥后恢复；只终止未完成的短时流程，
  -- 保留已终态历史记录。旧字段改为可空，避免新 OAuth 流程 INSERT 被无关旧列拦截。
  IF v_verifier_cipher_exists = 1 THEN
    IF v_oauth_row_count = 0 THEN
      ALTER TABLE `aid_tokendance_oauth_authorization`
        DROP COLUMN `verifier_cipher`;
    ELSE
      ALTER TABLE `aid_tokendance_oauth_authorization`
        MODIFY COLUMN `verifier_cipher` text NULL
        COMMENT '历史加密PKCE verifier（不再读取）';
      UPDATE `aid_tokendance_oauth_authorization`
      SET `status` = 'EXPIRED',
          `failure_code` = 'LEGACY_FLOW_UNUSABLE',
          `update_by` = 'system',
          `update_time` = NOW()
      WHERE `status` IN ('PENDING', 'EXCHANGING')
        AND `verifier_value` IS NULL;
    END IF;
  END IF;

  IF v_flow_hash_exists = 1 THEN
    IF v_oauth_row_count = 0 THEN
      IF v_flow_hash_index_exists = 1 THEN
        ALTER TABLE `aid_tokendance_oauth_authorization`
          DROP INDEX `uk_td_oauth_flow_hash`,
          DROP COLUMN `flow_token_hash`;
      ELSE
        ALTER TABLE `aid_tokendance_oauth_authorization`
          DROP COLUMN `flow_token_hash`;
      END IF;
    ELSE
      ALTER TABLE `aid_tokendance_oauth_authorization`
        MODIFY COLUMN `flow_token_hash` char(64) NULL
        COMMENT '历史匿名回调令牌摘要（不再读取）';
    END IF;
  END IF;

  IF v_value_column_exists = 0 AND v_cipher_column_exists = 1 THEN
    ALTER TABLE `aid_tokendance_credential`
      CHANGE COLUMN `credential_cipher` `credential_value` text NOT NULL
      COMMENT '仅服务端可访问的API Key（原文保存）';
  ELSEIF v_value_column_exists = 0 THEN
    ALTER TABLE `aid_tokendance_credential`
      ADD COLUMN `credential_value` text NOT NULL
      COMMENT '仅服务端可访问的API Key（原文保存）' AFTER `admin_user_id`;
  ELSEIF v_cipher_column_exists = 1 THEN
    -- 兼容曾经只“新增新字段”但未移除旧 NOT NULL 字段的中间状态，避免后续 OAuth INSERT
    -- 因未提供 credential_cipher 而失败。空表直接移除；非空表只放宽旧列并保留人工处置空间。
    IF v_row_count = 0 THEN
      ALTER TABLE `aid_tokendance_credential`
        DROP COLUMN `credential_cipher`;
    ELSE
      ALTER TABLE `aid_tokendance_credential`
        MODIFY COLUMN `credential_cipher` text NULL
        COMMENT '历史加密凭证（不再读取）';
    END IF;
  END IF;
END$$

DELIMITER ;

CALL `aid_migrate_tokendance_credential_storage`();
DROP PROCEDURE IF EXISTS `aid_migrate_tokendance_credential_storage`;

-- 已按新字段保存过凭证但供应商镜像为空的环境，补齐最新活跃版本；不创建凭证、不输出 Key。
UPDATE `aid_ai_provider` p
INNER JOIN `aid_tokendance_credential` c
  ON c.`provider_id` = p.`id`
 AND c.`status` = 'ACTIVE'
LEFT JOIN `aid_tokendance_credential` newer
  ON newer.`provider_id` = c.`provider_id`
 AND newer.`status` = 'ACTIVE'
 AND newer.`credential_version` > c.`credential_version`
SET p.`api_key` = c.`credential_value`,
    p.`update_time` = NOW(),
    p.`update_by` = 'system'
WHERE LOWER(TRIM(p.`provider_code`)) = 'tokendance'
  AND newer.`id` IS NULL
  AND TRIM(c.`credential_value`) <> ''
  AND (p.`api_key` IS NULL OR p.`api_key` <> c.`credential_value`);

-- ============================================================================
-- 100-model-capability-bindings.sql
-- ============================================================================

-- v2.1.0：模型能力、调用协议与业务绑定。
-- MySQL 5.7；只增加结构，已有模型和任务在核验迁移前保持原状。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS aid_ai_model_capability (
  id BIGINT NOT NULL AUTO_INCREMENT,
  model_id BIGINT NOT NULL,
  capability_code VARCHAR(96) NOT NULL,
  generate_mode VARCHAR(32) NOT NULL,
  definition_json MEDIUMTEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NULL,
  create_by VARCHAR(64) DEFAULT '',
  update_time DATETIME NULL,
  update_by VARCHAR(64) DEFAULT '',
  remark VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_capability (model_id, capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS aid_ai_model_protocol_binding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  model_id BIGINT NOT NULL,
  capability_code VARCHAR(96) NOT NULL,
  binding_code VARCHAR(96) NOT NULL,
  protocol VARCHAR(96) NOT NULL,
  definition_json MEDIUMTEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NULL,
  create_by VARCHAR(64) DEFAULT '',
  update_time DATETIME NULL,
  update_by VARCHAR(64) DEFAULT '',
  remark VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_capability_binding (model_id, capability_code, binding_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS aid_ai_model_alias (
  id BIGINT NOT NULL AUTO_INCREMENT,
  legacy_model_id BIGINT NOT NULL,
  legacy_model_code VARCHAR(100) NOT NULL,
  model_id BIGINT NOT NULL,
  capability_code VARCHAR(96) NULL,
  binding_code VARCHAR(96) NULL,
  create_time DATETIME NULL,
  create_by VARCHAR(64) DEFAULT '',
  update_time DATETIME NULL,
  update_by VARCHAR(64) DEFAULT '',
  remark VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_legacy_model_id (legacy_model_id),
  UNIQUE KEY uk_legacy_model_code (legacy_model_code),
  KEY idx_alias_target (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS aid_ai_business_model_binding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  func_code VARCHAR(100) NOT NULL,
  model_id BIGINT NOT NULL,
  capability_code VARCHAR(96) NOT NULL,
  default_capability TINYINT(1) NOT NULL DEFAULT 0,
  defaults_json TEXT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NULL,
  create_by VARCHAR(64) DEFAULT '',
  update_time DATETIME NULL,
  update_by VARCHAR(64) DEFAULT '',
  remark VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_model_capability (func_code, model_id, capability_code),
  KEY idx_business_model (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;


CREATE TABLE IF NOT EXISTS aid_ai_model_migration (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_key VARCHAR(80) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  affected_models INT NOT NULL,
  before_json MEDIUMTEXT NOT NULL,
  after_json MEDIUMTEXT NOT NULL,
  create_time DATETIME NULL,
  create_by VARCHAR(64) DEFAULT '',
  update_time DATETIME NULL,
  update_by VARCHAR(64) DEFAULT '',
  remark VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_migration_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- ============================================================================
-- 110-tokendance-capability-consistency.sql
-- ============================================================================

-- v2.1.0：TokenDance 已导入模型的能力一致性修复。
-- MySQL 5.7；先执行 030-model-configuration-version.sql 和 100-model-capability-bindings.sql。
-- 仅修复目录模型的已知缺项/矛盾，不修改价格、SKU、倍率、状态、凭证或业务池。
-- 执行前备份涉及的模型、能力及协议绑定；维护窗口暂停后台配置编辑，并在完成后重启服务清除配置缓存。
-- 重复执行不会追加参数或反复递增配置版本；JSON 非法的记录不作空对象覆盖。
SET NAMES utf8mb4;
START TRANSACTION;
SELECT m.id FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id
WHERE p.provider_code='tokendance' AND m.del_flag='0'
AND m.real_model_code IN ('minimax-h3-max','seedream-5.0-lite','qwen3.7-plus')
FOR UPDATE;

CREATE TEMPORARY TABLE td_capability_repair (
  kind VARCHAR(12) NOT NULL, id BIGINT NOT NULL, model_id BIGINT NOT NULL,
  model_key VARCHAR(255) NOT NULL, protocol VARCHAR(96) NOT NULL,
  old_doc JSON NOT NULL, doc JSON NOT NULL,
  PRIMARY KEY(kind,id)
) ENGINE=InnoDB;
INSERT INTO td_capability_repair
SELECT 'model',m.id,m.id,m.real_model_code,m.protocol,CAST(m.capability_json AS JSON),CAST(m.capability_json AS JSON)
FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id
WHERE p.provider_code='tokendance' AND m.del_flag='0'
AND m.real_model_code IN ('minimax-h3-max','seedream-5.0-lite','qwen3.7-plus')
AND JSON_VALID(m.capability_json)
AND JSON_UNQUOTE(JSON_EXTRACT(m.capability_json,'$.catalogModelId'))=m.real_model_code;

INSERT INTO td_capability_repair
SELECT 'binding',b.id,m.id,JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.capability.catalogModelId')),
b.protocol,CAST(JSON_EXTRACT(b.definition_json,'$.capability') AS JSON),CAST(JSON_EXTRACT(b.definition_json,'$.capability') AS JSON)
FROM aid_ai_model_protocol_binding b JOIN aid_ai_model m ON m.id=b.model_id
JOIN aid_ai_provider p ON p.id=m.provider_id
WHERE p.provider_code='tokendance' AND m.del_flag='0' AND JSON_VALID(b.definition_json)
AND JSON_TYPE(JSON_EXTRACT(b.definition_json,'$.capability'))='OBJECT'
AND JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.capability.catalogModelId'))
IN ('minimax-h3-max','seedream-5.0-lite','qwen3.7-plus');

-- 只填缺项；未知扩展和已有合法的自定义选项保持原样。
UPDATE td_capability_repair r JOIN aid_ai_model m ON r.kind='model' AND m.id=r.id
SET r.doc=JSON_SET(r.doc,'$.defaultAspectRatio',m.default_aspect_ratio)
WHERE r.model_key='minimax-h3-max' AND r.protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(r.doc,'one','$.defaultAspectRatio')=0
AND m.default_aspect_ratio IS NOT NULL AND m.default_aspect_ratio<>'';
UPDATE td_capability_repair r JOIN aid_ai_model_protocol_binding b ON r.kind='binding' AND b.id=r.id
SET r.doc=JSON_SET(r.doc,'$.defaultAspectRatio',JSON_EXTRACT(b.definition_json,'$.presentation.defaultAspectRatio'))
WHERE r.model_key='minimax-h3-max' AND r.protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(r.doc,'one','$.defaultAspectRatio')=0
AND JSON_TYPE(JSON_EXTRACT(b.definition_json,'$.presentation.defaultAspectRatio'))='STRING'
AND JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.presentation.defaultAspectRatio'))<>'';
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.sceneRules',JSON_OBJECT())
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.sceneRules')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.aspectRatioOptions',CAST('["16:9","21:9","4:3","1:1","3:4","9:16"]' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.aspectRatioOptions')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.defaultAspectRatio',CAST('"16:9"' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.defaultAspectRatio')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.inputImageRole',CAST('"first_frame"' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.inputImageRole')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.allowedScenes',CAST('["textToVideo","imageToVideo","startEndToVideo"]' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.allowedScenes')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.sceneRules.textToVideo',CAST('{"requiredInputs":["text"],"allowedInputs":["text"]}' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.sceneRules.textToVideo')=0 AND JSON_TYPE(JSON_EXTRACT(doc,'$.sceneRules'))='OBJECT';
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.sceneRules.imageToVideo',CAST('{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"aspectRatioFollowInput":true}' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.sceneRules.imageToVideo')=0 AND JSON_TYPE(JSON_EXTRACT(doc,'$.sceneRules'))='OBJECT';
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.sceneRules.startEndToVideo',CAST('{"requiredInputs":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"aspectRatioFollowInput":true}' AS JSON))
WHERE model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2'
AND JSON_CONTAINS_PATH(doc,'one','$.sceneRules.startEndToVideo')=0 AND JSON_TYPE(JSON_EXTRACT(doc,'$.sceneRules'))='OBJECT';
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.minOutputPixels',CAST('3686400' AS JSON))
WHERE model_key='seedream-5.0-lite' AND protocol='tokendance:openai:image-generations'
AND JSON_CONTAINS_PATH(doc,'one','$.minOutputPixels')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.maxOutputPixels',CAST('16777216' AS JSON))
WHERE model_key='seedream-5.0-lite' AND protocol='tokendance:openai:image-generations'
AND JSON_CONTAINS_PATH(doc,'one','$.maxOutputPixels')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.aspectRatioOptions',CAST('["1:1","4:3","3:4","16:9","9:16","3:2","2:3","21:9"]' AS JSON))
WHERE model_key='seedream-5.0-lite' AND protocol='tokendance:openai:image-generations'
AND JSON_CONTAINS_PATH(doc,'one','$.aspectRatioOptions')=0;

-- 只有旧配置未声明比例选项时，修正目录遗漏造成的关闭开关。
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.supportsAspectRatio',CAST('true' AS JSON))
WHERE ((model_key='minimax-h3-max' AND protocol='tokendance:minimax:video_generation_v2')
OR (model_key='seedream-5.0-lite' AND protocol='tokendance:openai:image-generations'))
AND JSON_CONTAINS_PATH(old_doc,'one','$.aspectRatioOptions')=0;
UPDATE td_capability_repair SET doc=JSON_SET(doc,'$.supportsVideoInput',CAST('true' AS JSON))
WHERE model_key='qwen3.7-plus' AND protocol='tokendance:anthropic:messages'
AND JSON_CONTAINS(JSON_EXTRACT(doc,'$.inputModalities'),'"VIDEO"')
AND JSON_CONTAINS(JSON_EXTRACT(doc,'$.allowedInputs'),'"video"')
AND JSON_UNQUOTE(JSON_EXTRACT(doc,'$.supportsVideoInput'))='false';

-- 写回协议自身能力，避免旧模型列已修复但新能力绑定仍使用旧值。
UPDATE aid_ai_model_protocol_binding b JOIN td_capability_repair r ON r.kind='binding' AND r.id=b.id
SET b.definition_json=JSON_SET(b.definition_json,'$.capability',r.doc),
b.update_time=NOW(),b.update_by='capability-repair'
WHERE NOT(r.doc <=> r.old_doc);

-- 可视化展示与参数只补缺项；不重建整组参数和不修改现有规则。
UPDATE aid_ai_model_protocol_binding b JOIN td_capability_repair r ON r.kind='binding' AND r.id=b.id
SET b.definition_json=JSON_SET(b.definition_json,'$.presentation.supportsAspectRatio',CAST('true' AS JSON)),
b.update_time=NOW(),b.update_by='capability-repair'
WHERE JSON_UNQUOTE(JSON_EXTRACT(r.doc,'$.supportsAspectRatio'))='true'
AND JSON_CONTAINS_PATH(r.old_doc,'one','$.aspectRatioOptions')=0
AND JSON_TYPE(JSON_EXTRACT(b.definition_json,'$.presentation'))='OBJECT';

CREATE TEMPORARY TABLE td_definition_repair (
 id BIGINT PRIMARY KEY, model_id BIGINT NOT NULL, model_key VARCHAR(255) NOT NULL,
 old_doc JSON NOT NULL, doc JSON NOT NULL
) ENGINE=InnoDB;
INSERT INTO td_definition_repair
SELECT DISTINCT c.id,c.model_id,r.model_key,CAST(c.definition_json AS JSON),CAST(c.definition_json AS JSON)
FROM aid_ai_model_capability c
JOIN aid_ai_model_protocol_binding b ON b.model_id=c.model_id AND b.capability_code=c.capability_code
JOIN td_capability_repair r ON r.kind='binding' AND r.id=b.id
WHERE JSON_VALID(c.definition_json) AND NOT(r.doc <=> r.old_doc)
AND JSON_UNQUOTE(JSON_EXTRACT(r.doc,'$.supportsAspectRatio'))='true'
AND JSON_CONTAINS_PATH(r.old_doc,'one','$.aspectRatioOptions')=0;

UPDATE td_definition_repair SET doc=JSON_SET(doc,'$.presentation',JSON_OBJECT())
WHERE JSON_CONTAINS_PATH(doc,'one','$.presentation')=0;
UPDATE td_definition_repair SET doc=JSON_SET(doc,'$.presentation.supportsAspectRatio',CAST('true' AS JSON))
WHERE JSON_TYPE(JSON_EXTRACT(doc,'$.presentation'))='OBJECT';
UPDATE td_definition_repair SET doc=JSON_SET(doc,'$.parameters',JSON_ARRAY())
WHERE JSON_CONTAINS_PATH(doc,'one','$.parameters')=0;
UPDATE td_definition_repair SET doc=JSON_ARRAY_APPEND(doc,'$.parameters',
JSON_OBJECT('name','aspectRatio','label','画面比例','type','string',
'choices',JSON_ARRAY('16:9','21:9','4:3','1:1','3:4','9:16')))
WHERE model_key='minimax-h3-max' AND JSON_TYPE(JSON_EXTRACT(doc,'$.parameters'))='ARRAY'
AND JSON_SEARCH(doc,'one','aspectRatio',NULL,'$.parameters[*].name') IS NULL;

-- 图片比例放在 options；已有 options 分组保留全部属性。
-- 使用 JSON_SEARCH 定位稳定字段名，不以旧数组下标绑定参数。
UPDATE td_definition_repair SET doc=JSON_ARRAY_APPEND(doc,'$.parameters',
JSON_OBJECT('name','options','label','生成参数','type','object','defaultValue',JSON_OBJECT(),
'properties',JSON_ARRAY(JSON_OBJECT('name','aspectRatio','label','画面比例','type','string',
'choices',JSON_ARRAY('1:1','4:3','3:4','16:9','9:16','3:2','2:3','21:9')))))
WHERE model_key='seedream-5.0-lite' AND JSON_TYPE(JSON_EXTRACT(doc,'$.parameters'))='ARRAY'
AND JSON_SEARCH(doc,'one','options',NULL,'$.parameters[*].name') IS NULL;
UPDATE td_definition_repair
SET doc=JSON_ARRAY_APPEND(doc,
REPLACE(JSON_UNQUOTE(JSON_SEARCH(doc,'one','options',NULL,'$.parameters[*].name')),'.name','.properties'),
JSON_OBJECT('name','aspectRatio','label','画面比例','type','string',
'choices',JSON_ARRAY('1:1','4:3','3:4','16:9','9:16','3:2','2:3','21:9')))
WHERE model_key='seedream-5.0-lite'
AND JSON_SEARCH(doc,'one','aspectRatio',NULL,'$.parameters[*].properties[*].name') IS NULL
AND JSON_TYPE(JSON_EXTRACT(doc,
REPLACE(JSON_UNQUOTE(JSON_SEARCH(doc,'one','options',NULL,'$.parameters[*].name')),'.name','.properties')))='ARRAY';

UPDATE aid_ai_model_capability c JOIN td_definition_repair r ON r.id=c.id
SET c.definition_json=r.doc,c.update_time=NOW(),c.update_by='capability-repair'
WHERE NOT(r.doc <=> r.old_doc);

UPDATE aid_ai_model m JOIN td_capability_repair r ON r.kind='model' AND r.id=m.id
SET m.capability_json=r.doc,
m.supports_aspect_ratio=IF(JSON_CONTAINS_PATH(r.old_doc,'one','$.aspectRatioOptions')=0
AND JSON_UNQUOTE(JSON_EXTRACT(r.doc,'$.supportsAspectRatio'))='true',1,m.supports_aspect_ratio),
m.default_aspect_ratio=IF(r.model_key='minimax-h3-max' AND (m.default_aspect_ratio IS NULL OR m.default_aspect_ratio=''),
'16:9',m.default_aspect_ratio)
WHERE NOT(r.doc <=> r.old_doc);

UPDATE aid_ai_model m JOIN (
 SELECT DISTINCT model_id FROM td_capability_repair WHERE NOT(doc <=> old_doc)
) changed ON changed.model_id=m.id
SET m.config_version=COALESCE(m.config_version,0)+1,m.update_time=NOW(),m.update_by='capability-repair';

SELECT DISTINCT model_id AS repaired_model_id FROM td_capability_repair WHERE NOT(doc <=> old_doc);
DROP TEMPORARY TABLE td_definition_repair;
DROP TEMPORARY TABLE td_capability_repair;
COMMIT;

-- ============================================================================
-- 120-tokendance-video-capability-routing.sql
-- ============================================================================

-- v2.1.0：修复 TokenDance 已导入视频模型的默认能力路由。
-- MySQL 5.7；先执行 030-model-configuration-version.sql。
-- 仅修复目录导入且仍保留旧错误默认值的 HappyHorse/Kling 模型；不修改 SKU、费率、倍率、状态、凭证或模型池。
-- 重复执行不会反复递增配置版本；非法 JSON 和管理员已经调整过的场景配置保持原样。
SET NAMES utf8mb4;
START TRANSACTION;

CREATE TEMPORARY TABLE td_video_route_repair (
  id BIGINT PRIMARY KEY,
  model_key VARCHAR(255) NOT NULL,
  protocol VARCHAR(96) NOT NULL,
  old_doc JSON NOT NULL,
  doc JSON NOT NULL,
  old_generate_mode VARCHAR(64),
  new_generate_mode VARCHAR(64),
  old_first TINYINT,
  new_first TINYINT,
  old_last TINYINT,
  new_last TINYINT
) ENGINE=InnoDB;

INSERT INTO td_video_route_repair
SELECT m.id,m.real_model_code,m.protocol,
       CAST(m.capability_json AS JSON),CAST(m.capability_json AS JSON),
       m.generate_mode,m.generate_mode,m.supports_first_frame,m.supports_first_frame,
       m.supports_last_frame,m.supports_last_frame
FROM aid_ai_model m
JOIN aid_ai_provider p ON p.id=m.provider_id
WHERE p.provider_code='tokendance' AND m.del_flag='0'
AND JSON_VALID(m.capability_json)
AND JSON_UNQUOTE(JSON_EXTRACT(m.capability_json,'$.catalogModelId'))=m.real_model_code
AND (
  (m.real_model_code IN ('happyhorse-1.0-i2v','happyhorse-1.1-i2v')
   AND m.protocol='tokendance:happyhorse:video-synthesis')
  OR
  (m.real_model_code IN ('kling-3.0','kling-3.0-turbo')
   AND m.protocol='tokendance:kling:image2video')
  OR
  (m.real_model_code='kling-3.0-omni'
   AND m.protocol='tokendance:kling:omni-video')
);

-- HappyHorse 图生视频只接受一张首帧，画幅跟随输入图，不传独立比例。
UPDATE td_video_route_repair
SET doc=JSON_SET(doc,
    '$.videoScenario','first_frame',
    '$.inputImageRole','first_frame',
    '$.allowedScenes',CAST('["imageToVideo"]' AS JSON),
    '$.strictSceneRules',CAST('true' AS JSON),
    '$.sceneRules',CAST('{"imageToVideo":{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"supportsAspectRatio":false}}' AS JSON)),
    new_generate_mode='image_to_video',new_first=1,new_last=0
WHERE model_key IN ('happyhorse-1.0-i2v','happyhorse-1.1-i2v')
AND old_generate_mode='text_to_video'
AND JSON_CONTAINS_PATH(old_doc,'one','$.videoScenario')=0
AND JSON_CONTAINS_PATH(old_doc,'one','$.sceneRules')=0;

-- Kling 3.0 标准版支持首帧及首尾帧；Turbo 目录协议只声明首帧。
UPDATE td_video_route_repair
SET doc=JSON_SET(doc,
    '$.supportsFirstFrame',CAST('true' AS JSON),
    '$.supportsLastFrame',CAST('true' AS JSON),
    '$.supportsAspectRatio',CAST('false' AS JSON),
    '$.allowedScenes',CAST('["imageToVideo","startEndToVideo"]' AS JSON),
    '$.strictSceneRules',CAST('true' AS JSON),
    '$.sceneRules',CAST('{"imageToVideo":{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"supportsAspectRatio":false},"startEndToVideo":{"requiredInputs":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsAspectRatio":false}}' AS JSON)),
    new_generate_mode='image_to_video',new_first=1,new_last=1
WHERE model_key='kling-3.0' AND old_generate_mode='text_to_video'
AND JSON_CONTAINS_PATH(old_doc,'one','$.sceneRules')=0;

UPDATE td_video_route_repair
SET doc=JSON_SET(doc,
    '$.supportsFirstFrame',CAST('true' AS JSON),
    '$.supportsLastFrame',CAST('false' AS JSON),
    '$.supportsAspectRatio',CAST('false' AS JSON),
    '$.allowedScenes',CAST('["imageToVideo"]' AS JSON),
    '$.strictSceneRules',CAST('true' AS JSON),
    '$.sceneRules',CAST('{"imageToVideo":{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"supportsAspectRatio":false}}' AS JSON)),
    new_generate_mode='image_to_video',new_first=1,new_last=0
WHERE model_key='kling-3.0-turbo' AND old_generate_mode='text_to_video'
AND JSON_CONTAINS_PATH(old_doc,'one','$.sceneRules')=0;

-- Omni 视频参考场景沿用输入视频画幅，协议不接受独立 aspect_ratio。
UPDATE td_video_route_repair
SET doc=JSON_SET(doc,'$.sceneRules.videoToVideo.supportsAspectRatio',CAST('false' AS JSON))
WHERE model_key='kling-3.0-omni'
AND JSON_TYPE(JSON_EXTRACT(doc,'$.sceneRules.videoToVideo'))='OBJECT'
AND JSON_CONTAINS_PATH(doc,'one','$.sceneRules.videoToVideo.supportsAspectRatio')=0;

UPDATE aid_ai_model m
JOIN td_video_route_repair r ON r.id=m.id
SET m.capability_json=r.doc,
    m.generate_mode=r.new_generate_mode,
    m.supports_first_frame=r.new_first,
    m.supports_last_frame=r.new_last,
    m.config_version=COALESCE(m.config_version,0)+1,
    m.update_time=NOW(),
    m.update_by='capability-repair'
WHERE NOT(r.doc <=> r.old_doc)
   OR NOT(r.new_generate_mode <=> r.old_generate_mode)
   OR NOT(r.new_first <=> r.old_first)
   OR NOT(r.new_last <=> r.old_last);

SELECT id AS repaired_model_id,model_key,protocol
FROM td_video_route_repair
WHERE NOT(doc <=> old_doc)
   OR NOT(new_generate_mode <=> old_generate_mode)
   OR NOT(new_first <=> old_first)
   OR NOT(new_last <=> old_last);

DROP TEMPORARY TABLE td_video_route_repair;
COMMIT;

-- ============================================================================
-- 130-provider-model-regression.sql
-- ============================================================================

-- v2.1.0：模型能力边界、协议路由与视频 Token 预估补齐。
-- MySQL 5.7；只修复官方硬约束或缺失字段，不覆盖站长自定义密钥、网关、倍率、状态与 SKU 单价。
SET NAMES utf8mb4;

-- 万相 2.6 图片编辑：官方显式宽高总像素为 768*768～2048*2048，宽高比为 1:4～4:1。
UPDATE aid_ai_model
SET capability_json = JSON_SET(capability_json, '$.sceneRules.textToImage', JSON_OBJECT())
WHERE model_code = 'wan2.6-image'
  AND JSON_VALID(capability_json)
  AND JSON_TYPE(JSON_EXTRACT(capability_json, '$.sceneRules')) = 'OBJECT'
  AND JSON_CONTAINS_PATH(capability_json, 'one', '$.sceneRules.textToImage') = 0;

UPDATE aid_ai_model
SET capability_json = JSON_SET(
      capability_json,
      '$.minOutputPixels', 589824,
      '$.maxOutputPixels', 4194304,
      '$.minOutputAspectRatio', 0.25,
      '$.maxOutputAspectRatio', 4,
      '$.sceneRules.imageToImage.minOutputPixels', 589824,
      '$.sceneRules.imageToImage.maxOutputPixels', 4194304,
      '$.sceneRules.imageToImage.minOutputAspectRatio', 0.25,
      '$.sceneRules.imageToImage.maxOutputAspectRatio', 4,
      '$.sceneRules.textToImage.minOutputPixels', 589824,
      '$.sceneRules.textToImage.maxOutputPixels', 1638400,
      '$.sceneRules.textToImage.minOutputAspectRatio', 0.25,
      '$.sceneRules.textToImage.maxOutputAspectRatio', 4
    ),
    config_version = config_version + 1,
    update_time = NOW(),
    update_by = 'system'
WHERE model_code = 'wan2.6-image'
  AND JSON_VALID(capability_json)
  AND (
    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(capability_json, '$.minOutputPixels')) AS DECIMAL(20,4)), 0) <> 589824
    OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(capability_json, '$.maxOutputPixels')) AS DECIMAL(20,4)), 0) <> 4194304
    OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(capability_json, '$.minOutputAspectRatio')) AS DECIMAL(20,4)), 0) <> 0.25
    OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(capability_json, '$.maxOutputAspectRatio')) AS DECIMAL(20,4)), 0) <> 4
    OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(capability_json, '$.sceneRules.textToImage.maxOutputPixels')) AS DECIMAL(20,4)), 0) <> 1638400
  );

-- Gemini 图片模型使用专用 generateContent 图片协议；升级库缺少结构化绑定时仍可由顶层协议安全路由。
UPDATE aid_ai_model
SET protocol = 'gemini-image',
    config_version = config_version + 1,
    update_time = NOW(),
    update_by = 'system'
WHERE model_code IN ('gemini-3.1-flash-image', 'gemini-3-pro-image')
  AND (protocol IS NULL OR TRIM(protocol) = '');

-- Seedance 2.5 多模态参考模型：补齐官方像素/FPS Token 预估，避免有 SKU 却无法预冻结。
SET @seedance25_video_token_estimate := '{"strategy":"PIXEL_FPS","framesPerSecond":24,"tokenDivisor":1024,"autoDurationMaxSeconds":30,"inputVideoMaxSeconds":30,"fallbackResolution":"720P","minimumInputSecondsNumerator":2,"minimumInputSecondsDenominator":3,"dimensions":{"480P":{"16:9":[854,480],"9:16":[854,480],"4:3":[752,560],"3:4":[752,560],"1:1":[640,640],"21:9":[992,432],"default":[992,432]},"720P":{"16:9":[1280,720],"9:16":[1280,720],"4:3":[1112,834],"3:4":[1112,834],"1:1":[960,960],"21:9":[1470,630],"default":[1112,834]}}}';

UPDATE aid_ai_model
SET billing_rule_json = JSON_SET(billing_rule_json, '$.videoTokenEstimate', JSON_EXTRACT(@seedance25_video_token_estimate, '$')),
    config_version = config_version + 1,
    update_time = NOW(),
    update_by = 'system'
WHERE model_code = 'doubao-seedance-2.5-reference'
  AND JSON_VALID(billing_rule_json)
  AND (
    JSON_CONTAINS_PATH(billing_rule_json, 'one', '$.videoTokenEstimate') = 0
    OR JSON_TYPE(JSON_EXTRACT(billing_rule_json, '$.videoTokenEstimate')) <> 'OBJECT'
  );

-- 该模型记录专用于全模态参考：至少一项图片、视频或音频素材，禁止误暴露为纯文生视频。
SET @seedance25_reference_scene := '{"referenceToVideo":{"requiredAnyOf":["image","video","audio"],"allowedInputs":["text","image","video","audio"],"supportsAspectRatio":true,"supportsSizePreset":true,"supportsDuration":true}}';

UPDATE aid_ai_model
SET capability_json = JSON_SET(
      capability_json,
      '$.strictSceneRules', TRUE,
      '$.sceneRules', JSON_EXTRACT(@seedance25_reference_scene, '$'),
      '$.requiredAnyOf', JSON_EXTRACT('["image","video","audio"]', '$'),
      '$.allowedInputs', JSON_EXTRACT('["text","image","video","audio"]', '$')
    ),
    config_version = config_version + 1,
    update_time = NOW(),
    update_by = 'system'
WHERE model_code = 'doubao-seedance-2.5-reference'
  AND JSON_VALID(capability_json)
  AND (
    JSON_TYPE(JSON_EXTRACT(capability_json, '$.sceneRules')) <> 'OBJECT'
    OR JSON_CONTAINS_PATH(capability_json, 'one', '$.sceneRules.referenceToVideo') = 0
    OR JSON_CONTAINS_PATH(capability_json, 'one', '$.sceneRules.textToVideo') = 1
    OR COALESCE(JSON_EXTRACT(capability_json, '$.strictSceneRules') + 0, 0) <> 1
  );

SET @seedance25_video_token_estimate := NULL;
SET @seedance25_reference_scene := NULL;

-- TokenDance HappyHorse R2V 只支持参考图生视频；修复旧目录把它投影成文生视频的冲突配置。
SET @happyhorse_reference_scene := '{"referenceToVideo":{"requiredInputs":["text","image"],"allowedInputs":["text","image"]}}';

UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id = m.provider_id
SET m.generate_mode = 'reference_to_video',
    m.capability_json = JSON_SET(
      m.capability_json,
      '$.allowedScenes', JSON_EXTRACT('["referenceToVideo"]', '$'),
      '$.strictSceneRules', TRUE,
      '$.sceneRules', JSON_EXTRACT(@happyhorse_reference_scene, '$')
    ),
    m.config_version = m.config_version + 1,
    m.update_time = NOW(),
    m.update_by = 'system'
WHERE LOWER(TRIM(p.provider_code)) = 'tokendance'
  AND m.real_model_code IN ('happyhorse-1.0-r2v', 'happyhorse-1.1-r2v')
  AND m.protocol = 'tokendance:happyhorse:video-synthesis'
  AND JSON_VALID(m.capability_json)
  AND JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.catalogModelId')) = m.real_model_code
  AND (
    COALESCE(m.generate_mode, '') <> 'reference_to_video'
    OR JSON_CONTAINS_PATH(m.capability_json, 'one', '$.sceneRules.referenceToVideo') = 0
    OR JSON_LENGTH(JSON_EXTRACT(m.capability_json, '$.allowedScenes')) <> 1
    OR JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.allowedScenes[0]')) <> 'referenceToVideo'
    OR COALESCE(JSON_EXTRACT(m.capability_json, '$.strictSceneRules') + 0, 0) <> 1
  );

SET @happyhorse_reference_scene := NULL;

-- ============================================================================
-- 140-provider-regression-fixes.sql
-- ============================================================================

-- v2.1.0：模型供应商真实接口回归修复。
-- MySQL 5.7；补齐 Agnes 2.5 动态能力绑定、Vidu 多帧边界与 Kling Omni 业务能力绑定。
-- 不修改 OpenAI 图片模型的价格、SKU、倍率或计费方式，不恢复任何已软删除模型。
SET NAMES utf8mb4;
START TRANSACTION;

-- Vidu 多帧官方契约：1 张 start_image + 2~9 个 image_settings，即总图片数 3~10。
UPDATE aid_ai_model
SET capability_json = JSON_SET(capability_json,
      '$.minReferenceImages', 3,
      '$.maxReferenceImages', 10),
    config_version = COALESCE(config_version, 0) + 1,
    update_time = NOW(),
    update_by = 'system'
WHERE model_code = 'vidu-q2-pro-multiframe'
  AND del_flag = '0'
  AND JSON_VALID(capability_json)
  AND (COALESCE(JSON_EXTRACT(capability_json, '$.minReferenceImages') + 0, -1) <> 3
       OR COALESCE(JSON_EXTRACT(capability_json, '$.maxReferenceImages') + 0, -1) <> 10);

UPDATE aid_ai_model_protocol_binding b
JOIN aid_ai_model m ON m.id = b.model_id
SET b.definition_json = JSON_SET(b.definition_json,
      '$.capability.minReferenceImages', 3,
      '$.capability.maxReferenceImages', 10),
    b.update_time = NOW(),
    b.update_by = 'system'
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND b.capability_code = 'multi_frame'
  AND JSON_VALID(b.definition_json)
  AND (COALESCE(JSON_EXTRACT(b.definition_json, '$.capability.minReferenceImages') + 0, -1) <> 3
       OR COALESCE(JSON_EXTRACT(b.definition_json, '$.capability.maxReferenceImages') + 0, -1) <> 10);

-- 旧安装可能只有模型主表，补齐统一能力层；ID 由现场模型编码解析。
INSERT INTO aid_ai_model_capability
  (model_id, capability_code, generate_mode, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, 'multi_frame', 'multi_frame',
       JSON_OBJECT(
         'code', 'multi_frame',
         'label', '多帧视频',
         'generateMode', 'multi_frame',
         'defaultCapability', TRUE,
         'enabled', TRUE,
         'evidenceStatus', 'VERIFIED_OFFICIAL',
         'parameters', JSON_ARRAY(),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', TRUE,
           'supportsMultiImageInput', TRUE,
           'supportsFirstFrame', FALSE,
           'supportsLastFrame', FALSE,
           'supportsAspectRatio', FALSE,
           'supportsSizePreset', TRUE,
           'supportsDuration', TRUE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'defaultSizeCode', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultSize')),
           'defaultAspectRatio', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultAspectRatio')),
           'defaultDurationSeconds', JSON_EXTRACT(m.capability_json, '$.defaultDurationSeconds') + 0
         ),
         'rules', JSON_ARRAY(),
         'sourceUrls', JSON_ARRAY('https://platform.vidu.cn/docs/vidu-api/multi-frame')
       ),
       0, NOW(), 'system', NOW(), 'system', 'Vidu Q2-Pro 多帧能力'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND JSON_VALID(m.capability_json)
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_capability x
    WHERE x.model_id = m.id AND x.capability_code = 'multi_frame'
  );

INSERT INTO aid_ai_model_protocol_binding
  (model_id, capability_code, binding_code, protocol, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, 'multi_frame', CONCAT('vidu_multiframe_', m.id), m.protocol,
       JSON_OBJECT(
         'code', CONCAT('vidu_multiframe_', m.id),
         'enabled', TRUE,
         'defaultBinding', TRUE,
         'protocol', m.protocol,
         'upstreamModel', COALESCE(NULLIF(TRIM(m.real_model_code), ''), m.model_code),
         'apiSuffix', m.api_suffix,
         'billingMode', m.billing_mode,
         'billingRule', JSON_EXTRACT(m.billing_rule_json, '$'),
         'costCredits', m.cost_credits,
         'parameterMapping', JSON_OBJECT(),
         'fixedParameters', JSON_OBJECT(),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', TRUE,
           'supportsMultiImageInput', TRUE,
           'supportsFirstFrame', FALSE,
           'supportsLastFrame', FALSE,
           'supportsAspectRatio', FALSE,
           'supportsSizePreset', TRUE,
           'supportsDuration', TRUE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'defaultSizeCode', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultSize')),
           'defaultAspectRatio', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultAspectRatio')),
           'defaultDurationSeconds', JSON_EXTRACT(m.capability_json, '$.defaultDurationSeconds') + 0
         ),
         'capability', JSON_SET(
           m.capability_json,
           '$.videoScenario', 'multi_frame',
           '$.allowedScenes', JSON_ARRAY('multiFrame'),
           '$.strictSceneRules', TRUE
         )
       ),
       0, NOW(), 'system', NOW(), 'system', 'Vidu Q2-Pro 多帧协议绑定'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND JSON_VALID(m.capability_json)
  AND JSON_VALID(m.billing_rule_json)
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_protocol_binding x
    WHERE x.model_id = m.id AND x.capability_code = 'multi_frame'
  );

DELETE b FROM aid_ai_business_model_binding b
JOIN aid_ai_model m ON m.id = b.model_id
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND b.func_code = 'main_storyboard_video_edge'
  AND b.capability_code = 'multi_frame';

INSERT INTO aid_ai_business_model_binding
  (func_code, model_id, capability_code, default_capability, defaults_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT 'main_storyboard_video_multi_pro', m.id, 'multi_frame', 1, NULL, 0,
       NOW(), 'system', NOW(), 'system', 'Vidu Q2-Pro 多帧分镜业务绑定'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_business_model_binding x
    WHERE x.func_code = 'main_storyboard_video_multi_pro'
      AND x.model_id = m.id AND x.capability_code = 'multi_frame'
  );

-- 旧版功能池仍从 aid_ai_model_func_config.model_ids 读取可用模型；
-- 与统一业务绑定表保持一致，同时保留站长自行添加的其他模型及原有顺序。
UPDATE aid_ai_model_func_config f
JOIN aid_ai_model m ON m.model_code = 'vidu-q2-pro-multiframe' AND m.del_flag = '0'
SET f.model_ids = CONCAT(
      '[',
      TRIM(BOTH ',' FROM REPLACE(
        CONCAT(',', REPLACE(REPLACE(REPLACE(f.model_ids, ' ', ''), '[', ''), ']', ''), ','),
        CONCAT(',', m.id, ','),
        ','
      )),
      ']'
    ),
    f.update_time = NOW(),
    f.update_by = 'system'
WHERE f.func_code = 'main_storyboard_video_edge'
  AND JSON_VALID(f.model_ids)
  AND JSON_CONTAINS(CAST(f.model_ids AS JSON), CAST(m.id AS JSON), '$');

UPDATE aid_ai_model_func_config f
JOIN aid_ai_model m ON m.model_code = 'vidu-q2-pro-multiframe' AND m.del_flag = '0'
SET f.model_ids = JSON_ARRAY_APPEND(CAST(f.model_ids AS JSON), '$', m.id),
    f.update_time = NOW(),
    f.update_by = 'system'
WHERE f.func_code = 'main_storyboard_video_multi_pro'
  AND JSON_VALID(f.model_ids)
  AND NOT JSON_CONTAINS(CAST(f.model_ids AS JSON), CAST(m.id AS JSON), '$');

UPDATE aid_ai_model_alias a
JOIN aid_ai_model m ON m.model_code = a.legacy_model_code AND m.del_flag = '0'
SET a.model_id = m.id,
    a.capability_code = 'multi_frame',
    a.binding_code = COALESCE((
      SELECT p.binding_code FROM aid_ai_model_protocol_binding p
      WHERE p.model_id = m.id AND p.capability_code = 'multi_frame'
      ORDER BY p.id LIMIT 1
    ), CONCAT('vidu_multiframe_', m.id)),
    a.update_time = NOW(),
    a.update_by = 'system'
WHERE a.legacy_model_code = 'vidu-q2-pro-multiframe'
  AND (a.model_id <> m.id OR COALESCE(a.capability_code, '') <> 'multi_frame');

INSERT INTO aid_ai_model_alias
  (legacy_model_id, legacy_model_code, model_id, capability_code, binding_code,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, m.model_code, m.id, 'multi_frame',
       COALESCE((
         SELECT p.binding_code FROM aid_ai_model_protocol_binding p
         WHERE p.model_id = m.id AND p.capability_code = 'multi_frame'
         ORDER BY p.id LIMIT 1
       ), CONCAT('vidu_multiframe_', m.id)),
       NOW(), 'system', NOW(), 'system', 'Vidu Q2-Pro 多帧当前模型别名'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q2-pro-multiframe'
  AND m.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_code = m.model_code)
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_id = m.id);

-- Agnes 模型 ID 由安装现场解析，禁止依赖历史自增值 116/117。
DROP TEMPORARY TABLE IF EXISTS tmp_aid_agnes25_capability;
CREATE TEMPORARY TABLE tmp_aid_agnes25_capability (
  capability_code VARCHAR(96) NOT NULL,
  generate_mode VARCHAR(32) NOT NULL,
  scene_key VARCHAR(32) NOT NULL,
  label_name VARCHAR(32) NOT NULL,
  default_capability TINYINT(1) NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (capability_code)
) ENGINE=MEMORY;

INSERT INTO tmp_aid_agnes25_capability VALUES
('text_to_video','text_to_video','textToVideo','文生视频',0,0),
('image_to_video','image_to_video','imageToVideo','关键帧视频',1,1),
('start_end_to_video','start_end_to_video','startEndToVideo','首尾帧视频',0,2),
('reference_to_video','reference_to_video','referenceToVideo','多模态参考视频',0,3);

INSERT INTO aid_ai_model_capability
  (model_id, capability_code, generate_mode, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, c.capability_code, c.generate_mode,
       JSON_OBJECT(
         'code', c.capability_code,
         'label', c.label_name,
         'generateMode', c.generate_mode,
         'defaultCapability', c.default_capability = 1,
         'enabled', TRUE,
         'evidenceStatus', 'VERIFIED_OFFICIAL',
         'parameters', JSON_ARRAY(),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', c.capability_code <> 'text_to_video',
           'supportsMultiImageInput', c.capability_code = 'reference_to_video',
           'supportsFirstFrame', c.capability_code IN ('image_to_video','start_end_to_video'),
           'supportsLastFrame', c.capability_code IN ('image_to_video','start_end_to_video'),
           'supportsAspectRatio', TRUE,
           'supportsSizePreset', TRUE,
           'supportsDuration', TRUE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'defaultSizeCode', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultSize')),
           'defaultAspectRatio', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultAspectRatio')),
           'defaultDurationSeconds', JSON_EXTRACT(m.capability_json, '$.defaultDurationSeconds') + 0
         ),
         'rules', JSON_ARRAY(),
         'sourceUrls', JSON_ARRAY('https://wiki.agnes-ai.cn/models/agnes-video-2.5')
       ),
       c.sort_order, NOW(), 'system', NOW(), 'system', '官方 Agnes Video 2.5 能力'
FROM aid_ai_model m
CROSS JOIN tmp_aid_agnes25_capability c
WHERE m.model_code IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND m.del_flag = '0'
  AND JSON_VALID(m.capability_json)
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_capability x
    WHERE x.model_id = m.id AND x.capability_code = c.capability_code
  );

INSERT INTO aid_ai_model_protocol_binding
  (model_id, capability_code, binding_code, protocol, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, c.capability_code, CONCAT('agnes25_', c.capability_code), m.protocol,
       JSON_OBJECT(
         'code', CONCAT('agnes25_', c.capability_code),
         'enabled', TRUE,
         'defaultBinding', TRUE,
         'protocol', m.protocol,
         'upstreamModel', COALESCE(NULLIF(TRIM(m.real_model_code), ''), m.model_code),
         'apiSuffix', m.api_suffix,
         'billingMode', m.billing_mode,
         'billingRule', JSON_EXTRACT(m.billing_rule_json, '$'),
         'costCredits', m.cost_credits,
         'parameterMapping', JSON_OBJECT(),
         'fixedParameters', JSON_OBJECT(),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', c.capability_code <> 'text_to_video',
           'supportsMultiImageInput', c.capability_code = 'reference_to_video',
           'supportsFirstFrame', c.capability_code IN ('image_to_video','start_end_to_video'),
           'supportsLastFrame', c.capability_code IN ('image_to_video','start_end_to_video'),
           'supportsAspectRatio', TRUE,
           'supportsSizePreset', TRUE,
           'supportsDuration', TRUE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'defaultSizeCode', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultSize')),
           'defaultAspectRatio', JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.defaultAspectRatio')),
           'defaultDurationSeconds', JSON_EXTRACT(m.capability_json, '$.defaultDurationSeconds') + 0
         ),
         'capability', JSON_SET(
           m.capability_json,
           '$.videoScenario', c.capability_code,
           '$.allowedScenes', JSON_ARRAY(c.scene_key),
           '$.strictSceneRules', TRUE,
           '$.sceneRules', JSON_OBJECT(c.scene_key,
             JSON_EXTRACT(m.capability_json, CONCAT('$.sceneRules.', c.scene_key)))
         )
       ),
       0, NOW(), 'system', NOW(), 'system', '官方 Agnes Video 2.5 协议绑定'
FROM aid_ai_model m
CROSS JOIN tmp_aid_agnes25_capability c
WHERE m.model_code IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND m.del_flag = '0'
  AND JSON_VALID(m.capability_json)
  AND JSON_VALID(m.billing_rule_json)
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_protocol_binding x
    WHERE x.model_id = m.id AND x.capability_code = c.capability_code
  );

DROP TEMPORARY TABLE IF EXISTS tmp_aid_agnes25_business;
CREATE TEMPORARY TABLE tmp_aid_agnes25_business (
  func_code VARCHAR(100) NOT NULL,
  capability_code VARCHAR(96) NOT NULL,
  default_capability TINYINT(1) NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (func_code, capability_code)
) ENGINE=MEMORY;

INSERT INTO tmp_aid_agnes25_business VALUES
('main_storyboard_video_image','image_to_video',1,0),
('main_storyboard_video_grid','image_to_video',1,1),
('main_storyboard_video_edge','start_end_to_video',1,2),
('main_storyboard_video','text_to_video',0,3),
('main_storyboard_video','reference_to_video',1,4),
('main_storyboard_video_multi_pro','text_to_video',0,5),
('main_storyboard_video_multi_pro','reference_to_video',1,6);

INSERT INTO aid_ai_business_model_binding
  (func_code, model_id, capability_code, default_capability, defaults_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT b.func_code, m.id, b.capability_code, b.default_capability, NULL, b.sort_order,
       NOW(), 'system', NOW(), 'system', 'Agnes Video 2.5 分镜业务绑定'
FROM aid_ai_model m
CROSS JOIN tmp_aid_agnes25_business b
WHERE m.model_code IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND m.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_business_model_binding x
    WHERE x.func_code = b.func_code AND x.model_id = m.id
      AND x.capability_code = b.capability_code
  );

-- 修正历史 Agnes 别名指向；没有别名时以现场真实模型 ID 新增。
UPDATE aid_ai_model_alias a
JOIN aid_ai_model m ON m.model_code = a.legacy_model_code AND m.del_flag = '0'
SET a.model_id = m.id,
    a.capability_code = 'image_to_video',
    a.binding_code = 'agnes25_image_to_video',
    a.update_time = NOW(),
    a.update_by = 'system'
WHERE a.legacy_model_code IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND (a.model_id <> m.id OR COALESCE(a.capability_code, '') <> 'image_to_video'
       OR COALESCE(a.binding_code, '') <> 'agnes25_image_to_video');

INSERT INTO aid_ai_model_alias
  (legacy_model_id, legacy_model_code, model_id, capability_code, binding_code,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, m.model_code, m.id, 'image_to_video', 'agnes25_image_to_video',
       NOW(), 'system', NOW(), 'system', 'Agnes Video 2.5 当前模型别名'
FROM aid_ai_model m
WHERE m.model_code IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND m.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_code = m.model_code)
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_id = m.id);

-- Kling 3.0 Omni 只操作当前有效模型 103 所代表的活动行；不恢复 102、104~108。
DROP TEMPORARY TABLE IF EXISTS tmp_aid_kling103_business;
CREATE TEMPORARY TABLE tmp_aid_kling103_business (
  func_code VARCHAR(100) NOT NULL,
  capability_code VARCHAR(96) NOT NULL,
  default_capability TINYINT(1) NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (func_code, capability_code)
) ENGINE=MEMORY;

INSERT INTO tmp_aid_kling103_business VALUES
('main_storyboard_video','text_to_video',0,10),
('main_storyboard_video_multi_pro','text_to_video',0,11),
('main_storyboard_video','feature_video',0,12),
('main_storyboard_video_multi_pro','feature_video',0,13),
('main_storyboard_video','video_edit',0,14),
('main_storyboard_video_multi_pro','video_edit',0,15);

INSERT INTO aid_ai_business_model_binding
  (func_code, model_id, capability_code, default_capability, defaults_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT b.func_code, m.id, b.capability_code, b.default_capability, NULL, b.sort_order,
       NOW(), 'system', NOW(), 'system', 'Kling 3.0 Omni 分镜业务绑定'
FROM aid_ai_model m
CROSS JOIN tmp_aid_kling103_business b
WHERE m.id = 103
  AND m.model_code = 'kling-3.0-omni-t2v'
  AND m.del_flag = '0'
  AND EXISTS (
    SELECT 1 FROM aid_ai_model_capability c
    WHERE c.model_id = m.id AND c.capability_code = b.capability_code
  )
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_business_model_binding x
    WHERE x.func_code = b.func_code AND x.model_id = m.id
      AND x.capability_code = b.capability_code
  );

-- Kling Omni 视频特征参考在未提供首帧/编辑视频时必须向上游提交画幅；官方缺省使用 16:9。
UPDATE aid_ai_model_capability c
JOIN aid_ai_model m ON m.id = c.model_id
SET c.definition_json = JSON_SET(c.definition_json,
      '$.evidenceStatus', 'VERIFIED_OFFICIAL',
      '$.presentation.supportsAspectRatio', TRUE,
      '$.presentation.defaultAspectRatio', '16:9'),
    c.update_time = NOW(),
    c.update_by = 'system'
WHERE m.id = 103
  AND m.model_code = 'kling-3.0-omni-t2v'
  AND m.del_flag = '0'
  AND c.capability_code = 'feature_video'
  AND JSON_VALID(c.definition_json)
  AND (COALESCE(JSON_EXTRACT(c.definition_json, '$.presentation.supportsAspectRatio') + 0, 0) <> 1
       OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.definition_json, '$.presentation.defaultAspectRatio')), '') <> '16:9'
       OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.definition_json, '$.evidenceStatus')), '') <> 'VERIFIED_OFFICIAL');

UPDATE aid_ai_model_capability c
JOIN aid_ai_model m ON m.id = c.model_id
SET c.definition_json = JSON_ARRAY_APPEND(c.definition_json, '$.parameters',
      JSON_OBJECT('name', 'aspectRatio', 'label', '画面比例', 'type', 'string',
        'choices', JSON_ARRAY('16:9', '9:16', '1:1'), 'defaultValue', '16:9')),
    c.update_time = NOW(),
    c.update_by = 'system'
WHERE m.id = 103
  AND m.model_code = 'kling-3.0-omni-t2v'
  AND m.del_flag = '0'
  AND c.capability_code = 'feature_video'
  AND JSON_VALID(c.definition_json)
  AND JSON_TYPE(JSON_EXTRACT(c.definition_json, '$.parameters')) = 'ARRAY'
  AND JSON_SEARCH(c.definition_json, 'one', 'aspectRatio', NULL, '$.parameters[*].name') IS NULL;

UPDATE aid_ai_model_protocol_binding b
JOIN aid_ai_model m ON m.id = b.model_id
SET b.definition_json = JSON_SET(b.definition_json,
      '$.capability.supportsAspectRatio', TRUE,
      '$.capability.aspectRatioOptions', JSON_ARRAY('16:9', '9:16', '1:1'),
      '$.capability.defaultAspectRatio', '16:9',
      '$.capability.sceneRules.videoToVideo.supportsAspectRatio', TRUE,
      '$.presentation.supportsAspectRatio', TRUE,
      '$.presentation.defaultAspectRatio', '16:9'),
    b.update_time = NOW(),
    b.update_by = 'system'
WHERE m.id = 103
  AND m.model_code = 'kling-3.0-omni-t2v'
  AND m.del_flag = '0'
  AND b.capability_code = 'feature_video'
  AND JSON_VALID(b.definition_json)
  AND (COALESCE(JSON_EXTRACT(b.definition_json, '$.capability.supportsAspectRatio') + 0, 0) <> 1
       OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(b.definition_json, '$.capability.defaultAspectRatio')), '') <> '16:9'
       OR COALESCE(JSON_EXTRACT(b.definition_json, '$.presentation.supportsAspectRatio') + 0, 0) <> 1);

-- Vidu Lip Sync 的 duration 是可信素材时长与计费输入，不是用户选择的输出时长。
-- 主表继续保持 supports_duration=0，只补齐输入视频的官方 1~600 秒及文件约束。
UPDATE aid_ai_model m
SET m.capability_json = JSON_SET(IF(JSON_VALID(m.capability_json), m.capability_json, JSON_OBJECT()),
      '$.lipSync', TRUE,
      '$.supportsVideoInput', TRUE,
      '$.referenceVideoMinDurationSeconds', 1,
      '$.referenceVideoMaxDurationSeconds', 600,
      '$.referenceVideoMaxTotalDurationSeconds', 600,
      '$.referenceVideoFormats', JSON_ARRAY('mp4', 'mov', 'avi'),
      '$.referenceVideoMaxFileSizeMb', 5120,
      '$.referenceVideoMinDimensionPixels', 360,
      '$.referenceVideoMaxDimensionPixels', 4096),
    m.config_version = COALESCE(m.config_version, 0) + 1,
    m.update_time = NOW(),
    m.update_by = 'system'
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND (NOT JSON_VALID(m.capability_json)
       OR COALESCE(JSON_EXTRACT(m.capability_json, '$.referenceVideoMinDurationSeconds') + 0, -1) <> 1
       OR COALESCE(JSON_EXTRACT(m.capability_json, '$.referenceVideoMaxDurationSeconds') + 0, -1) <> 600
       OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.capability_json, '$.referenceVideoFormats[2]')), '') <> 'avi');

INSERT INTO aid_ai_model_capability
  (model_id, capability_code, generate_mode, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, 'lip_sync', 'lip_sync',
       JSON_OBJECT(
         'code', 'lip_sync',
         'label', '对口型',
         'generateMode', 'lip_sync',
         'defaultCapability', TRUE,
         'enabled', TRUE,
         'evidenceStatus', 'VERIFIED_OFFICIAL',
         'parameters', JSON_ARRAY(JSON_OBJECT('name', 'prompt', 'label', '驱动文本', 'type', 'string', 'widget', 'textarea')),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', TRUE,
           'supportsMultiImageInput', FALSE,
           'supportsFirstFrame', FALSE,
           'supportsLastFrame', FALSE,
           'supportsAspectRatio', FALSE,
           'supportsSizePreset', FALSE,
           'supportsDuration', FALSE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'supportsSystemPrompt', TRUE
         ),
         'rules', JSON_ARRAY(),
         'sourceUrls', JSON_ARRAY('https://platform.vidu.cn/docs/vidu-api/lip-sync')
       ),
       0, NOW(), 'system', NOW(), 'system', 'Vidu Lip Sync 官方能力'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_capability x
    WHERE x.model_id = m.id AND x.capability_code = 'lip_sync'
  );

UPDATE aid_ai_model_capability c
JOIN aid_ai_model m ON m.id = c.model_id
SET c.definition_json = JSON_SET(c.definition_json,
      '$.evidenceStatus', 'VERIFIED_OFFICIAL',
      '$.presentation.supportsDuration', FALSE),
    c.update_time = NOW(),
    c.update_by = 'system'
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND c.capability_code = 'lip_sync'
  AND JSON_VALID(c.definition_json)
  AND (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.definition_json, '$.evidenceStatus')), '') <> 'VERIFIED_OFFICIAL'
       OR COALESCE(JSON_EXTRACT(c.definition_json, '$.presentation.supportsDuration') + 0, 1) <> 0);

INSERT INTO aid_ai_model_protocol_binding
  (model_id, capability_code, binding_code, protocol, definition_json, sort_order,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, 'lip_sync', CONCAT('vidu_lipsync_', m.id), m.protocol,
       JSON_OBJECT(
         'code', CONCAT('vidu_lipsync_', m.id),
         'enabled', TRUE,
         'defaultBinding', TRUE,
         'protocol', m.protocol,
         'upstreamModel', COALESCE(NULLIF(TRIM(m.real_model_code), ''), m.model_code),
         'apiSuffix', m.api_suffix,
         'billingMode', m.billing_mode,
         'billingRule', JSON_EXTRACT(m.billing_rule_json, '$'),
         'costCredits', m.cost_credits,
         'parameterMapping', JSON_OBJECT(),
         'fixedParameters', JSON_OBJECT(),
         'presentation', JSON_OBJECT(
           'supportsTextInput', TRUE,
           'supportsImageInput', TRUE,
           'supportsMultiImageInput', FALSE,
           'supportsFirstFrame', FALSE,
           'supportsLastFrame', FALSE,
           'supportsAspectRatio', FALSE,
           'supportsSizePreset', FALSE,
           'supportsDuration', FALSE,
           'defaultOutputCount', 1,
           'maxOutputCount', 1,
           'supportsSystemPrompt', TRUE
         ),
         'capability', JSON_SET(m.capability_json,
           '$.videoScenario', 'lip_sync',
           '$.allowedScenes', JSON_ARRAY('videoToVideo'),
           '$.strictSceneRules', TRUE)
       ),
       0, NOW(), 'system', NOW(), 'system', 'Vidu Lip Sync 协议绑定'
FROM aid_ai_model m
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND JSON_VALID(m.capability_json)
  AND JSON_VALID(m.billing_rule_json)
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_protocol_binding x
    WHERE x.model_id = m.id AND x.capability_code = 'lip_sync'
  );

UPDATE aid_ai_model_protocol_binding b
JOIN aid_ai_model m ON m.id = b.model_id
SET b.definition_json = JSON_SET(b.definition_json,
      '$.capability.lipSync', TRUE,
      '$.capability.supportsVideoInput', TRUE,
      '$.capability.referenceVideoMinDurationSeconds', 1,
      '$.capability.referenceVideoMaxDurationSeconds', 600,
      '$.capability.referenceVideoMaxTotalDurationSeconds', 600,
      '$.capability.referenceVideoFormats', JSON_ARRAY('mp4', 'mov', 'avi'),
      '$.capability.referenceVideoMaxFileSizeMb', 5120,
      '$.capability.referenceVideoMinDimensionPixels', 360,
      '$.capability.referenceVideoMaxDimensionPixels', 4096,
      '$.presentation.supportsDuration', FALSE),
    b.update_time = NOW(),
    b.update_by = 'system'
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND b.capability_code = 'lip_sync'
  AND JSON_VALID(b.definition_json)
  AND (COALESCE(JSON_EXTRACT(b.definition_json, '$.capability.referenceVideoMinDurationSeconds') + 0, -1) <> 1
       OR COALESCE(JSON_EXTRACT(b.definition_json, '$.capability.referenceVideoMaxDurationSeconds') + 0, -1) <> 600
       OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(b.definition_json, '$.capability.referenceVideoFormats[2]')), '') <> 'avi');

UPDATE aid_ai_model_alias a
JOIN aid_ai_model m ON m.model_code = a.legacy_model_code AND m.del_flag = '0'
SET a.model_id = m.id,
    a.capability_code = 'lip_sync',
    a.binding_code = (
      SELECT p.binding_code FROM aid_ai_model_protocol_binding p
      WHERE p.model_id = m.id AND p.capability_code = 'lip_sync'
      ORDER BY p.id LIMIT 1
    ),
    a.update_time = NOW(),
    a.update_by = 'system'
WHERE a.legacy_model_code = 'vidu-q3-lipsync'
  AND EXISTS (
    SELECT 1 FROM aid_ai_model_protocol_binding p
    WHERE p.model_id = m.id AND p.capability_code = 'lip_sync'
  )
  AND (a.model_id <> m.id OR COALESCE(a.capability_code, '') <> 'lip_sync'
       OR COALESCE(a.binding_code, '') <> COALESCE((
         SELECT p.binding_code FROM aid_ai_model_protocol_binding p
         WHERE p.model_id = m.id AND p.capability_code = 'lip_sync'
         ORDER BY p.id LIMIT 1
       ), ''));

INSERT INTO aid_ai_model_alias
  (legacy_model_id, legacy_model_code, model_id, capability_code, binding_code,
   create_time, create_by, update_time, update_by, remark)
SELECT m.id, m.model_code, m.id, 'lip_sync', p.binding_code,
       NOW(), 'system', NOW(), 'system', 'Vidu Lip Sync 当前模型别名'
FROM aid_ai_model m
JOIN aid_ai_model_protocol_binding p
  ON p.model_id = m.id AND p.capability_code = 'lip_sync'
WHERE m.model_code = 'vidu-q3-lipsync'
  AND m.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_code = m.model_code)
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_alias x WHERE x.legacy_model_id = m.id)
ORDER BY p.id
LIMIT 1;

DROP TEMPORARY TABLE IF EXISTS tmp_aid_kling103_business;
DROP TEMPORARY TABLE IF EXISTS tmp_aid_agnes25_business;
DROP TEMPORARY TABLE IF EXISTS tmp_aid_agnes25_capability;
COMMIT;

-- ============================================================================
-- 150-deepseek-v4-pricing.sql
-- ============================================================================

-- DeepSeek V4 官方价格基线调整。
-- 兼容 MySQL 5.7，可重复执行；仅更新仍使用内置 SKU 编码及已知官方价格的模型。
-- 当前运行时尚未按峰谷时段选择 SKU，因此按高峰价格保守结算；缓存命中价仅作为结构化价格元数据保存，不参与当前聚合结算。

SET @deepseek_price_url := 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing';
SET @deepseek_flash_model_remark := 'DeepSeek-V4-Flash-0731；按官方高峰价保守结算：缓存命中0.10元、缓存未命中3.0元、输出9.0元/百万Token；并发上限2500';
SET @deepseek_flash_sku_remark := '官方高峰价：缓存命中0.10元、缓存未命中3.0元、输出9.0元/百万Token；当前按聚合口径使用缓存未命中价结算输入';
SET @deepseek_pro_model_remark := 'DeepSeek-V4-Pro-0813；按官方高峰价保守结算：缓存命中0.30元、缓存未命中9.0元、输出27.0元/百万Token；并发上限500';
SET @deepseek_pro_sku_remark := '官方高峰价：缓存命中0.30元、缓存未命中9.0元、输出27.0元/百万Token；当前按聚合口径使用缓存未命中价结算输入';

START TRANSACTION;

UPDATE `aid_ai_model` AS m
INNER JOIN `aid_ai_provider` AS p
        ON p.`id` = m.`provider_id`
       AND p.`provider_code` = 'deepseek'
       AND p.`del_flag` = '0'
SET m.`billing_rule_json` = JSON_SET(
        m.`billing_rule_json`,
        '$.skus[0].inputPricePerMillion', 3.0,
        '$.skus[0].cachedInputPricePerMillion', 0.10,
        '$.skus[0].cacheWritePricePerMillion', 3.0,
        '$.skus[0].outputPricePerMillion', 9.0,
        '$.skus[0].remark', @deepseek_flash_sku_remark,
        '$.settleRule.usagePricingMode', 'AGGREGATE'
    ),
    m.`billing_version` = GREATEST(COALESCE(m.`billing_version`, 0), 4),
    m.`official_price_url` = @deepseek_price_url,
    m.`remark` = @deepseek_flash_model_remark,
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1,
    m.`update_by` = 'system',
    m.`update_time` = CURRENT_TIMESTAMP
WHERE m.`model_code` = 'deepseek-v4-flash'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`billing_rule_json`) = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].skuCode')) = 'DEEPSEEK_V4_FLASH_0_1M'
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].inputPricePerMillion')) AS DECIMAL(20, 8)) IN (1.0, 3.0)
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].outputPricePerMillion')) AS DECIMAL(20, 8)) IN (2.0, 9.0)
  AND (
       CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].inputPricePerMillion')) AS DECIMAL(20, 8)) <> 3.0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].cachedInputPricePerMillion')) AS DECIMAL(20, 8)) <> 0.10
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.skus[0].cachedInputPricePerMillion') = 0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].cacheWritePricePerMillion')) AS DECIMAL(20, 8)) <> 3.0
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.skus[0].cacheWritePricePerMillion') = 0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].outputPricePerMillion')) AS DECIMAL(20, 8)) <> 9.0
    OR JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.settleRule.usagePricingMode')) <> 'AGGREGATE'
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.settleRule.usagePricingMode') = 0
    OR JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].remark')) <> @deepseek_flash_sku_remark
    OR COALESCE(m.`billing_version`, 0) < 4
    OR COALESCE(m.`official_price_url`, '') <> @deepseek_price_url
    OR COALESCE(m.`remark`, '') <> @deepseek_flash_model_remark
  );

UPDATE `aid_ai_model` AS m
INNER JOIN `aid_ai_provider` AS p
        ON p.`id` = m.`provider_id`
       AND p.`provider_code` = 'deepseek'
       AND p.`del_flag` = '0'
SET m.`billing_rule_json` = JSON_SET(
        m.`billing_rule_json`,
        '$.skus[0].inputPricePerMillion', 9.0,
        '$.skus[0].cachedInputPricePerMillion', 0.30,
        '$.skus[0].cacheWritePricePerMillion', 9.0,
        '$.skus[0].outputPricePerMillion', 27.0,
        '$.skus[0].remark', @deepseek_pro_sku_remark,
        '$.settleRule.usagePricingMode', 'AGGREGATE'
    ),
    m.`billing_version` = GREATEST(COALESCE(m.`billing_version`, 0), 4),
    m.`official_price_url` = @deepseek_price_url,
    m.`remark` = @deepseek_pro_model_remark,
    m.`config_version` = COALESCE(m.`config_version`, 0) + 1,
    m.`update_by` = 'system',
    m.`update_time` = CURRENT_TIMESTAMP
WHERE m.`model_code` = 'deepseek-v4-pro'
  AND m.`del_flag` = '0'
  AND JSON_VALID(m.`billing_rule_json`) = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].skuCode')) = 'DEEPSEEK_V4_PRO_0_1M'
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].inputPricePerMillion')) AS DECIMAL(20, 8)) IN (3.0, 9.0)
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].outputPricePerMillion')) AS DECIMAL(20, 8)) IN (6.0, 27.0)
  AND (
       CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].inputPricePerMillion')) AS DECIMAL(20, 8)) <> 9.0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].cachedInputPricePerMillion')) AS DECIMAL(20, 8)) <> 0.30
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.skus[0].cachedInputPricePerMillion') = 0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].cacheWritePricePerMillion')) AS DECIMAL(20, 8)) <> 9.0
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.skus[0].cacheWritePricePerMillion') = 0
    OR CAST(JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].outputPricePerMillion')) AS DECIMAL(20, 8)) <> 27.0
    OR JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.settleRule.usagePricingMode')) <> 'AGGREGATE'
    OR JSON_CONTAINS_PATH(m.`billing_rule_json`, 'one', '$.settleRule.usagePricingMode') = 0
    OR JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].remark')) <> @deepseek_pro_sku_remark
    OR COALESCE(m.`billing_version`, 0) < 4
    OR COALESCE(m.`official_price_url`, '') <> @deepseek_price_url
    OR COALESCE(m.`remark`, '') <> @deepseek_pro_model_remark
  );

COMMIT;

SELECT
    m.`model_code`,
    m.`billing_multiplier`,
    m.`billing_version`,
    JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].inputPricePerMillion') AS `input_price_per_million`,
    JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].cachedInputPricePerMillion') AS `cached_input_price_per_million`,
    JSON_EXTRACT(m.`billing_rule_json`, '$.skus[0].outputPricePerMillion') AS `output_price_per_million`,
    JSON_UNQUOTE(JSON_EXTRACT(m.`billing_rule_json`, '$.settleRule.usagePricingMode')) AS `usage_pricing_mode`
FROM `aid_ai_model` AS m
INNER JOIN `aid_ai_provider` AS p ON p.`id` = m.`provider_id`
WHERE p.`provider_code` = 'deepseek'
  AND m.`model_code` IN ('deepseek-v4-flash', 'deepseek-v4-pro');

-- ============================================================================
-- 160-gpt-image-2-5-models.sql
-- ============================================================================

-- v2.1.0：新增 GPT Image 2.5 Flare / Sunburst（MySQL 5.7，可重复执行）。
-- 依赖：现有 aid_ai_provider、aid_ai_model（含 config_version）、aid_ai_model_func_config 表。
-- 执行前选中目标数据库；必须已有 OpenAI 的 gpt-image-2，且采用有效的 PER_IMAGE SKU。
-- 新记录原样复制来源的 billing_rule_json（包括 SKU 编码/名称）、billing_mode、billing_version、
-- cost_credits、billing_multiplier、is_free，不写死价格，不把既有按张规则改为 Token 计费。
-- 这是创建时的配置快照，后续修改 gpt-image-2 不会自动同步到两个新模型。
-- 新模型默认停用，继承来源的供应商、相对路径、现有尺寸/参考图能力和调度配置；
-- 验证供应商支持对应模型后在后台启用。本脚本不新增页面质量选项。
-- 同名记录（包括软删除记录）保持原状，不覆盖自定义 SKU、倍率、状态或关联。
-- 仅为本次新建模型追加来源已有的图片功能池关系，保留原顺序并按 ID 去重。
-- 不修改供应商配置，不写入密钥、网关或其他环境值；不修改原模型及历史任务。
-- 官方契约：https://developers.openai.com/api/docs/guides/image-generation

DROP PROCEDURE IF EXISTS `aid_add_gpt_image_25_models`;
DELIMITER $$
CREATE PROCEDURE `aid_add_gpt_image_25_models`()
main: BEGIN
  DECLARE v_source_id BIGINT DEFAULT NULL;
  DECLARE v_flare_id BIGINT DEFAULT NULL;
  DECLARE v_sunburst_id BIGINT DEFAULT NULL;
  DECLARE v_flare_exists INT DEFAULT 0;
  DECLARE v_sunburst_exists INT DEFAULT 0;
  DECLARE v_billing_rule LONGTEXT;
  DECLARE v_billing_mode VARCHAR(20);
  DECLARE v_capability LONGTEXT;
  DECLARE v_index INT DEFAULT 0;
  DECLARE v_meter_type VARCHAR(32);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;
  SELECT m.`id`, m.`billing_mode`, m.`billing_rule_json`, m.`capability_json`
    INTO v_source_id, v_billing_mode, v_billing_rule, v_capability
  FROM `aid_ai_model` m
  JOIN `aid_ai_provider` p ON p.`id` = m.`provider_id`
  WHERE m.`model_code` = 'gpt-image-2' AND m.`del_flag` = '0'
    AND m.`model_type` = 'image' AND m.`protocol` = 'openai-image'
    AND p.`provider_code` = 'openai' AND p.`del_flag` = '0'
  ORDER BY m.`id`
  LIMIT 1
  FOR UPDATE;

  SELECT COUNT(*) INTO v_flare_exists
  FROM `aid_ai_model` WHERE `model_code` = 'gpt-image-2.5-flare';
  SELECT COUNT(*) INTO v_sunburst_exists
  FROM `aid_ai_model` WHERE `model_code` = 'gpt-image-2.5-sunburst';
  IF v_flare_exists > 0 AND v_sunburst_exists > 0 THEN
    COMMIT;
    LEAVE main;
  END IF;

  IF v_source_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '缺少有效的 OpenAI gpt-image-2 来源模型';
  END IF;
  IF NOT (v_billing_mode <=> 'SKU') OR COALESCE(JSON_VALID(v_billing_rule), 0) <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gpt-image-2 必须配置有效 SKU JSON';
  END IF;
  IF NOT (JSON_UNQUOTE(JSON_EXTRACT(v_billing_rule, '$.meterType')) <=> 'PER_IMAGE')
    OR NOT (JSON_TYPE(JSON_EXTRACT(v_billing_rule, '$.skus')) <=> 'ARRAY')
    OR COALESCE(JSON_LENGTH(JSON_EXTRACT(v_billing_rule, '$.skus')), 0) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gpt-image-2 必须采用非空 PER_IMAGE SKU';
  END IF;
  -- SKU 可覆盖顶层计量单位，禁止把混有 Token 等计量的来源当成按张模板。
  WHILE v_index < JSON_LENGTH(JSON_EXTRACT(v_billing_rule, '$.skus')) DO
    SET v_meter_type = JSON_UNQUOTE(JSON_EXTRACT(v_billing_rule,
      CONCAT('$.skus[', v_index, '].meterType')));
    IF v_meter_type IS NOT NULL AND v_meter_type NOT IN ('null', '', 'PER_IMAGE') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gpt-image-2 存在非 PER_IMAGE 的 SKU';
    END IF;
    SET v_index = v_index + 1;
  END WHILE;
  IF COALESCE(JSON_VALID(v_capability), 0) <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gpt-image-2 缺少有效能力 JSON';
  END IF;
  IF JSON_TYPE(v_capability) <> 'OBJECT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gpt-image-2 能力 JSON 必须为对象';
  END IF;

  INSERT INTO `aid_ai_model` (
    `provider_id`,`model_code`,`real_model_code`,`model_name`,`model_type`,`generate_mode`,`api_version`,
    `cost_credits`,`billing_multiplier`,`api_suffix`,`protocol`,`priority`,`status`,`del_flag`,
    `create_time`,`create_by`,`update_time`,`update_by`,`remark`,`billing_mode`,`billing_rule_json`,
    `billing_version`,`schedule_strategy_json`,`image_refine`,`supports_text_input`,`supports_system_prompt`,
    `supports_image_input`,`supports_multi_image_input`,`max_output_count`,`default_output_count`,
    `supports_aspect_ratio`,`supports_size_preset`,`supports_duration`,`supports_first_frame`,`supports_last_frame`,
    `default_size_code`,`default_aspect_ratio`,`default_duration_seconds`,`capability_json`,`param_mapping_json`,
    `capability_inited`,`extra_body`,`official_price_url`,`is_free`,`config_version`
  )
  SELECT s.`provider_id`, n.`model_code`, n.`model_code`, n.`model_name`, s.`model_type`, s.`generate_mode`, s.`api_version`,
    s.`cost_credits`, s.`billing_multiplier`, s.`api_suffix`, s.`protocol`, s.`priority`, '1', '0',
    NOW(), 'system', NOW(), 'system',
    CONCAT(n.`model_name`, '；文生图与参考图编辑；沿用 gpt-image-2 的按张 SKU 与现有参数配置；默认停用'),
    s.`billing_mode`, s.`billing_rule_json`, s.`billing_version`, s.`schedule_strategy_json`,
    s.`image_refine`, s.`supports_text_input`, s.`supports_system_prompt`,
    s.`supports_image_input`, s.`supports_multi_image_input`, s.`max_output_count`, s.`default_output_count`,
    s.`supports_aspect_ratio`, s.`supports_size_preset`, s.`supports_duration`, s.`supports_first_frame`, s.`supports_last_frame`,
    s.`default_size_code`, s.`default_aspect_ratio`, s.`default_duration_seconds`, s.`capability_json`, s.`param_mapping_json`,
    1, s.`extra_body`, 'https://developers.openai.com/api/docs/pricing#image-generation', s.`is_free`, 0
  FROM `aid_ai_model` s
  CROSS JOIN (
    SELECT 'gpt-image-2.5-flare' AS `model_code`, 'GPT Image 2.5 Flare' AS `model_name`
    UNION ALL
    SELECT 'gpt-image-2.5-sunburst', 'GPT Image 2.5 Sunburst'
  ) n
  WHERE s.`id` = v_source_id
    AND NOT EXISTS (SELECT 1 FROM `aid_ai_model` existing WHERE existing.`model_code` = n.`model_code`);

  IF v_flare_exists = 0 THEN
    SELECT `id` INTO v_flare_id FROM `aid_ai_model` WHERE `model_code` = 'gpt-image-2.5-flare';
    UPDATE `aid_ai_model_func_config`
    SET `model_ids` = JSON_ARRAY_APPEND(`model_ids`, '$', v_flare_id),
        `update_time` = NOW(), `update_by` = 'system'
    WHERE `del_flag` = '0' AND `model_type` = 'image' AND JSON_TYPE(`model_ids`) = 'ARRAY'
      AND (JSON_CONTAINS(`model_ids`, CAST(v_source_id AS CHAR), '$') = 1
        OR JSON_CONTAINS(`model_ids`, JSON_QUOTE(CAST(v_source_id AS CHAR)), '$') = 1)
      AND JSON_CONTAINS(`model_ids`, CAST(v_flare_id AS CHAR), '$') = 0
      AND JSON_CONTAINS(`model_ids`, JSON_QUOTE(CAST(v_flare_id AS CHAR)), '$') = 0;
  END IF;
  IF v_sunburst_exists = 0 THEN
    SELECT `id` INTO v_sunburst_id FROM `aid_ai_model` WHERE `model_code` = 'gpt-image-2.5-sunburst';
    UPDATE `aid_ai_model_func_config`
    SET `model_ids` = JSON_ARRAY_APPEND(`model_ids`, '$', v_sunburst_id),
        `update_time` = NOW(), `update_by` = 'system'
    WHERE `del_flag` = '0' AND `model_type` = 'image' AND JSON_TYPE(`model_ids`) = 'ARRAY'
      AND (JSON_CONTAINS(`model_ids`, CAST(v_source_id AS CHAR), '$') = 1
        OR JSON_CONTAINS(`model_ids`, JSON_QUOTE(CAST(v_source_id AS CHAR)), '$') = 1)
      AND JSON_CONTAINS(`model_ids`, CAST(v_sunburst_id AS CHAR), '$') = 0
      AND JSON_CONTAINS(`model_ids`, JSON_QUOTE(CAST(v_sunburst_id AS CHAR)), '$') = 0;
  END IF;
  COMMIT;
END$$
DELIMITER ;
CALL `aid_add_gpt_image_25_models`();
DROP PROCEDURE `aid_add_gpt_image_25_models`;


-- ============================================================================
-- 170-agnes-3-0-flash.sql
-- ============================================================================

-- v2.1.0：新增 Agnes 3.0 Flash 官方文本模型目录项。
-- 依赖：本文件前置迁移已准备 aid_ai_provider、aid_ai_model 及模型配置版本字段。
-- 兼容 MySQL 5.7，可重复执行；不覆盖站长已有同名模型或自定义网关。

START TRANSACTION;

UPDATE `aid_ai_provider`
SET `base_url` = CASE
      WHEN NULLIF(TRIM(`base_url`), '') IS NULL OR TRIM(`base_url`) = 'https://api.agnes-ai.cn'
        THEN 'https://apihub.agnes-ai.com'
      ELSE `base_url`
    END,
    `official_doc_url` = CASE
      WHEN NULLIF(TRIM(`official_doc_url`), '') IS NULL OR TRIM(`official_doc_url`) = 'https://wiki.agnes-ai.cn'
        THEN 'https://wiki.agnes-ai.com'
      ELSE `official_doc_url`
    END,
    `official_price_url` = CASE
      WHEN NULLIF(TRIM(`official_price_url`), '') IS NULL OR TRIM(`official_price_url`) = 'https://wiki.agnes-ai.cn'
        THEN 'https://wiki.agnes-ai.com/en/docs/pricing.md'
      ELSE `official_price_url`
    END,
    `update_time` = NOW(),
    `update_by` = 'system'
WHERE `provider_code` = 'agnes'
  AND `del_flag` = '0'
  AND (
    NULLIF(TRIM(`base_url`), '') IS NULL
    OR TRIM(`base_url`) = 'https://api.agnes-ai.cn'
    OR NULLIF(TRIM(`official_doc_url`), '') IS NULL
    OR TRIM(`official_doc_url`) = 'https://wiki.agnes-ai.cn'
    OR NULLIF(TRIM(`official_price_url`), '') IS NULL
    OR TRIM(`official_price_url`) = 'https://wiki.agnes-ai.cn'
  );

SET @agnes30_provider_id := (
  SELECT `id`
  FROM `aid_ai_provider`
  WHERE `provider_code` = 'agnes' AND `del_flag` = '0'
  ORDER BY `id`
  LIMIT 1
);

SET @agnes30_capability := '{"requiresConfiguredBilling":true,"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"supportsImageInput":true,"supportsVideoInput":false,"supportsAudioInput":false,"supportsDocumentInput":false,"maxInputImages":10,"maxInputVideos":0,"maxInputAudios":0,"maxInputDocuments":0,"inputImageFormats":[],"contextWindowTokens":512000,"maxOutputTokens":65536,"supportsStreaming":true,"supportsToolCalling":true,"supportsReasoning":true,"supportsReasoningDisable":true,"supportsReasoningContent":false,"returnsReasoningContent":false,"supportsReasoningBudget":false,"reasoningApiStyle":"AGNES","outputTokenApiField":"max_tokens","defaultReasoningEnabled":false,"allowedReasoningLevels":[],"capabilityVerifiedAt":"2026-09-09","capabilitySourceUrls":["https://wiki.agnes-ai.com/en/docs/agnes-30-flash.md"]}';

-- 官方未公布图片张数硬限制，10 张为平台接入安全上限；未公布的格式和大小不虚构。
-- 刊例美元价按 7 元/美元折算；当前官方价格为 0，is_free=1 保证实际零扣费。
SET @agnes30_billing_rule := '{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"AGNES_30_FLASH_0_512K","skuName":"输入Token 0-512K","enabled":true,"priority":1,"match":{"inputTokensMin":0,"inputTokensMax":512000},"remark":"官方刊例价：缓存输入$0.005、输入$0.05、输出$0.15每百万Token；当前价格均为$0","inputPricePerMillion":0.35,"cachedInputPricePerMillion":0.035,"outputPricePerMillion":1.05}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"allowRefund":true,"allowExtraCharge":false}}';

INSERT INTO `aid_ai_model` (
  `provider_id`,`model_code`,`real_model_code`,`model_name`,`model_type`,`generate_mode`,`api_version`,
  `cost_credits`,`billing_multiplier`,`api_suffix`,`protocol`,`priority`,`status`,`del_flag`,
  `create_time`,`create_by`,`update_time`,`update_by`,`remark`,`billing_mode`,`billing_rule_json`,
  `billing_version`,`schedule_strategy_json`,`image_refine`,`supports_text_input`,`supports_system_prompt`,
  `supports_image_input`,`supports_multi_image_input`,`max_output_count`,`default_output_count`,
  `supports_aspect_ratio`,`supports_size_preset`,`supports_duration`,`supports_first_frame`,`supports_last_frame`,
  `default_size_code`,`default_aspect_ratio`,`default_duration_seconds`,`capability_json`,`param_mapping_json`,
  `capability_inited`,`extra_body`,`official_price_url`,`is_free`,`config_version`
)
SELECT
  @agnes30_provider_id,'agnes-3.0-flash','agnes-3.0-flash','Agnes 3.0 Flash','text','text',NULL,
  0,1,'/v1/chat/completions','openai-compatible-text',110,'1','0',
  NOW(),'system',NOW(),'system',
  '文本与图片 URL 输入、文本输出；512K 上下文、最大输出 65536 Token；支持工具调用与可关闭思考；不支持视频、音频和文档输入；当前免费、保留刊例价；默认停用',
  'SKU',@agnes30_billing_rule,1,NULL,NULL,1,1,1,1,1,1,0,0,0,0,0,NULL,NULL,NULL,
  @agnes30_capability,NULL,1,'{"stream":false,"chat_template_kwargs":{"enable_thinking":false}}',
  'https://wiki.agnes-ai.com/en/docs/pricing.md',1,0
WHERE @agnes30_provider_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `aid_ai_model` WHERE `model_code` = 'agnes-3.0-flash'
  );

COMMIT;
