-- v1.0.0-beta.5 数据库迁移脚本。
-- 本脚本可重复执行：仅补齐登录渠道开关配置，不覆盖管理员已有开关值。

START TRANSACTION;

INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`
) VALUES
  ('sms', 'enabled', 'false', '是否启用', '0', 1, NOW(), 'system', 'system', NOW(), '短信配置', 0),
  ('mail', 'enabled', 'false', '是否启用', '0', 1, NOW(), 'system', 'system', NOW(), '邮箱总开关', 0),
  ('wxLogin', 'enabled', 'false', '是否启用', '0', 1, NOW(), 'system', 'system', NOW(), '微信扫码登录总开关', 0)
ON DUPLICATE KEY UPDATE
  `config_value` = `config_value`;

COMMIT;
