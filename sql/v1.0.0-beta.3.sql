-- v1.0.0-beta.3 数据库迁移脚本。
-- 本脚本可重复执行：补齐本次配置、权限，并保留已有业务配置和角色授权。

-- 成片正式槽与待审槽分别记录内容指纹，避免同一成片重复进入审核。
-- DDL 在 MySQL 中会隐式提交，因此放在业务数据事务之前执行。
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

START TRANSACTION;

-- 定位未识别错误日志菜单，兼容菜单 ID 被部署方调整的情况。
SET @error_log_menu_id := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `menu_type` = 'C'
      AND (`component` = 'aid/errorlog/index' OR `perms` = 'aid:errorlog:list')
    ORDER BY CASE WHEN `component` = 'aid/errorlog/index' THEN 0 ELSE 1 END, `menu_id`
    LIMIT 1
);

SET @convert_menu_id_before := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `perms` = 'aid:errorlog:convert'
    ORDER BY `menu_id`
    LIMIT 1
);

-- 旧库缺少按钮权限时补建；新安装库已有该权限时不重复插入。
INSERT INTO `sys_menu` (
    `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`,
    `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`,
    `create_by`, `create_time`, `update_by`, `update_time`, `remark`
)
SELECT
    '转为规则', @error_log_menu_id, 3, '#', '', NULL, '',
    1, 0, 'F', '0', '0', 'aid:errorlog:convert', '#',
    'system', NOW(), 'system', NOW(), '将未识别错误样本转换为归一化规则'
WHERE @error_log_menu_id IS NOT NULL
  AND @convert_menu_id_before IS NULL;

SET @convert_menu_id_after := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `perms` = 'aid:errorlog:convert'
    ORDER BY `menu_id`
    LIMIT 1
);

-- 统一按钮的父菜单和展示信息，不改变已有角色授权关系。
UPDATE `sys_menu`
SET `menu_name` = '转为规则',
    `parent_id` = @error_log_menu_id,
    `order_num` = 3,
    `path` = '#',
    `component` = '',
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `update_by` = 'system',
    `update_time` = NOW(),
    `remark` = '将未识别错误样本转换为归一化规则'
WHERE `menu_id` = @convert_menu_id_after
  AND @error_log_menu_id IS NOT NULL;

-- 仅在本次新建权限时，继承原错误日志菜单的角色授权；已有权限保持部署方配置不变。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT DISTINCT role_menu.`role_id`, @convert_menu_id_after
FROM `sys_role_menu` role_menu
WHERE role_menu.`menu_id` = @error_log_menu_id
  AND @convert_menu_id_before IS NULL
  AND @convert_menu_id_after IS NOT NULL;

COMMIT;

START TRANSACTION;

-- 七牛云 Kodo 配置：仅补齐字段，不覆盖部署方已经填写的值。
INSERT INTO `aid_config` (
    `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
    `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`
) VALUES
    ('oss', 'qiniuAccessKey', '', '七牛云AccessKey', '0', 20, NOW(), 'system', 'system', NOW(), '七牛云账号AccessKey，敏感配置', 0),
    ('oss', 'qiniuSecretKey', '', '七牛云SecretKey', '0', 21, NOW(), 'system', 'system', NOW(), '七牛云账号SecretKey，敏感配置', 0),
    ('oss', 'qiniuBucketName', '', '七牛云Bucket名称', '0', 22, NOW(), 'system', 'system', NOW(), '七牛云Kodo存储空间名称', 0),
    ('oss', 'qiniuPrefix', 'upload', '七牛云路径前缀', '0', 23, NOW(), 'system', 'system', NOW(), '仅用于后续新上传文件，可留空；官方资源固定放在Bucket根目录aid下', 0)
ON DUPLICATE KEY UPDATE
    `config_dict` = VALUES(`config_dict`),
    `order_num` = VALUES(`order_num`),
    `update_by` = 'system',
    `update_time` = NOW(),
    `remark` = VALUES(`remark`),
    `del_flag` = 0;

-- 公共访问域名由本地、OSS、COS、七牛云共用；保留已填写的域名值。
UPDATE `aid_config`
SET `config_dict` = '公共访问域名（CDN）',
    `remark` = '本地官方资源示例https://api.example.com/profile；云存储示例https://cdn.example.com，通常不加/profile',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `category` = 'oss' AND `config_name` = 'cdnDomain';

UPDATE `aid_config`
SET `remark` = '上传模式：local=本地存储；oss=阿里云OSS；cos=腾讯云COS；qiniu=七牛云Kodo',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `category` = 'oss' AND `config_name` = 'uploadMode';

UPDATE `aid_config`
SET `config_dict` = 'COS源站域名（可选）',
    `remark` = '腾讯云COS源站域名，仅用于向上游厂商提供源站地址；公共访问域名统一填写cdnDomain',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `category` = 'oss' AND `config_name` = 'cosCdnDomain';

-- 官方资源初始化按钮权限。
SET @upgrade_menu_id := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `menu_type` = 'C'
      AND (`component` = 'aidconfig/upgrade/index' OR `perms` = 'aidconfig:upgrade:list')
    ORDER BY CASE WHEN `component` = 'aidconfig/upgrade/index' THEN 0 ELSE 1 END, `menu_id`
    LIMIT 1
);

SET @assets_menu_id_before := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `perms` = 'aidconfig:upgrade:assets'
    ORDER BY `menu_id`
    LIMIT 1
);

INSERT INTO `sys_menu` (
    `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`,
    `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`,
    `create_by`, `create_time`, `update_by`, `update_time`, `remark`
)
SELECT
    '官方资源初始化', @upgrade_menu_id, 7, '#', '', NULL, '',
    1, 0, 'F', '0', '0', 'aidconfig:upgrade:assets', '#',
    'system', NOW(), 'system', NOW(), '上传、校验并初始化官方资源包'
WHERE @upgrade_menu_id IS NOT NULL
  AND @assets_menu_id_before IS NULL;

SET @assets_menu_id_after := (
    SELECT `menu_id`
    FROM `sys_menu`
    WHERE `perms` = 'aidconfig:upgrade:assets'
    ORDER BY `menu_id`
    LIMIT 1
);

UPDATE `sys_menu`
SET `menu_name` = '官方资源初始化',
    `parent_id` = @upgrade_menu_id,
    `order_num` = 7,
    `path` = '#',
    `component` = '',
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `update_by` = 'system',
    `update_time` = NOW(),
    `remark` = '上传、校验并初始化官方资源包'
WHERE `menu_id` = @assets_menu_id_after
  AND @upgrade_menu_id IS NOT NULL;

-- 仅在本次新建权限时继承升级页面的角色授权。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT DISTINCT role_menu.`role_id`, @assets_menu_id_after
FROM `sys_role_menu` role_menu
WHERE role_menu.`menu_id` = @upgrade_menu_id
  AND @assets_menu_id_before IS NULL
  AND @assets_menu_id_after IS NOT NULL;

COMMIT;
