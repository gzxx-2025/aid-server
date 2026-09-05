-- ============================================================================
-- AID v2.0.0 数据库增量脚本
-- 发布日期：2026-09-05
-- 兼容 MySQL 5.7；按下列归档顺序执行；脚本设计为可重复执行。
-- ============================================================================

-- ===== BEGIN 010-seo-management.sql =====
-- SEO 管理中心公共数据库增量脚本（v2.0.0）
-- 兼容 MySQL 5.7；可重复执行；不会覆盖管理员已有站点配置或密钥。

CREATE TABLE IF NOT EXISTS `aid_seo_page` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '页面ID',
  `source_type` varchar(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源类型 BUILTIN/MANUAL/扩展模块',
  `source_id` varchar(128) NULL DEFAULT NULL COMMENT '来源业务ID',
  `page_path` varchar(512) NOT NULL COMMENT '站内页面路径',
  `canonical_url` varchar(2048) NOT NULL DEFAULT '' COMMENT '规范化完整URL',
  `page_title` varchar(255) NOT NULL COMMENT '页面标题',
  `meta_description` varchar(500) NULL DEFAULT NULL COMMENT '页面描述',
  `meta_keywords` varchar(500) NULL DEFAULT NULL COMMENT '页面关键词',
  `og_image_url` varchar(2048) NULL DEFAULT NULL COMMENT '社交分享图片',
  `indexable` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否允许索引',
  `sitemap_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否进入站点地图',
  `content_hash` char(64) NULL DEFAULT NULL COMMENT '待提交内容摘要',
  `source_update_time` datetime NULL DEFAULT NULL COMMENT '来源内容更新时间',
  `last_seen_time` datetime NULL DEFAULT NULL COMMENT '最近扫描发现时间',
  `status` varchar(16) NOT NULL DEFAULT '0' COMMENT '状态 0启用/1停用',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seo_page_path` (`page_path`),
  UNIQUE KEY `uk_seo_page_source` (`source_type`, `source_id`),
  KEY `idx_seo_page_indexable` (`status`, `indexable`, `sitemap_enabled`),
  KEY `idx_seo_page_seen` (`last_seen_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SEO页面清单';

CREATE TABLE IF NOT EXISTS `aid_seo_submission` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '提交状态ID',
  `page_id` bigint(20) NOT NULL COMMENT '页面ID',
  `provider` varchar(32) NOT NULL COMMENT '搜索引擎 BAIDU',
  `channel` varchar(16) NOT NULL COMMENT '渠道 API/MANUAL',
  `submit_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/ACCEPTED/RETRY/INVALID/BLOCKED',
  `submitted_hash` char(64) NULL DEFAULT NULL COMMENT '最近受理的页面摘要',
  `attempt_count` int(11) NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `next_retry_time` datetime NULL DEFAULT NULL COMMENT '下次重试时间',
  `last_attempt_time` datetime NULL DEFAULT NULL COMMENT '最近尝试时间',
  `accepted_time` datetime NULL DEFAULT NULL COMMENT '上游受理时间',
  `last_http_status` int(11) NULL DEFAULT NULL COMMENT '最近HTTP状态码',
  `last_error_code` varchar(64) NULL DEFAULT NULL COMMENT '最近错误码',
  `last_error_message` varchar(500) NULL DEFAULT NULL COMMENT '脱敏错误摘要',
  `provider_remain` int(11) NULL DEFAULT NULL COMMENT '上游返回的当日剩余额度',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seo_submission_page_channel` (`page_id`, `provider`, `channel`),
  KEY `idx_seo_submission_dispatch` (`provider`, `channel`, `submit_status`, `next_retry_time`),
  KEY `idx_seo_submission_accepted` (`accepted_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SEO页面提交状态';

CREATE TABLE IF NOT EXISTS `aid_seo_submission_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `batch_no` varchar(64) NOT NULL COMMENT '提交批次号',
  `page_id` bigint(20) NOT NULL COMMENT '页面ID',
  `provider` varchar(32) NOT NULL COMMENT '搜索引擎',
  `channel` varchar(16) NOT NULL COMMENT '提交渠道',
  `trigger_type` varchar(16) NOT NULL COMMENT '触发方式 SCHEDULED/ADMIN',
  `submit_status` varchar(16) NOT NULL COMMENT '本次结果状态',
  `url_snapshot` varchar(2048) NOT NULL COMMENT '提交时完整URL',
  `http_status` int(11) NULL DEFAULT NULL COMMENT '上游HTTP状态码',
  `response_summary` varchar(1000) NULL DEFAULT NULL COMMENT '脱敏响应摘要',
  `error_code` varchar(64) NULL DEFAULT NULL COMMENT '错误码',
  `error_message` varchar(500) NULL DEFAULT NULL COMMENT '脱敏错误摘要',
  `operator_id` bigint(20) NULL DEFAULT NULL COMMENT '管理员ID',
  `operator_name` varchar(64) NULL DEFAULT NULL COMMENT '管理员名称',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_seo_log_batch` (`batch_no`),
  KEY `idx_seo_log_page_time` (`page_id`, `create_time`),
  KEY `idx_seo_log_result_time` (`provider`, `submit_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SEO提交审计日志';

INSERT INTO `aid_config`
  (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`)
VALUES
  ('seo', 'site_url', '', '站点公开访问地址', '0', 1, NOW(), 'system', 'system', NOW(), '只允许HTTP或HTTPS站点根地址', 0),
  ('seo', 'site_name', '', '站点名称', '0', 2, NOW(), 'system', 'system', NOW(), '用于页面标题与结构化展示', 0),
  ('seo', 'title_suffix', '', '页面标题后缀', '0', 3, NOW(), 'system', 'system', NOW(), '页面未包含后缀时自动追加', 0),
  ('seo', 'default_description', '', '默认页面描述', '0', 4, NOW(), 'system', 'system', NOW(), '页面未单独维护描述时使用', 0),
  ('seo', 'default_keywords', '', '默认关键词', '0', 5, NOW(), 'system', 'system', NOW(), '英文逗号分隔', 0),
  ('seo', 'baidu_enabled', 'false', '百度API主动推送开关', '0', 6, NOW(), 'system', 'system', NOW(), '仅定时任务或管理员操作触发，不影响内容发布', 0),
  ('seo', 'baidu_site', '', '百度验证站点', '0', 7, NOW(), 'system', 'system', NOW(), '必须与公开站点同源', 0),
  ('seo', 'baidu_token', '', '百度推送准入密钥', '0', 8, NOW(), 'system', 'system', NOW(), '保存时加密，接口不返回明文', 0),
  ('seo', 'submit_batch_size', '100', '单批主动推送数量', '0', 9, NOW(), 'system', 'system', NOW(), '范围1至2000', 0),
  ('seo', 'robots_disallow', '/admin\n/assets\n/billing\n/create\n/forgot-password\n/login\n/user\n/aid\n/url', 'robots禁止抓取路径', '0', 10, NOW(), 'system', 'system', NOW(), '每行一个站内路径', 0)
ON DUPLICATE KEY UPDATE `config_name`=VALUES(`config_name`), `del_flag`='0';

-- SEO 管理菜单及按钮权限；不自动授予非管理员角色。
SET @seo_parent_menu_id := (SELECT `menu_id` FROM `sys_menu` WHERE `path`='system' AND `menu_type`='M' LIMIT 1);
SET @seo_menu_id := (SELECT `menu_id` FROM `sys_menu` WHERE `perms`='aid:seo:list' LIMIT 1);
INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'SEO 管理', COALESCE(@seo_parent_menu_id, 0), 8, 'seo', 'aid/seo/index', NULL, '', 1, 0, 'C', '0', '0', 'aid:seo:list', 'search', 'system', NOW(), 'system', NOW(), '搜索引擎抓取、站点地图与链接提交管理'
FROM DUAL WHERE @seo_menu_id IS NULL;
SET @seo_menu_id := (SELECT `menu_id` FROM `sys_menu` WHERE `perms`='aid:seo:list' LIMIT 1);

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'SEO 信息查询', @seo_menu_id, 1, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:seo:query', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE @seo_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:seo:query');

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'SEO 配置维护', @seo_menu_id, 2, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:seo:edit', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE @seo_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:seo:edit');

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'SEO 链接提交', @seo_menu_id, 3, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:seo:submit', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE @seo_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:seo:submit');
-- ===== END 010-seo-management.sql =====

-- ===== BEGIN 020-nginx-management.sql =====
-- v2.0.0：Nginx 配置管理权限。MySQL 5.7 幂等；不修改既有部署值或角色授权。
INSERT INTO sys_menu
(menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'Nginx配置管理', parent.menu_id, 8, '#', '', 1, 0, 'F', '0', '0',
       'aidconfig:upgrade:nginx', '#', 'system', NOW(), '校验、应用和恢复本安装的受管Nginx配置'
FROM sys_menu parent
WHERE parent.perms = 'aidconfig:upgrade:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu current_menu WHERE current_menu.perms = 'aidconfig:upgrade:nginx')
ORDER BY parent.menu_id LIMIT 1;
-- ===== END 020-nginx-management.sql =====

-- ===== BEGIN 030-provider-balance.sql =====
-- ============================================================================
-- 供应商余额监控与提醒
-- ============================================================================
-- 供应商余额监控、提醒渠道及理论成本台账。
-- 兼容 MySQL 5.7；可重复执行；不覆盖已有配置值。

-- 供应商余额监控：仅已选择的供应商参与查询、错误触发、模拟余额和通知。
CREATE TABLE IF NOT EXISTS `aid_provider_balance_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `provider_id` bigint(20) NOT NULL COMMENT '供应商ID',
  `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否选择该供应商参与监控',
  `api_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用官方余额接口',
  `simulated_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用理论模拟余额',
  `error_rule_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用请求错误触发',
  `forecast_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用余额可用天数预警',
  `currency` varchar(16) NOT NULL DEFAULT 'CNY' COMMENT '余额单位或币种',
  `initial_amount` decimal(20,8) NULL DEFAULT NULL COMMENT '模拟余额初始金额',
  `cost_unit_multiplier` decimal(20,8) NOT NULL DEFAULT 1.00000000 COMMENT '官方基础价到余额单位换算倍率',
  `initial_time` datetime NULL DEFAULT NULL COMMENT '模拟余额开始时间',
  `warning_threshold` decimal(20,8) NOT NULL DEFAULT 100.00000000 COMMENT '预警阈值',
  `critical_threshold` decimal(20,8) NOT NULL DEFAULT 20.00000000 COMMENT '严重阈值',
  `recovery_threshold` decimal(20,8) NOT NULL DEFAULT 120.00000000 COMMENT '恢复阈值',
  `forecast_days` int(11) NOT NULL DEFAULT 3 COMMENT '预计可用天数阈值',
  `repeat_interval_minutes` int(11) NOT NULL DEFAULT 360 COMMENT '重复提醒间隔分钟',
  `query_interval_minutes` int(11) NOT NULL DEFAULT 10 COMMENT '余额查询间隔分钟',
  `confirm_count` int(11) NOT NULL DEFAULT 2 COMMENT '连续低余额确认次数',
  `current_status` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT '当前状态',
  `current_source` varchar(16) NULL DEFAULT NULL COMMENT '当前余额来源',
  `current_balance` decimal(20,8) NULL DEFAULT NULL COMMENT '当前综合余额',
  `simulated_balance` decimal(20,8) NULL DEFAULT NULL COMMENT '当前模拟余额',
  `last_check_time` datetime NULL DEFAULT NULL COMMENT '最后检查时间',
  `last_success_time` datetime NULL DEFAULT NULL COMMENT '最后成功查询时间',
  `last_error` varchar(300) NULL DEFAULT NULL COMMENT '最后查询错误摘要',
  `silence_until` datetime NULL DEFAULT NULL COMMENT '供应商静默截止时间',
  `consecutive_low` int(11) NOT NULL DEFAULT 0 COMMENT '连续低余额次数',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_balance_config_provider` (`provider_id`),
  KEY `idx_provider_balance_config_enabled_check` (`enabled`, `last_check_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商余额监控配置';

CREATE TABLE IF NOT EXISTS `aid_provider_balance_recipient` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '提醒人ID',
  `recipient_name` varchar(64) NOT NULL COMMENT '提醒人名称',
  `channel` varchar(16) NOT NULL COMMENT '通知渠道 EMAIL/SMS/WECHAT',
  `target_value` varchar(255) NOT NULL COMMENT '邮箱、手机号或微信公众号OpenID',
  `target_hash` char(64) NOT NULL COMMENT '接收目标SHA-256去重值',
  `display_value` varchar(255) NOT NULL COMMENT '脱敏展示值',
  `wechat_nickname` varchar(128) NULL DEFAULT NULL COMMENT '微信昵称',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `daily_report_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否接收邮件余额日报',
  `provider_ids` varchar(1000) NOT NULL COMMENT '逗号分隔的供应商ID',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_balance_recipient_target` (`channel`, `target_hash`),
  KEY `idx_provider_balance_recipient_enabled` (`enabled`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商余额提醒人';

CREATE TABLE IF NOT EXISTS `aid_provider_balance_snapshot` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '快照ID',
  `provider_id` bigint(20) NOT NULL COMMENT '供应商ID',
  `source_type` varchar(16) NOT NULL COMMENT '来源 API/SIMULATED/ERROR',
  `balance` decimal(20,8) NULL DEFAULT NULL COMMENT '余额',
  `currency` varchar(16) NULL DEFAULT NULL COMMENT '余额单位或币种',
  `status` varchar(16) NOT NULL COMMENT '状态',
  `precision_type` varchar(16) NULL DEFAULT NULL COMMENT '精度 EXACT/ESTIMATED/UNKNOWN',
  `detail_json` text NULL COMMENT '脱敏后的查询或计算摘要',
  `error_message` varchar(300) NULL DEFAULT NULL COMMENT '失败摘要',
  `checked_at` datetime NOT NULL COMMENT '检查时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_provider_balance_snapshot_provider_time` (`provider_id`, `checked_at`),
  KEY `idx_provider_balance_snapshot_status_time` (`status`, `checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商余额检查快照';

CREATE TABLE IF NOT EXISTS `aid_provider_balance_incident` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '告警事件ID',
  `provider_id` bigint(20) NOT NULL COMMENT '供应商ID',
  `severity` varchar(16) NOT NULL COMMENT '级别 WARNING/CRITICAL/RECOVERY',
  `status` varchar(16) NOT NULL COMMENT '状态 OPEN/ACKED/RESOLVED',
  `active_marker` tinyint(1) GENERATED ALWAYS AS (CASE WHEN `status` IN ('OPEN','ACKED') THEN 1 ELSE NULL END) STORED COMMENT '同一供应商仅允许一个活动事件',
  `trigger_source` varchar(16) NOT NULL COMMENT '触发来源 API/SIMULATED/ERROR/FORECAST',
  `balance` decimal(20,8) NULL DEFAULT NULL COMMENT '触发时余额',
  `threshold_amount` decimal(20,8) NULL DEFAULT NULL COMMENT '命中阈值',
  `currency` varchar(16) NULL DEFAULT NULL COMMENT '余额单位或币种',
  `reason` varchar(500) NOT NULL COMMENT '告警原因摘要',
  `opened_at` datetime NOT NULL COMMENT '首次触发时间',
  `last_triggered_at` datetime NOT NULL COMMENT '最后触发时间',
  `last_notified_at` datetime NULL DEFAULT NULL COMMENT '最后至少一个渠道发送成功时间',
  `next_notify_at` datetime NULL DEFAULT NULL COMMENT '下次允许提醒时间',
  `acknowledged_at` datetime NULL DEFAULT NULL COMMENT '确认时间',
  `acknowledged_by` varchar(64) NULL DEFAULT NULL COMMENT '确认人',
  `resolved_at` datetime NULL DEFAULT NULL COMMENT '恢复时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_balance_incident_active` (`provider_id`, `active_marker`),
  KEY `idx_provider_balance_incident_provider_status` (`provider_id`, `status`),
  KEY `idx_provider_balance_incident_notify` (`status`, `next_notify_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商余额告警事件';

CREATE TABLE IF NOT EXISTS `aid_provider_balance_delivery` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '发送记录ID',
  `incident_id` bigint(20) NULL DEFAULT NULL COMMENT '关联告警事件ID，日报为空',
  `provider_id` bigint(20) NULL DEFAULT NULL COMMENT '供应商ID，日报汇总时为空',
  `recipient_id` bigint(20) NOT NULL COMMENT '提醒人ID',
  `channel` varchar(16) NOT NULL COMMENT '通知渠道',
  `delivery_type` varchar(16) NOT NULL COMMENT '发送类型 ALERT/RECOVERY/DAILY/TEST',
  `status` varchar(16) NOT NULL COMMENT '状态 SUCCESS/FAILED',
  `message_id` varchar(128) NULL DEFAULT NULL COMMENT '渠道消息ID',
  `error_message` varchar(300) NULL DEFAULT NULL COMMENT '发送失败摘要',
  `attempted_at` datetime NOT NULL COMMENT '尝试发送时间',
  `succeeded_at` datetime NULL DEFAULT NULL COMMENT '发送成功时间',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_provider_balance_delivery_incident` (`incident_id`, `attempted_at`),
  KEY `idx_provider_balance_delivery_recipient` (`recipient_id`, `delivery_type`, `attempted_at`),
  KEY `idx_provider_balance_delivery_status_time` (`status`, `attempted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商余额通知发送记录';

CREATE TABLE IF NOT EXISTS `aid_provider_cost_ledger` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '成本台账ID',
  `event_key` varchar(128) NOT NULL COMMENT '幂等事件键',
  `provider_id` bigint(20) NOT NULL COMMENT '供应商ID',
  `model_id` bigint(20) NULL DEFAULT NULL COMMENT '模型ID',
  `task_id` bigint(20) NULL DEFAULT NULL COMMENT '媒体任务ID',
  `model_code` varchar(100) NULL DEFAULT NULL COMMENT '任务模型编码快照',
  `entry_type` varchar(16) NOT NULL COMMENT '条目类型 COST/ADJUSTMENT',
  `amount` decimal(20,8) NOT NULL DEFAULT 0.00000000 COMMENT '官方基础价金额',
  `balance_delta` decimal(20,8) NOT NULL DEFAULT 0.00000000 COMMENT '对模拟余额的增减值',
  `currency` varchar(16) NOT NULL DEFAULT 'CNY' COMMENT '余额单位或币种',
  `precision_type` varchar(16) NOT NULL DEFAULT 'EXACT' COMMENT '精度 EXACT/ESTIMATED',
  `pricing_version` varchar(64) NULL DEFAULT NULL COMMENT '任务定价快照版本',
  `occurred_at` datetime NOT NULL COMMENT '成本发生时间',
  `detail_json` text NULL COMMENT '不含敏感数据的计价摘要',
  `create_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_cost_ledger_event` (`event_key`),
  KEY `idx_provider_cost_ledger_provider_time` (`provider_id`, `occurred_at`),
  KEY `idx_provider_cost_ledger_task` (`task_id`, `entry_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商理论成本台账';

INSERT INTO `aid_config`
  (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`)
SELECT 'provider_balance', 'settings',
       '{"enabled":false,"dailyReportEnabled":false,"dailyReportTime":"09:00","defaultRepeatIntervalMinutes":360,"failureRetryMinutes":10,"snapshotRetentionDays":90,"deliveryRetentionDays":180,"wechatProviderField":"thing1","wechatBalanceField":"amount2","wechatStatusField":"thing3","wechatTimeField":"time4"}',
       '供应商余额监控全局设置', '0', 1, NOW(), 'system', 'system', NOW(), '默认关闭，需完成供应商和提醒人配置后手动开启', 0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `aid_config` WHERE `category` = 'provider_balance' AND `config_name` = 'settings'
);

-- 将明确的供应商余额不足规则与用户平台余额不足分离，通用配额耗尽仍保持原错误类型。
UPDATE `aid_provider_error_rule`
SET `error_code` = 'PROVIDER_BALANCE_INSUFFICIENT',
    `rule_name` = '供应商余额不足(英文)',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `id` = 12
  AND `error_code` = 'USER_BALANCE_NOT_ENOUGH';

INSERT INTO `aid_provider_error_rule`
  (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`, `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'deepseek', NULL, 'DeepSeek 账户余额不足', 'HTTP_STATUS', '402', NULL, 0, 'PROVIDER_BALANCE_INSUFFICIENT', NULL, 3, 1, 1, 'DeepSeek 官方 HTTP 402 余额不足响应', 'system', NOW(), 'system', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `aid_provider_error_rule`
  WHERE `provider_code`='deepseek' AND `match_type`='HTTP_STATUS' AND `match_pattern`='402');

INSERT INTO `aid_provider_error_rule`
  (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`, `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'minimax', NULL, 'MiniMax 账户余额不足', 'CODE', '1008', NULL, 0, 'PROVIDER_BALANCE_INSUFFICIENT', NULL, 3, 1, 1, 'MiniMax 官方余额不足错误码', 'system', NOW(), 'system', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `aid_provider_error_rule`
  WHERE `provider_code`='minimax' AND `match_type`='CODE' AND `match_pattern`='1008');

INSERT INTO `aid_provider_error_rule`
  (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`, `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'vidu', NULL, 'Vidu Credit 不足', 'KEYWORD', 'CreditInsufficient,credit insufficient,insufficient credit', NULL, 0, 'PROVIDER_BALANCE_INSUFFICIENT', NULL, 3, 1, 1, 'Vidu Credit 不足响应关键词', 'system', NOW(), 'system', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `aid_provider_error_rule`
  WHERE `provider_code`='vidu' AND `match_type`='KEYWORD'
    AND `match_pattern`='CreditInsufficient,credit insufficient,insufficient credit');

-- 供应商余额监控菜单及按钮权限；不自动授予非管理员角色。
SET @provider_balance_menu_id := (SELECT `menu_id` FROM `sys_menu`
  WHERE `perms`='aid:providerbalance:list' LIMIT 1);
INSERT INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT IF(EXISTS(SELECT 1 FROM `sys_menu` WHERE `menu_id`=1358),NULL,1358), '供应商余额监控', 1260, 8, 'providerbalance', 'aid/providerbalance/index', NULL, '', 1, 0, 'C', '0', '0', 'aid:providerbalance:list', 'money', 'system', NOW(), 'system', NOW(), '供应商余额检测、模拟余额与多渠道提醒'
FROM DUAL WHERE @provider_balance_menu_id IS NULL;
SET @provider_balance_menu_id := (SELECT `menu_id` FROM `sys_menu`
  WHERE `perms`='aid:providerbalance:list' LIMIT 1);

INSERT INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT IF(EXISTS(SELECT 1 FROM `sys_menu` WHERE `menu_id`=1359),NULL,1359), '余额监控查询', @provider_balance_menu_id, 1, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:providerbalance:query', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:providerbalance:query');

INSERT INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT IF(EXISTS(SELECT 1 FROM `sys_menu` WHERE `menu_id`=1360),NULL,1360), '余额监控配置', @provider_balance_menu_id, 2, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:providerbalance:edit', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:providerbalance:edit');

INSERT INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT IF(EXISTS(SELECT 1 FROM `sys_menu` WHERE `menu_id`=1361),NULL,1361), '提醒渠道测试', @provider_balance_menu_id, 3, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'aid:providerbalance:test', '#', 'system', NOW(), 'system', NOW(), ''
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms`='aid:providerbalance:test');
-- ===== END 030-provider-balance.sql =====

-- ===== BEGIN 040-account-security.sql =====
-- ============================================================================
-- 账号安全
-- ============================================================================
-- 账号注销再注册限制及稳定用户 ID 登录历史。
-- 兼容 MySQL 5.7；可重复执行；不覆盖已有配置值。

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS `aid_account_cancellation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '注销用户ID',
  `identity_type` varchar(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '身份类型',
  `identity_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '身份标识SHA-256哈希',
  `cancelled_at` datetime NOT NULL COMMENT '最近注销时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'system' COMMENT '创建者',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_cancel_identity` (`identity_type`, `identity_hash`) USING BTREE,
  INDEX `idx_cancel_user` (`user_id`) USING BTREE,
  INDEX `idx_cancel_time` (`cancelled_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号注销身份记录';

INSERT INTO `aid_config`
  (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`)
VALUES
  ('account_security', 'cancel_re_registration_enabled', 'true', '注销后再次注册限制开关', '0', 1, NOW(), 'system', 'system', NOW(), '关闭后注销账号可立即再次注册', 0),
  ('account_security', 'cancel_re_registration_days', '15', '注销后再次注册限制天数', '0', 2, NOW(), 'system', 'system', NOW(), '开启限制时生效，允许设置1至3650天', 0)
ON DUPLICATE KEY UPDATE `config_name` = VALUES(`config_name`), `del_flag` = '0';

-- 账号安全中心按稳定用户ID查询登录历史，避免手机号或邮箱换绑后历史记录失联。
SELECT COUNT(*) INTO @login_user_id_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'sys_logininfor'
  AND COLUMN_NAME = 'user_id';
SET @ddl = IF(
  @login_user_id_exists = 0,
  'ALTER TABLE `sys_logininfor` ADD COLUMN `user_id` bigint(20) NULL DEFAULT NULL COMMENT ''用户ID'' AFTER `info_id`',
  'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SELECT COUNT(*) INTO @login_user_time_index_exists
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'sys_logininfor'
  AND INDEX_NAME = 'idx_sys_logininfor_uid_lt';
SET @ddl = IF(
  @login_user_time_index_exists = 0,
  'ALTER TABLE `sys_logininfor` ADD INDEX `idx_sys_logininfor_uid_lt` (`user_id`, `login_time`)',
  'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
-- ===== END 040-account-security.sql =====

-- ===== BEGIN 050-media-task-eta.sql =====
-- ============================================================================
-- 媒体任务预计完成时间
-- ============================================================================
-- 媒体任务不可变终态时间、ETA 聚合直方图及默认参数。
-- 兼容 MySQL 5.7；可重复执行；不覆盖已有配置值。

SET @schema_name = DATABASE();

SELECT COUNT(*) INTO @terminal_time_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'aid_media_task'
  AND COLUMN_NAME = 'terminal_time';
SET @ddl = IF(
  @terminal_time_exists = 0,
  'ALTER TABLE `aid_media_task` ADD COLUMN `terminal_time` datetime NULL COMMENT ''首次进入终态的不可变时刻，用于准确统计生成耗时''',
  'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

CREATE TABLE IF NOT EXISTS `aid_media_eta_stat` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `bucket_date` date NOT NULL COMMENT '自然日统计桶',
  `phase` varchar(20) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '阶段：QUEUE/PROCESSING',
  `profile_key` char(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '协议、模型、媒体与工作量画像哈希',
  `provider_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '媒体协议/服务商维度',
  `model_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '模型编码',
  `media_type` varchar(20) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '媒体类型',
  `workload_key` varchar(100) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL DEFAULT 'default' COMMENT '时长、分辨率、模式等低基数工作量档位',
  `sample_count` bigint(20) NOT NULL DEFAULT 0 COMMENT '成功样本数',
  `total_duration_ms` bigint(20) NOT NULL DEFAULT 0 COMMENT '总耗时毫秒',
  `max_duration_ms` bigint(20) NOT NULL DEFAULT 0 COMMENT '最大耗时毫秒',
  `bucket_1s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_5s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_15s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_30s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_60s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_120s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_300s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_600s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_1200s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_2400s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_4800s` bigint(20) NOT NULL DEFAULT 0,
  `bucket_inf` bigint(20) NOT NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_eta_bucket_phase_profile` (`bucket_date`, `phase`, `profile_key`) USING BTREE,
  INDEX `idx_eta_profile_lookup` (`phase`, `profile_key`, `bucket_date`) USING BTREE,
  INDEX `idx_eta_lookup` (`phase`, `media_type`, `provider_key`(32), `model_code`(64), `bucket_date`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体任务预计耗时聚合直方图';

INSERT INTO `aid_config`
  (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`)
VALUES
  ('media_eta', 'enabled', 'true', '任务预计时间开关', '0', 1, NOW(), 'system', 'system', NOW(), '开启图片、视频等媒体任务的预计进度与剩余时间', 0),
  ('media_eta', 'window_days', '7', '统计窗口天数', '0', 2, NOW(), 'system', 'system', NOW(), 'P50/P90读取最近多少天的成功样本', 0),
  ('media_eta', 'retention_days', '30', '统计保留天数', '0', 3, NOW(), 'system', 'system', NOW(), '聚合直方图的保留天数', 0),
  ('media_eta', 'min_samples', '20', '画像最小样本数', '0', 4, NOW(), 'system', 'system', NOW(), '低于该数量时回退到模型或媒体类型统计', 0),
  ('media_eta', 'cache_ttl_seconds', '60', '统计缓存秒数', '0', 5, NOW(), 'system', 'system', NOW(), '服务端统计结果的进程内缓存时间', 0),
  ('media_eta', 'image_p50_seconds', '60', '图片默认P50秒数', '0', 6, NOW(), 'system', 'system', NOW(), '图片样本不足时的中位耗时', 0),
  ('media_eta', 'image_p90_seconds', '180', '图片默认P90秒数', '0', 7, NOW(), 'system', 'system', NOW(), '图片样本不足时的保守耗时', 0),
  ('media_eta', 'video_p50_seconds', '300', '视频默认P50秒数', '0', 8, NOW(), 'system', 'system', NOW(), '视频样本不足时的中位耗时', 0),
  ('media_eta', 'video_p90_seconds', '900', '视频默认P90秒数', '0', 9, NOW(), 'system', 'system', NOW(), '视频样本不足时的保守耗时', 0),
  ('media_eta', 'audio_p50_seconds', '60', '音频默认P50秒数', '0', 10, NOW(), 'system', 'system', NOW(), '音频样本不足时的中位耗时', 0),
  ('media_eta', 'audio_p90_seconds', '180', '音频默认P90秒数', '0', 11, NOW(), 'system', 'system', NOW(), '音频样本不足时的保守耗时', 0),
  ('media_eta', 'queue_p50_seconds', '15', '排队默认P50秒数', '0', 12, NOW(), 'system', 'system', NOW(), '排队样本不足时的中位等待时间', 0),
  ('media_eta', 'queue_p90_seconds', '60', '排队默认P90秒数', '0', 13, NOW(), 'system', 'system', NOW(), '排队样本不足时的保守等待时间', 0)
ON DUPLICATE KEY UPDATE `config_name` = VALUES(`config_name`);
-- ===== END 050-media-task-eta.sql =====

-- ===== BEGIN 060-ai-model-orchestration.sql =====
-- ============================================================================
-- AI 模型编排
-- ============================================================================
-- AI 业务编排统一入口及既有菜单权限迁移。
-- 兼容 MySQL 5.7；可重复执行；保留既有细粒度权限。

-- AI业务编排统一入口：保留原智能体和矩阵路由作为隐藏兼容入口，避免旧链接与权限失效。
UPDATE `sys_menu`
SET `menu_name` = 'AI业务编排',
    `path` = 'orchestration',
    `component` = 'aid/orchestration/index',
    `perms` = '',
    `remark` = '模型池、智能体与策略矩阵统一编排入口',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `menu_id` = 1265;

UPDATE `sys_menu`
SET `menu_name` = CASE `menu_id`
      WHEN 1266 THEN '模型池查询'
      WHEN 1267 THEN '模型池新增'
      WHEN 1268 THEN '模型池修改'
      WHEN 1269 THEN '模型池删除'
      WHEN 1270 THEN '模型池导出'
      ELSE `menu_name`
    END,
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `menu_id` IN (1266, 1267, 1268, 1269, 1270);

UPDATE `sys_menu`
SET `visible` = '1',
    `remark` = CASE `menu_id`
      WHEN 1291 THEN '智能体配置兼容入口，由AI业务编排统一展示'
      WHEN 1311 THEN '策略矩阵兼容入口，由AI业务编排统一展示'
      ELSE `remark`
    END,
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `menu_id` IN (1291, 1311);

-- 旧入口曾直接承载列表权限；先迁移到“模型池查询”按钮，避免统一入口清空路由权限后丢失查询能力。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT DISTINCT `role_id`, 1266
FROM `sys_role_menu`
WHERE `menu_id` = 1265;

-- 已拥有任一旧入口的角色自动获得统一入口；原按钮权限保持不变。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT DISTINCT `role_id`, 1265
FROM `sys_role_menu`
WHERE `menu_id` IN (
  1265, 1266, 1267, 1268, 1269, 1270,
  1291, 1292, 1293, 1294, 1295,
  1311, 1312, 1313, 1314, 1315
);
-- ===== END 060-ai-model-orchestration.sql =====

-- ===== BEGIN 070-text-model-capabilities.sql =====
-- ============================================================================
-- 文本模型多模态能力
-- ============================================================================
-- 文本模型统一调用控制、多模态能力与模型退役。
-- 兼容 MySQL 5.7；可重复执行。

-- 仅迁移明确的官方模型基线；用户自定义模型与已维护能力保持不变。
UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE','VIDEO'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.supportsVideoInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 2048, '$.maxInputVideos', 64,
      '$.contextWindowTokens', 1000000, '$.maxOutputTokens', 65536,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('true', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('true', '$'),
      '$.reasoningApiStyle', 'QWEN',
      '$.outputTokenApiField', 'max_completion_tokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('false', '$'),
      '$.allowedReasoningLevels', JSON_ARRAY(),
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY(
        'https://help.aliyun.com/zh/model-studio/getting-started/models',
        'https://help.aliyun.com/zh/model-studio/deep-thinking'))
WHERE `model_code` IN ('qwen3.7-max','qwen3.7-plus') AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.contextWindowTokens', 1000000, '$.maxOutputTokens', 384000,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('true', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('false', '$'),
      '$.reasoningApiStyle', 'DEEPSEEK', '$.outputTokenApiField', 'max_tokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('true', '$'),
      '$.defaultReasoningLevel', 'high',
      '$.allowedReasoningLevels', JSON_ARRAY('high','max'),
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY('https://api-docs.deepseek.com/zh-cn/quick_start/pricing'))
WHERE `model_code` IN ('deepseek-v4-flash','deepseek-v4-pro') AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE','VIDEO','AUDIO','DOCUMENT'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.supportsVideoInput', JSON_EXTRACT('true', '$'),
      '$.supportsAudioInput', JSON_EXTRACT('true', '$'),
      '$.supportsDocumentInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 3600, '$.maxInputVideos', 10,
      '$.maxInputAudios', 10, '$.maxInputDocuments', 10,
      '$.maxInputImageFileSizeMb', 2048, '$.maxInputVideoFileSizeMb', 2048,
      '$.maxInputAudioFileSizeMb', 2048, '$.maxInputDocumentFileSizeMb', 50,
      '$.maxInputVideoDurationSeconds', 3600,
      '$.maxInputAudioDurationSeconds', 34200,
      '$.maxInputDocumentPages', 1000,
      '$.inputImageFormats', JSON_ARRAY('jpeg','jpg','png','webp','heic','heif'),
      '$.inputVideoFormats', JSON_ARRAY('mp4','mpeg','mov','avi','flv','mpg','webm','wmv','3gpp'),
      '$.inputAudioFormats', JSON_ARRAY('wav','mp3','aiff','aac','ogg','flac'),
      '$.inputDocumentFormats', JSON_ARRAY('pdf','txt','html','css','md','csv','xml'),
      '$.contextWindowTokens', 1048576, '$.maxOutputTokens', 65536,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('false', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('false', '$'),
      '$.reasoningApiStyle', 'GEMINI', '$.outputTokenApiField', 'maxOutputTokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('true', '$'),
      '$.defaultReasoningLevel', 'medium',
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY(
        'https://ai.google.dev/gemini-api/docs/models',
        'https://ai.google.dev/gemini-api/docs/file-input-methods',
        'https://ai.google.dev/gemini-api/docs/document-processing'))
WHERE `model_code` IN ('gemini-3.1-pro-preview','gemini-3-flash-preview',
                       'gemini-3.1-flash-lite','gemini-3.5-flash') AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.allowedReasoningLevels', JSON_ARRAY('low','medium','high'))
WHERE `model_code` = 'gemini-3.1-pro-preview'
  AND JSON_UNQUOTE(JSON_EXTRACT(`capability_json`, '$.capabilityVerifiedAt')) = '2026-09-02';

UPDATE `aid_ai_model`
SET `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.allowedReasoningLevels', JSON_ARRAY('minimal','low','medium','high'))
WHERE `model_code` IN ('gemini-3-flash-preview','gemini-3.1-flash-lite','gemini-3.5-flash')
  AND JSON_UNQUOTE(JSON_EXTRACT(`capability_json`, '$.capabilityVerifiedAt')) = '2026-09-02';

UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 10,
      '$.inputImageFormats', JSON_ARRAY('jpeg','jpg','png','webp','gif'),
      '$.maxInputImageFileSizeMb', 0,
      '$.contextWindowTokens', 1050000, '$.maxOutputTokens', 128000,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('false', '$'),
      '$.reasoningApiStyle', 'OPENAI', '$.outputTokenApiField', 'max_completion_tokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('false', '$'),
      '$.defaultReasoningLevel', 'low',
      '$.allowedReasoningLevels', JSON_ARRAY('low','medium','high','xhigh'),
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY('https://developers.openai.com/api/docs/models'))
WHERE `model_code` IN ('gpt-5.4','gpt-5.5') AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 10,
      '$.inputImageFormats', JSON_ARRAY('jpeg','jpg','png','webp','gif'),
      '$.maxInputImageFileSizeMb', 0,
      '$.contextWindowTokens', 1050000, '$.maxOutputTokens', 128000,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('false', '$'),
      '$.reasoningApiStyle', 'OPENAI', '$.outputTokenApiField', 'max_completion_tokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('false', '$'),
      '$.defaultReasoningLevel', 'medium',
      '$.allowedReasoningLevels', JSON_ARRAY('low','medium','high','xhigh','max'),
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY('https://developers.openai.com/api/docs/models/gpt-5.6-sol'))
WHERE `model_code` = 'gpt-5.6' AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 10,
      '$.contextWindowTokens', 512000, '$.maxOutputTokens', 65536,
      '$.supportsReasoning', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningDisable', JSON_EXTRACT('true', '$'),
      '$.supportsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.returnsReasoningContent', JSON_EXTRACT('false', '$'),
      '$.supportsReasoningBudget', JSON_EXTRACT('false', '$'),
      '$.reasoningApiStyle', 'AGNES', '$.outputTokenApiField', 'max_tokens',
      '$.defaultReasoningEnabled', JSON_EXTRACT('false', '$'),
      '$.allowedReasoningLevels', JSON_ARRAY(),
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY('https://wiki.agnes-ai.cn/models/agnes-2.5-flash'))
WHERE `model_code` = 'agnes-2.5-flash' AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

UPDATE `aid_ai_model`
SET `supports_image_input` = 1, `supports_multi_image_input` = 1,
    `capability_json` = JSON_SET(CASE WHEN JSON_VALID(`capability_json`) THEN `capability_json` ELSE JSON_OBJECT() END,
      '$.inputModalities', JSON_ARRAY('TEXT','IMAGE','VIDEO','DOCUMENT'),
      '$.outputModalities', JSON_ARRAY('TEXT'),
      '$.supportsImageInput', JSON_EXTRACT('true', '$'),
      '$.supportsVideoInput', JSON_EXTRACT('true', '$'),
      '$.supportsDocumentInput', JSON_EXTRACT('true', '$'),
      '$.maxInputImages', 10, '$.maxInputVideos', 10, '$.maxInputDocuments', 10,
      '$.inputImageFormats', JSON_ARRAY('jpeg','jpg','png','webp'),
      '$.inputVideoFormats', JSON_ARRAY('mp4','mov'),
      '$.inputDocumentFormats', JSON_ARRAY('pdf'),
      '$.contextWindowTokens', 256000,
      '$.capabilityVerifiedAt', '2026-09-02',
      '$.capabilitySourceUrls', JSON_ARRAY('https://www.volcengine.com/docs/82379/1330310'))
WHERE `model_code` IN ('doubao-seed-2.0-pro-260215','doubao-seed-2-1-pro-260628') AND (capability_json IS NULL OR TRIM(capability_json) IN ('','{}'));

-- 退役模型保留历史任务和账单引用，但退出模型目录、项目配置和智能体池。
SET @agnes20_retired_id := (
  SELECT `id` FROM `aid_ai_model` WHERE `model_code` = 'agnes-2.0-flash' LIMIT 1
);
SET @agnes25alpha_retired_id := (
  SELECT `id` FROM `aid_ai_model` WHERE `model_code` = 'agnes-2.5-pro-alpha' LIMIT 1
);

UPDATE `aid_ai_model_func_config`
SET `model_ids` = CAST(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
    CAST(`model_ids` AS CHAR),
    CONCAT('[', @agnes20_retired_id, ']'), '[]'),
    CONCAT('[', @agnes20_retired_id, ', '), '['),
    CONCAT(', ', @agnes20_retired_id, ']'), ']'),
    CONCAT(', ', @agnes20_retired_id, ', '), ', '),
    CONCAT('[', @agnes20_retired_id, ','), '['),
    CONCAT(',', @agnes20_retired_id, ']'), ']'),
    CONCAT(',', @agnes20_retired_id, ','), ',') AS JSON)
WHERE @agnes20_retired_id IS NOT NULL
  AND JSON_CONTAINS(COALESCE(`model_ids`, JSON_ARRAY()), CAST(CONCAT(@agnes20_retired_id) AS JSON));

UPDATE `aid_ai_model_func_config`
SET `model_ids` = CAST(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
    CAST(`model_ids` AS CHAR),
    CONCAT('[', @agnes25alpha_retired_id, ']'), '[]'),
    CONCAT('[', @agnes25alpha_retired_id, ', '), '['),
    CONCAT(', ', @agnes25alpha_retired_id, ']'), ']'),
    CONCAT(', ', @agnes25alpha_retired_id, ', '), ', '),
    CONCAT('[', @agnes25alpha_retired_id, ','), '['),
    CONCAT(',', @agnes25alpha_retired_id, ']'), ']'),
    CONCAT(',', @agnes25alpha_retired_id, ','), ',') AS JSON)
WHERE @agnes25alpha_retired_id IS NOT NULL
  AND JSON_CONTAINS(COALESCE(`model_ids`, JSON_ARRAY()), CAST(CONCAT(@agnes25alpha_retired_id) AS JSON));

UPDATE `aid_agent`
SET `model_code` = 'agnes-2.5-flash', `update_by` = 'system', `update_time` = CURRENT_TIMESTAMP
WHERE `model_code` IN ('agnes-2.5-pro-alpha','agnes-2.0-flash') AND `del_flag` = '0';

UPDATE `aid_ai_model`
SET `status` = '1', `del_flag` = '1', `update_by` = 'system', `update_time` = CURRENT_TIMESTAMP
WHERE `model_code` IN ('agnes-2.5-pro-alpha','agnes-2.0-flash');

UPDATE `aid_project_gen_config`
SET `del_flag` = '1', `update_by` = 'system', `update_time` = CURRENT_TIMESTAMP,
    `remark` = '引用模型已退役，请重新选择'
WHERE `model_code` IN ('agnes-2.5-pro-alpha','agnes-2.0-flash') AND `del_flag` = '0';

UPDATE `aid_gen_agent_pool`
SET `status` = '1', `del_flag` = '1', `update_by` = 'system', `update_time` = CURRENT_TIMESTAMP,
    `remark` = '引用模型已退役'
WHERE `model_code` IN ('agnes-2.5-pro-alpha','agnes-2.0-flash') AND `del_flag` = '0';
-- ===== END 070-text-model-capabilities.sql =====

-- ===== BEGIN 080-asset-primary-image.sql =====
-- ============================================================================
-- 角色、道具与场景主图
-- ============================================================================
-- 角色、道具形态主图状态收口（MySQL 5.7，可重复执行）。
-- 同一形态存在多张主图时，保留当前列表顺序中的首张：sort_order、create_time、id 依次升序。
UPDATE `aid_role_prop_scene_form_image` current_image
INNER JOIN `aid_role_prop_scene_form` form_record
        ON form_record.`id` = current_image.`form_id`
       AND form_record.`del_flag` = '0'
INNER JOIN `aid_role_prop_scene` asset_record
        ON asset_record.`id` = form_record.`asset_id`
       AND asset_record.`asset_type` IN ('character', 'prop')
       AND asset_record.`del_flag` = '0'
INNER JOIN `aid_role_prop_scene_form_image` earlier_image
        ON earlier_image.`form_id` = current_image.`form_id`
       AND earlier_image.`is_use` = 1
       AND earlier_image.`del_flag` = '0'
       AND (
            earlier_image.`sort_order` < current_image.`sort_order`
            OR (
                earlier_image.`sort_order` = current_image.`sort_order`
                AND earlier_image.`create_time` IS NULL
                AND current_image.`create_time` IS NOT NULL
            )
            OR (
                earlier_image.`sort_order` = current_image.`sort_order`
                AND earlier_image.`create_time` IS NOT NULL
                AND current_image.`create_time` IS NOT NULL
                AND earlier_image.`create_time` < current_image.`create_time`
            )
            OR (
                earlier_image.`sort_order` = current_image.`sort_order`
                AND (
                    earlier_image.`create_time` = current_image.`create_time`
                    OR (earlier_image.`create_time` IS NULL AND current_image.`create_time` IS NULL)
                )
                AND earlier_image.`id` < current_image.`id`
            )
       )
SET current_image.`is_use` = 0,
    current_image.`update_time` = NOW(),
    current_image.`update_by` = 'system'
WHERE current_image.`is_use` = 1
  AND current_image.`del_flag` = '0';
-- ===== END 080-asset-primary-image.sql =====

-- ===== BEGIN 090-skill-runtime.sql =====
-- ============================================================================
-- Skill Runtime
-- ============================================================================
-- Skill Runtime、后台管理能力与内置剧本 Skill。
-- MySQL 5.7；可重复执行。只初始化 Skill 自身结构、公开包与权限，不改写智能体或风格数据。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `aid_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Skill ID',
  `skill_code` varchar(64) NOT NULL COMMENT '稳定编码',
  `name` varchar(100) NOT NULL COMMENT '名称',
  `description` varchar(1000) DEFAULT NULL COMMENT '说明',
  `capability_description` varchar(2000) DEFAULT NULL COMMENT '面向调用方的能力介绍',
  `icon_url` varchar(500) DEFAULT NULL COMMENT '图标链接',
  `owner_type` varchar(20) NOT NULL DEFAULT 'PLATFORM' COMMENT '所有者类型',
  `owner_user_id` bigint DEFAULT NULL COMMENT '所有者用户',
  `visibility` varchar(20) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PUBLIC/PRIVATE',
  `invocation_scope` varchar(20) NOT NULL DEFAULT 'ENTRYPOINT' COMMENT 'ENTRYPOINT/INTERNAL',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前不可变可执行版本',
  `executor_type` varchar(30) NOT NULL DEFAULT 'PROMPT' COMMENT '执行器类型',
  `model_code` varchar(100) NOT NULL COMMENT '文本模型编码',
  `input_schema_json` longtext NOT NULL COMMENT '输入约束',
  `output_schema_json` longtext NOT NULL COMMENT '输出约束',
  `system_prompt` longtext NOT NULL COMMENT '系统提示词',
  `reasoning_policy` varchar(20) NOT NULL DEFAULT 'DISABLED' COMMENT 'DISABLED/OPTIONAL/REQUIRED',
  `default_reasoning_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '默认思考开关',
  `default_reasoning_level` varchar(20) DEFAULT NULL COMMENT '默认思考档位',
  `show_reasoning_default` tinyint(1) NOT NULL DEFAULT 0 COMMENT '默认实时展示公开思考内容',
  `reasoning_budget_tokens` int NOT NULL DEFAULT 0 COMMENT '总输出上限内的思考子预算',
  `max_output_tokens` int NOT NULL DEFAULT 8192 COMMENT '总输出上限',
  `context_window_tokens` int NOT NULL DEFAULT 128000 COMMENT '上下文窗口',
  `safety_margin_tokens` int NOT NULL DEFAULT 2048 COMMENT '安全余量',
  `definition_json` longtext DEFAULT NULL COMMENT '扩展元数据',
  `config_hash` varchar(64) NOT NULL COMMENT '当前配置摘要',
  `status` char(1) NOT NULL DEFAULT '1' COMMENT '0启用 1停用',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '0正常 1删除',
  `create_by` varchar(64) DEFAULT '',
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT '',
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_code` (`skill_code`),
  KEY `idx_skill_admin` (`del_flag`,`status`,`update_time`,`id`),
  KEY `idx_skill_owner` (`owner_type`,`owner_user_id`,`status`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 稳定身份';

CREATE TABLE IF NOT EXISTS `aid_skill_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `skill_id` bigint NOT NULL,
  `skill_version_id` bigint DEFAULT NULL,
  `skill_config_hash` varchar(64) NOT NULL COMMENT '执行时配置摘要',
  `model_code` varchar(100) NOT NULL COMMENT '实际模型编码',
  `project_id` bigint DEFAULT NULL,
  `episode_id` bigint DEFAULT NULL,
  `invoke_source` varchar(20) NOT NULL DEFAULT 'API',
  `client_request_id` varchar(64) NOT NULL,
  `idempotency_scope_hash` varchar(64) DEFAULT NULL,
  `generation` int NOT NULL DEFAULT 0,
  `client_request_digest` varchar(64) DEFAULT NULL,
  `execution_snapshot_digest` varchar(64) DEFAULT NULL,
  `resolved_config_digest` varchar(64) DEFAULT NULL,
  `root_run_id` bigint DEFAULT NULL,
  `parent_run_id` bigint DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `stage` varchar(30) DEFAULT NULL,
  `action_mode` varchar(30) DEFAULT NULL,
  `quality_mode` varchar(20) DEFAULT NULL,
  `input_json` longtext NOT NULL,
  `output_json` longtext DEFAULT NULL,
  `effective_reasoning_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `effective_reasoning_level` varchar(20) DEFAULT NULL,
  `show_reasoning` tinyint(1) NOT NULL DEFAULT 0,
  `reasoning_budget_tokens` int NULL DEFAULT NULL COMMENT '总输出上限内的思考子预算',
  `error_message` varchar(500) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `del_flag` char(1) NOT NULL DEFAULT '0',
  `create_by` varchar(64) DEFAULT '',
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT '',
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_run_scope_generation` (`idempotency_scope_hash`,`generation`),
  KEY `idx_skill_run_user` (`user_id`,`create_time`,`id`),
  KEY `idx_skill_run_reconcile` (`status`,`del_flag`,`update_time`,`id`),
  KEY `idx_skill_run_admin` (`skill_id`,`user_id`,`status`,`create_time`,`id`),
  KEY `idx_skill_run_version` (`skill_version_id`,`status`,`create_time`,`id`),
  KEY `idx_skill_run_project` (`user_id`,`project_id`,`episode_id`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 独立运行';

-- 通用 Skill Runtime 纵向能力。
-- MySQL 5.7，可重复执行；复用 aid_media_task、现有队列、计费、Provider 与补偿状态机。
SET NAMES utf8mb4;

-- aid_skill 继续作为稳定 identity/current pointer 和旧读模型。
SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill' AND column_name='invocation_scope');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill ADD COLUMN invocation_scope varchar(20) NOT NULL DEFAULT ''ENTRYPOINT'' COMMENT ''ENTRYPOINT/INTERNAL'' AFTER visibility',
  'SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill' AND column_name='current_version_id');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill ADD COLUMN current_version_id bigint DEFAULT NULL COMMENT ''当前不可变可执行版本'' AFTER invocation_scope',
  'SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS aid_skill_draft (
  id bigint NOT NULL AUTO_INCREMENT, skill_id bigint NOT NULL, owner_user_id bigint DEFAULT NULL,
  draft_json longtext NOT NULL, draft_digest varchar(64) NOT NULL, status varchar(20) NOT NULL DEFAULT 'EDITING',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_skill_draft_owner (owner_user_id,skill_id,status,update_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 用户编辑草稿，不可直接执行';

CREATE TABLE IF NOT EXISTS aid_skill_version (
  id bigint NOT NULL AUTO_INCREMENT, skill_id bigint NOT NULL, version_code varchar(64) NOT NULL,
  visibility varchar(20) NOT NULL, invocation_scope varchar(20) NOT NULL,
  publish_status varchar(20) NOT NULL DEFAULT 'PRIVATE', executor_type varchar(30) NOT NULL,
  model_code varchar(100) NOT NULL, package_digest varchar(64) NOT NULL,
  manifest_json longtext NOT NULL, input_schema_json longtext NOT NULL, output_schema_json longtext NOT NULL,
  system_prompt longtext NOT NULL COMMENT '兼容现有提示词；大知识资源使用可定位URI',
  definition_json longtext DEFAULT NULL, max_output_tokens int NOT NULL,
  context_window_tokens int NOT NULL, safety_margin_tokens int NOT NULL,
  status char(1) NOT NULL DEFAULT '0', del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL, remark varchar(500) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_version (skill_id,version_code),
  UNIQUE KEY uk_skill_package_digest (package_digest),
  KEY idx_skill_version_resolve (skill_id,status,del_flag,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 不可变可执行版本';

SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_version' AND column_name='max_output_tokens');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_version ADD COLUMN max_output_tokens int NOT NULL DEFAULT 8192 AFTER definition_json','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_version' AND column_name='context_window_tokens');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_version ADD COLUMN context_window_tokens int NOT NULL DEFAULT 128000 AFTER max_output_tokens','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_version' AND column_name='safety_margin_tokens');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_version ADD COLUMN safety_margin_tokens int NOT NULL DEFAULT 4096 AFTER context_window_tokens','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS aid_skill_resource (
  id bigint NOT NULL AUTO_INCREMENT, skill_version_id bigint NOT NULL, resource_key varchar(200) NOT NULL,
  resource_type varchar(30) NOT NULL, object_key varchar(1000) NOT NULL, content_digest varchar(64) NOT NULL,
  mime_type varchar(100) DEFAULT NULL, size_bytes bigint NOT NULL DEFAULT 0,
  route_json text DEFAULT NULL, status char(1) NOT NULL DEFAULT '0', del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_resource (skill_version_id,resource_key),
  KEY idx_skill_resource_digest (content_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill不可变资源索引，支持classpath或对象存储URI';

SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='route_json');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_resource ADD COLUMN route_json text DEFAULT NULL AFTER size_bytes','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='status');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_resource ADD COLUMN status char(1) NOT NULL DEFAULT ''0'' AFTER route_json','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='del_flag');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_resource ADD COLUMN del_flag char(1) NOT NULL DEFAULT ''0'' AFTER status','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='update_by');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_resource ADD COLUMN update_by varchar(64) DEFAULT '''' AFTER create_time','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='update_time');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_resource ADD COLUMN update_time datetime DEFAULT NULL AFTER update_by','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS aid_skill_relation (
  id bigint NOT NULL AUTO_INCREMENT, parent_version_id bigint NOT NULL, child_skill_id bigint NOT NULL,
  child_version_id bigint NOT NULL, relation_type varchar(30) NOT NULL, relation_key varchar(64) NOT NULL,
  required_flag tinyint(1) NOT NULL DEFAULT 1, del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_relation (parent_version_id,relation_type,relation_key),
  KEY idx_skill_relation_child (child_version_id,del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill固定子能力及依赖关系';

CREATE TABLE IF NOT EXISTS aid_skill_installation (
  id bigint NOT NULL AUTO_INCREMENT, user_id bigint DEFAULT NULL, skill_id bigint NOT NULL,
  skill_version_id bigint NOT NULL, config_json text DEFAULT NULL, resolved_config_digest varchar(64) NOT NULL,
  permission_digest varchar(64) NOT NULL, status char(1) NOT NULL DEFAULT '0', del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_installation (user_id,skill_id),
  KEY idx_skill_install_version (skill_version_id,status,del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill安装及无权限配置快照';

-- 兼容扩展现有 aid_skill_run；旧行保持 NULL，新 Runtime 写入完整快照。
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='skill_version_id');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN skill_version_id bigint DEFAULT NULL AFTER skill_id','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='project_id');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN project_id bigint DEFAULT NULL AFTER session_id','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='episode_id');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN episode_id bigint DEFAULT NULL AFTER project_id','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='idempotency_scope_hash');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN idempotency_scope_hash varchar(64) DEFAULT NULL AFTER client_request_id','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='generation');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN generation int NOT NULL DEFAULT 0 AFTER idempotency_scope_hash','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='client_request_digest');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN client_request_digest varchar(64) DEFAULT NULL AFTER generation','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='execution_snapshot_digest');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN execution_snapshot_digest varchar(64) DEFAULT NULL AFTER client_request_digest','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='resolved_config_digest');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN resolved_config_digest varchar(64) DEFAULT NULL AFTER execution_snapshot_digest','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='root_run_id');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN root_run_id bigint DEFAULT NULL AFTER resolved_config_digest','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='parent_run_id');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN parent_run_id bigint DEFAULT NULL AFTER root_run_id','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='stage');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN stage varchar(30) DEFAULT NULL AFTER status','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='action_mode');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN action_mode varchar(30) DEFAULT NULL AFTER stage','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND column_name='quality_mode');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD COLUMN quality_mode varchar(20) DEFAULT NULL AFTER action_mode','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND index_name='uk_skill_run_scope_generation');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD UNIQUE INDEX uk_skill_run_scope_generation (idempotency_scope_hash,generation)','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND index_name='idx_skill_run_version');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD INDEX idx_skill_run_version (skill_version_id,status,create_time,id)','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_run' AND index_name='idx_skill_run_project');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_run ADD INDEX idx_skill_run_project (user_id,project_id,episode_id,create_time,id)','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS aid_skill_run_step (
  id bigint NOT NULL AUTO_INCREMENT, run_id bigint NOT NULL, step_seq int NOT NULL,
  step_key varchar(64) NOT NULL, step_execution_id varchar(64) NOT NULL,
  skill_id bigint NOT NULL, skill_version_id bigint NOT NULL, action_mode varchar(30) NOT NULL,
  workflow_attempt int NOT NULL DEFAULT 0, orchestration_status varchar(30) NOT NULL,
  checkpoint_json text DEFAULT NULL, del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_step_execution (run_id,step_execution_id),
  KEY idx_skill_step_run (run_id,step_seq,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill编排步骤';

CREATE TABLE IF NOT EXISTS aid_skill_run_task_link (
  id bigint NOT NULL AUTO_INCREMENT, run_id bigint NOT NULL, step_id bigint NOT NULL,
  step_execution_id varchar(64) NOT NULL, workflow_attempt int NOT NULL DEFAULT 0,
  logical_call_key varchar(64) NOT NULL, media_task_id bigint NOT NULL, del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_task_logical_call (logical_call_key),
  UNIQUE KEY uk_skill_task_media (media_task_id), KEY idx_skill_task_run (run_id,step_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Run步骤到现有媒体任务的纯关联';

CREATE TABLE IF NOT EXISTS aid_skill_input_request (
  id bigint NOT NULL AUTO_INCREMENT, run_id bigint NOT NULL, request_key varchar(64) NOT NULL,
  round_no int NOT NULL, status varchar(20) NOT NULL, schema_digest varchar(64) NOT NULL,
  context_version varchar(64) NOT NULL, accepted_revision varchar(64) DEFAULT NULL,
  question_bundle_json text NOT NULL, expires_at datetime DEFAULT NULL, answered_at datetime DEFAULT NULL,
  del_flag char(1) NOT NULL DEFAULT '0', create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_input_request (run_id,request_key),
  KEY idx_skill_input_pending (run_id,status,round_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill结构化澄清请求';

CREATE TABLE IF NOT EXISTS aid_skill_input_response (
  id bigint NOT NULL AUTO_INCREMENT, input_request_id bigint NOT NULL, run_id bigint NOT NULL,
  user_id bigint NOT NULL, response_key varchar(64) NOT NULL, response_digest varchar(64) NOT NULL,
  answers_json text NOT NULL, del_flag char(1) NOT NULL DEFAULT '0',
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_skill_input_response (input_request_id),
  KEY idx_skill_input_response_key (response_key),
  KEY idx_skill_input_response_run (run_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill澄清回答';

SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_input_response' AND index_name='uk_skill_input_response');
SET @s := IF(@c>1,'ALTER TABLE aid_skill_input_response DROP INDEX uk_skill_input_response','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_input_response' AND index_name='uk_skill_input_response');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_input_response ADD UNIQUE INDEX uk_skill_input_response (input_request_id)','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='aid_skill_input_response' AND index_name='idx_skill_input_response_key');
SET @s := IF(@c=0,'ALTER TABLE aid_skill_input_response ADD INDEX idx_skill_input_response_key (response_key)','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS aid_skill_run_event (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '全局单调ID，同时作为事件seq', run_id bigint NOT NULL,
  event_type varchar(30) NOT NULL, stage varchar(30) DEFAULT NULL, step_id bigint DEFAULT NULL,
  media_task_id bigint DEFAULT NULL, payload_json text DEFAULT NULL, create_time datetime NOT NULL,
  PRIMARY KEY (id), KEY idx_skill_event_resume (run_id,id), KEY idx_skill_event_time (create_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill可恢复里程碑事件';

-- 三个可执行 Skill：一个用户入口、两个 INTERNAL 子 Skill。knowledge 仅作为 references，不建 Skill。
INSERT INTO aid_skill (skill_code,name,description,owner_type,visibility,invocation_scope,executor_type,model_code,
 input_schema_json,output_schema_json,system_prompt,reasoning_policy,default_reasoning_enabled,
 show_reasoning_default,reasoning_budget_tokens,max_output_tokens,context_window_tokens,safety_margin_tokens,
 definition_json,config_hash,status,del_flag,create_by,create_time,update_by,update_time)
SELECT 'screenplay','剧本创作','电影与剧集剧本创作、审核及规范化入口','PLATFORM','PUBLIC','ENTRYPOINT',
 'ORCHESTRATOR','deepseek-v4-pro','{}','{}','根Skill只做确定性校验、规划、提问和子Skill编排。',
 'DISABLED',0,0,0,8192,128000,4096,'{"schemaVersion":1,"children":["screenplay-write","screenplay-review"]}',
 SHA2('screenplay-root-v1',256),'0','0','system',NOW(),'system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM aid_skill WHERE skill_code='screenplay');

INSERT INTO aid_skill (skill_code,name,description,owner_type,visibility,invocation_scope,executor_type,model_code,
 input_schema_json,output_schema_json,system_prompt,reasoning_policy,default_reasoning_enabled,
 show_reasoning_default,reasoning_budget_tokens,max_output_tokens,context_window_tokens,safety_margin_tokens,
 definition_json,config_hash,status,del_flag,create_by,create_time,update_by,update_time)
SELECT 'screenplay-write','剧本写作','INTERNAL：CREATE/REWRITE/CONTINUE/NORMALIZE/REPAIR','PLATFORM','PRIVATE','INTERNAL',
 'PROMPT','deepseek-v4-pro','{}','{}',
 '你是AID专业编剧。只输出可被现有下游解析的纯文本剧本。剧集首行必须是“本集正文”，电影首行必须是“电影正文”，均不带冒号。每场标题独占一行，格式“场次 N：地点 内/外 日/夜”；说话人独占一行并使用“人物名：”。时长、审核、版本等元数据不得混入正文。严格保持人物知识、关系、伤情、道具、未解线和出场状态连续。',
 'OPTIONAL',1,0,0,32768,128000,4096,'{"schemaVersion":1,"actions":["CREATE","REWRITE","CONTINUE","NORMALIZE","REPAIR"]}',
 SHA2('screenplay-write-v1',256),'0','0','system',NOW(),'system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM aid_skill WHERE skill_code='screenplay-write');

INSERT INTO aid_skill (skill_code,name,description,owner_type,visibility,invocation_scope,executor_type,model_code,
 input_schema_json,output_schema_json,system_prompt,reasoning_policy,default_reasoning_enabled,
 show_reasoning_default,reasoning_budget_tokens,max_output_tokens,context_window_tokens,safety_margin_tokens,
 definition_json,config_hash,status,del_flag,create_by,create_time,update_by,update_time)
SELECT 'screenplay-review','剧本审核','INTERNAL：独立只读审核，不直接改稿','PLATFORM','PRIVATE','INTERNAL',
 'PROMPT','deepseek-v4-pro','{}','{}',
 '你是独立剧本审核员。只输出审核报告，不改写原稿。逐项给出问题类别、严重程度、证据位置和修改建议；区分客观硬伤与审美分叉。禁止输出模型内部思维链。',
 'OPTIONAL',1,0,0,16384,128000,4096,'{"schemaVersion":1,"readOnly":true}',
 SHA2('screenplay-review-v1',256),'0','0','system',NOW(),'system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM aid_skill WHERE skill_code='screenplay-review');

INSERT INTO aid_skill_version (skill_id,version_code,visibility,invocation_scope,publish_status,executor_type,
 model_code,package_digest,manifest_json,input_schema_json,output_schema_json,system_prompt,definition_json,
 max_output_tokens,context_window_tokens,safety_margin_tokens,
 status,del_flag,create_by,create_time,update_by,update_time,remark)
SELECT s.id,'1.0.0',s.visibility,s.invocation_scope,'PRIVATE',s.executor_type,s.model_code,
 CASE s.skill_code
  WHEN 'screenplay' THEN 'd4ac1d40bfbf8ebbcc7851e85909fd22111e60d99e655f6c2cc8f34272ceadaf'
  WHEN 'screenplay-write' THEN '3495b12f9df2ec4489ce03514a994d7db6f88e680df73dc4578255632d2b8a14'
  WHEN 'screenplay-review' THEN 'b89f1e922560b10b73a276544bec396778223c68ac07ec701da4acc8bd0efe03' END,
 CONCAT('{"formatVersion":"1","code":"',s.skill_code,
  '","version":"1.0.0","invocationScope":"',s.invocation_scope,
  '","source":"classpath:skills/',s.skill_code,'/1.0.0/aid-skill.json"}'),
 s.input_schema_json,s.output_schema_json,s.system_prompt,s.definition_json,
 s.max_output_tokens,s.context_window_tokens,s.safety_margin_tokens,
 '0','0','system',NOW(),'system',NOW(),'内置初始版本'
FROM aid_skill s WHERE s.skill_code IN ('screenplay','screenplay-write','screenplay-review')
  AND NOT EXISTS (SELECT 1 FROM aid_skill_version v WHERE v.skill_id=s.id AND v.version_code='1.0.0');

INSERT INTO aid_skill_resource (skill_version_id,resource_key,resource_type,object_key,content_digest,
 mime_type,size_bytes,route_json,status,del_flag,create_by,create_time,update_by,update_time)
SELECT v.id,r.resource_key,'REFERENCE',r.object_key,r.content_digest,'text/markdown',r.size_bytes,
 r.route_json,'0','0','system',NOW(),'system',NOW()
FROM aid_skill s JOIN aid_skill_version v ON v.skill_id=s.id AND v.version_code='1.0.0'
JOIN (
 SELECT 'screenplay' skill_code,'runtime-contract' resource_key,
  'classpath:skills/screenplay/1.0.0/references/runtime-contract.md' object_key,
  '2bf0fd2f22191a9ca72fe48b006719ddb22bd50ad301deb591e449f173428263' content_digest,
  542 size_bytes,'{"always":true}' route_json
 UNION ALL SELECT 'screenplay-write','canonical-format',
  'classpath:skills/screenplay-write/1.0.0/references/canonical-format.md',
  '6dd377d3b431ddf0f257d0510593c696bebd54c192207b8912c68c04768419a2',590,'{"always":true}'
 UNION ALL SELECT 'screenplay-write','continuity',
  'classpath:skills/screenplay-write/1.0.0/references/continuity.md',
  '51893caa3ac9c74c85ecfb72e82cd60dac1a36dd068af218cfa6f58dededc3f8',466,'{"operations":["CONTINUE","REWRITE","REPAIR"]}'
 UNION ALL SELECT 'screenplay-write','dramaturgy-craft',
  'classpath:skills/screenplay-write/1.0.0/references/dramaturgy-craft.md',
  '467c2805621754d7d17a3aa1d1553aea04d3e754fd529e668c521b560218867a',837,'{"always":true}'
 UNION ALL SELECT 'screenplay-write','dialogue-craft',
  'classpath:skills/screenplay-write/1.0.0/references/dialogue-craft.md',
  '5415227028907bde2f897410f6141e924f70a5f339d834438ff3ff2a1011895f',483,'{"keywords":["对白","对话","台词"]}'
 UNION ALL SELECT 'screenplay-write','repair-policy',
  'classpath:skills/screenplay-write/1.0.0/references/repair-policy.md',
  '2edd68a22690939d202f94e77ae1694d878c17b16e4bcef9e3924f9cd627569c',368,'{"operations":["REPAIR"]}'
 UNION ALL SELECT 'screenplay-review','review-rubric',
  'classpath:skills/screenplay-review/1.0.0/references/review-rubric.md',
  '3c153b7dd55403634c3d8100825e13ae0797420c78524c05f0fa7c52b010f2b3',492,'{"always":true}'
 UNION ALL SELECT 'screenplay-review','continuity-review',
  'classpath:skills/screenplay-review/1.0.0/references/continuity-review.md',
  '6cacd05a3a33668b814efbfa2033693ef8398b9d21200fae33c5eaf9f263f6e2',347,'{"operations":["REVIEW"]}'
) r ON r.skill_code=s.skill_code
WHERE NOT EXISTS (SELECT 1 FROM aid_skill_resource sr
 WHERE sr.skill_version_id=v.id AND sr.resource_key=r.resource_key);

UPDATE aid_skill s JOIN aid_skill_version v ON v.skill_id=s.id AND v.version_code='1.0.0'
SET s.current_version_id=v.id,s.update_by='system',s.update_time=NOW()
WHERE s.skill_code IN ('screenplay','screenplay-write','screenplay-review') AND s.current_version_id IS NULL;

INSERT INTO aid_skill_relation (parent_version_id,child_skill_id,child_version_id,relation_type,relation_key,
 required_flag,del_flag,create_by,create_time,update_by,update_time)
SELECT pv.id,c.id,cv.id,'CHILD',c.skill_code,1,'0','system',NOW(),'system',NOW()
FROM aid_skill p
JOIN aid_skill_version pv ON pv.skill_id=p.id AND pv.version_code='1.0.0'
JOIN aid_skill c ON c.skill_code IN ('screenplay-write','screenplay-review')
JOIN aid_skill_version cv ON cv.skill_id=c.id AND cv.version_code='1.0.0'
WHERE p.skill_code='screenplay'
  AND NOT EXISTS (SELECT 1 FROM aid_skill_relation r
    WHERE r.parent_version_id=pv.id AND r.relation_type='CHILD' AND r.relation_key=c.skill_code);


-- 内置剧本 Skill 提示词与参考资料包；MySQL 5.7，可重复执行。
-- 保留 1.0.0 供历史 Run 复现，新调用切换到固定绑定的 1.1.0 根与子 Skill。
SET NAMES utf8mb4;

SET @screenplay_root_prompt := '你是AID剧本工作流调度器。只做确定性校验、动态澄清、可信上下文装配和固定版本子Skill编排，不直接生成正文。只询问会改变故事方向、审核范围或目标集的缺失决定；已知事实不重复问，可安全回退的细节允许模型决定。REVIEW_ONLY只审核不改稿，高质量模式仅对有正文证据且无需审美选择的客观硬伤执行一次修复。';
SET @screenplay_write_prompt := '你是AID专业编剧。按动作模式处理电影或剧集：先服从用户明确要求和已接受项目事实，再在内部形成观看承诺、人物当前目标、有效阻力、方向性转向、局部兑现与退出状态，使行动结果迫使下一动作。只写观众可见可听且可表演的事实，严格区分世界真相、人物认知和观众已知；不得为套规则补造关系、证据、资源、权限、精确时间或机关。REWRITE、NORMALIZE和REPAIR必须保护未命中内容，REPAIR只改有正文证据的客观硬伤。输出前静默检查因果、对白策略、连续性、时长容量与格式，不输出思考过程。最终只输出AID规范纯文本：剧集首行为“本集正文”，电影首行为“电影正文”，每场使用“场次 N：地点 内/外 日/夜”，说话人独占一行使用“人物名：”；禁止JSON、Markdown围栏、审核报告、时长和版本元数据。';
SET @screenplay_review_prompt := '你是AID独立剧本审核员。只审核当前正文并输出证据化报告，绝不改写原稿或输出模型内部思维链。先检查可证明的AID格式和事实连续性，再审查观看承诺、因果、场景变化、可表演行动、对白策略、退出状态与条件化机制；审美偏好不能伪装成客观缺陷。每个问题必须给出有界位置、必要短证据、实际影响、修订目标、严重程度和STRUCTURAL/OBJECTIVE/CRAFT/AESTHETIC性质。第一行使用“审核结论：APPROVE|APPROVE_WITH_NOTES|REVISE|PROVISIONAL”。REPAIR_REQUIRED只有在存在无需用户选择的STRUCTURAL或OBJECTIVE级BLOCKER/MAJOR时为YES；报告末尾必须分别输出REPAIR_REQUIRED和AESTHETIC_CHOICE_REQUIRED标记。';

INSERT INTO aid_skill_version (skill_id,version_code,visibility,invocation_scope,publish_status,executor_type,
 model_code,package_digest,manifest_json,input_schema_json,output_schema_json,system_prompt,definition_json,
 max_output_tokens,context_window_tokens,safety_margin_tokens,
 status,del_flag,create_by,create_time,update_by,update_time,remark)
SELECT s.id,'1.1.0',s.visibility,s.invocation_scope,'PRIVATE',s.executor_type,s.model_code,
 CASE s.skill_code
  WHEN 'screenplay' THEN '74235e652c1eae5a2786557dba040d611fc3edddf81b57ce5b788c055e0085c2'
  WHEN 'screenplay-write' THEN '20f8f1a4605f202efe4d05a9358b6603080de0a08fe32acdfe003a94752096ce'
  WHEN 'screenplay-review' THEN '471afd9933a7193b2aa99b95b63dbac6cc27db2686b6097fa61e1341c1cb5f7e' END,
 CONCAT('{"formatVersion":"1","code":"',s.skill_code,
  '","version":"1.1.0","invocationScope":"',s.invocation_scope,
  '","source":"classpath:skills/',s.skill_code,'/1.1.0/aid-skill.json"}'),
 s.input_schema_json,s.output_schema_json,
 CASE s.skill_code
  WHEN 'screenplay' THEN @screenplay_root_prompt
  WHEN 'screenplay-write' THEN @screenplay_write_prompt
  WHEN 'screenplay-review' THEN @screenplay_review_prompt END,
 CASE s.skill_code
  WHEN 'screenplay' THEN '{"schemaVersion":1,"children":["screenplay-write","screenplay-review"],"interaction":"dynamic"}'
  WHEN 'screenplay-write' THEN '{"schemaVersion":1,"actions":["CREATE","REWRITE","CONTINUE","NORMALIZE","REPAIR"],"canonicalFormat":"aid-plaintext"}'
  WHEN 'screenplay-review' THEN '{"schemaVersion":1,"readOnly":true,"evidenceBased":true}' END,
 CASE s.skill_code WHEN 'screenplay' THEN 8192 WHEN 'screenplay-write' THEN 32768 ELSE 16384 END,
 s.context_window_tokens,s.safety_margin_tokens,
 '0','0','system',NOW(),'system',NOW(),'剧本方法、连续性与证据化审核提示词包'
FROM aid_skill s
WHERE s.skill_code IN ('screenplay','screenplay-write','screenplay-review')
  AND NOT EXISTS (SELECT 1 FROM aid_skill_version v
    WHERE v.skill_id=s.id AND v.version_code='1.1.0');

INSERT INTO aid_skill_resource (skill_version_id,resource_key,resource_type,object_key,content_digest,
 mime_type,size_bytes,route_json,status,del_flag,create_by,create_time,update_by,update_time)
SELECT v.id,r.resource_key,'REFERENCE',r.object_key,r.content_digest,'text/markdown',r.size_bytes,
 r.route_json,'0','0','system',NOW(),'system',NOW()
FROM aid_skill s
JOIN aid_skill_version v ON v.skill_id=s.id AND v.version_code='1.1.0'
JOIN (
 SELECT 'screenplay' skill_code,'runtime-contract' resource_key,
  'classpath:skills/screenplay/1.1.0/references/runtime-contract.md' object_key,
  '21bf2a38f6c2bd4bc744d707229e4f54533ffd95def8ba0f79d348c43292defb' content_digest,
  1367 size_bytes,'{"always":true}' route_json
 UNION ALL SELECT 'screenplay','interaction-policy',
  'classpath:skills/screenplay/1.1.0/references/interaction-policy.md',
  'f3d2ffc2b1eed303c6ffd6604abfd914ade9b789b5aacd0c53d73a1141a3c8a6',1658,'{"always":true}'
 UNION ALL SELECT 'screenplay','upstream-attribution',
  'classpath:skills/screenplay/1.1.0/references/upstream-attribution.md',
  '221aa1be84c361293d773af4f63ef03c2d814f2fbc157ac9d645e375d3e1a611',1422,
  '{}'

 UNION ALL SELECT 'screenplay-write','canonical-format',
  'classpath:skills/screenplay-write/1.1.0/references/canonical-format.md',
  '473329feb9baeb547c9a183c1cd20876e76148d7faad48eb33f05c5c0b507da1',1878,'{"always":true}'
 UNION ALL SELECT 'screenplay-write','story-engine',
  'classpath:skills/screenplay-write/1.1.0/references/story-engine.md',
  'c046615df9247d3f891cb83211cf82d0d37bee40ab0caee245799a50d898aab0',4432,'{"always":true}'
 UNION ALL SELECT 'screenplay-write','revision-policy',
  'classpath:skills/screenplay-write/1.1.0/references/revision-policy.md',
  '1d53396784725360b16bbac2618d2c301f9acd5e4c3e0ed4ebf237d4e9eea90d',2044,
  '{"operations":["REWRITE","NORMALIZE","REPAIR"]}'
 UNION ALL SELECT 'screenplay-write','series-craft',
  'classpath:skills/screenplay-write/1.1.0/references/series-craft.md',
  '644469ee0ec6e6567192904b6ec4b2b4a26c05f575c19fcc6f048469afa9041c',2004,
  '{"keywords":["项目类型：series","剧集项目","前集","跨集连续","当前集"]}'
 UNION ALL SELECT 'screenplay-write','movie-craft',
  'classpath:skills/screenplay-write/1.1.0/references/movie-craft.md',
  '3e50d9fb6b14178a8eeee7fbeb3bc788579f8feed23b699c782f6eb3431633ba',1555,
  '{"keywords":["项目类型：movie","电影项目","长片","全片"]}'
 UNION ALL SELECT 'screenplay-write','conditional-mechanisms',
  'classpath:skills/screenplay-write/1.1.0/references/conditional-mechanisms.md',
  '63099b18f7c63d73420cd459ccc30c88bc851cce087b976c41419ce15aa51c4b',1383,
  '{"keywords":["艰难选择","二选一","证据","物证","倒计时","死线","限时","次数","轮次","呼吸"]}'
 UNION ALL SELECT 'screenplay-write','dialogue-performance',
  'classpath:skills/screenplay-write/1.1.0/references/dialogue-performance.md',
  '6b6ec19466401c798d7dedb92d825250657fe16c1d5b39e3509f85bf5f92bc66',1561,
  '{"keywords":["对白","对话","台词","潜台词","谈判","审讯","吵架","去AI味"]}'
 UNION ALL SELECT 'screenplay-write','production-craft',
  'classpath:skills/screenplay-write/1.1.0/references/production-craft.md',
  '43b09b13a39857a4854611e02429103202f2a4258b7b4a457136deca7c5e3ba6',1284,
  '{"keywords":["可拍","声音","旁白","画外音","画面文字","转场","制作"]}'
 UNION ALL SELECT 'screenplay-write','upstream-attribution',
  'classpath:skills/screenplay-write/1.1.0/references/upstream-attribution.md',
  '8de35e00f0316d1f2977ad86ad6822329a778de3e36bedff0ea34d3e4825f6c0',1397,
  '{}'

 UNION ALL SELECT 'screenplay-review','review-method',
  'classpath:skills/screenplay-review/1.1.0/references/review-method.md',
  'a01a41e1a82c13708742a5f9b7872b18f4aee7aecdc1a8d3a19ed95556244c2c',2677,'{"always":true}'
 UNION ALL SELECT 'screenplay-review','story-script-rubric',
  'classpath:skills/screenplay-review/1.1.0/references/story-script-rubric.md',
  '8eb21957efdce5ed80ee2c9d6c9f90e04858819b55a1d28a5a825fe9b543ede4',2727,'{"always":true}'
 UNION ALL SELECT 'screenplay-review','continuity-review',
  'classpath:skills/screenplay-review/1.1.0/references/continuity-review.md',
  '1c3d60fef4aeaf2ee84c5f717fd9467beb97a41b69c57e80d51376682fd85c56',1414,
  '{"keywords":["项目类型：series","连续","前集","剧集","当前集","人物关系","关键道具"]}'
 UNION ALL SELECT 'screenplay-review','anti-template-review',
  'classpath:skills/screenplay-review/1.1.0/references/anti-template-review.md',
  '2f6271e34eb4c4abaa0047b208dc1ad5e2bccd21b6291715846e787ecf5f5b6a',1344,
  '{"keywords":["AI味","去AI","模板","套路","机械","同质","工整","金句"]}'
 UNION ALL SELECT 'screenplay-review','format-production-review',
  'classpath:skills/screenplay-review/1.1.0/references/format-production-review.md',
  'ffe0e360c2a9973ce4d55f45d2bde406af0a06a755eeedf12ce07d3ec6dc3484',1144,
  '{"keywords":["本集正文","电影正文","场次","格式","可拍","制作","旁白","画面文字"]}'
 UNION ALL SELECT 'screenplay-review','upstream-attribution',
  'classpath:skills/screenplay-review/1.1.0/references/upstream-attribution.md',
  '0ec376b6a20920fb67e8458f7a8797049e0c9d1629d5a77d43f20e77bf5d62e0',1365,
  '{}'
) r ON r.skill_code=s.skill_code
WHERE NOT EXISTS (SELECT 1 FROM aid_skill_resource sr
 WHERE sr.skill_version_id=v.id AND sr.resource_key=r.resource_key);

INSERT INTO aid_skill_relation (parent_version_id,child_skill_id,child_version_id,relation_type,relation_key,
 required_flag,del_flag,create_by,create_time,update_by,update_time)
SELECT pv.id,c.id,cv.id,'CHILD',c.skill_code,1,'0','system',NOW(),'system',NOW()
FROM aid_skill p
JOIN aid_skill_version pv ON pv.skill_id=p.id AND pv.version_code='1.1.0'
JOIN aid_skill c ON c.skill_code IN ('screenplay-write','screenplay-review')
JOIN aid_skill_version cv ON cv.skill_id=c.id AND cv.version_code='1.1.0'
WHERE p.skill_code='screenplay'
  AND NOT EXISTS (SELECT 1 FROM aid_skill_relation r
    WHERE r.parent_version_id=pv.id AND r.relation_type='CHILD' AND r.relation_key=c.skill_code);

-- 只从初始版本切换，避免在未来版本已启用时重复执行本迁移造成降级。
UPDATE aid_skill s
JOIN aid_skill_version next_version ON next_version.skill_id=s.id AND next_version.version_code='1.1.0'
LEFT JOIN aid_skill_version current_version ON current_version.id=s.current_version_id
SET s.current_version_id=next_version.id,
    s.system_prompt=next_version.system_prompt,
    s.definition_json=next_version.definition_json,
    s.config_hash=SHA2(CONCAT(s.skill_code,'|1.1.0|',next_version.package_digest),256),
    s.update_by='system',s.update_time=NOW()
WHERE s.skill_code IN ('screenplay','screenplay-write','screenplay-review')
  AND (current_version.id IS NULL OR current_version.version_code='1.0.0');

SET @screenplay_root_prompt := NULL;
SET @screenplay_write_prompt := NULL;
SET @screenplay_review_prompt := NULL;


-- Skill 后台版本化编辑增量；MySQL 5.7，可重复执行。
-- 小型提示词资源每个不可变版本仅保存一份，应用层限制单资源 100KiB、单版本合计 512KiB；发布后草稿正文收缩为版本回执。
SET NAMES utf8mb4;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_resource' AND column_name='content_text');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill_resource ADD COLUMN content_text longtext DEFAULT NULL COMMENT ''后台发布的版本化文本资源；不复制到运行记录'' AFTER route_json',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- SemVer 标识使用 ASCII 且大小写敏感，唯一索引据此区分预发布标识大小写。
SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_version' AND column_name='version_code'
    AND character_set_name='ascii' AND collation_name='ascii_bin' AND column_type='varchar(64)');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill_version MODIFY COLUMN version_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_draft' AND column_name='active_key');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill_draft ADD COLUMN active_key varchar(160) DEFAULT NULL COMMENT ''活动草稿唯一键；发布后清空'' AFTER owner_user_id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 升级前若已有重复活动草稿，只保留每个管理员与 Skill 最新的一份。
UPDATE aid_skill_draft d
JOIN (
  SELECT grouped.owner_user_id,grouped.skill_id,grouped.keep_id
  FROM (
    SELECT owner_user_id,skill_id,MAX(id) keep_id
    FROM aid_skill_draft
    WHERE status='EDITING' AND owner_user_id IS NOT NULL
    GROUP BY owner_user_id,skill_id
  ) grouped
) latest ON latest.owner_user_id=d.owner_user_id AND latest.skill_id=d.skill_id
SET d.status=IF(d.id=latest.keep_id,'EDITING','SUPERSEDED'),
    d.active_key=IF(d.id=latest.keep_id,CONCAT(d.owner_user_id,':',d.skill_id),NULL)
WHERE d.status='EDITING';

SET @c := (SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='aid_skill_draft' AND index_name='uk_skill_draft_active');
SET @s := IF(@c=0,
  'ALTER TABLE aid_skill_draft ADD UNIQUE INDEX uk_skill_draft_active (active_key)',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;


-- Upgrade the screenplay Skill runtime to versioned model pools and replayable streaming events.
-- MySQL 5.7 compatible and safe to execute repeatedly.
SET NAMES utf8mb4;
SET SESSION group_concat_max_len = 1048576;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill' AND column_name='capability_description');
SET @s := IF(@c=0,
  'ALTER TABLE `aid_skill` ADD COLUMN `capability_description` varchar(2000) DEFAULT NULL COMMENT ''面向调用方的能力介绍'' AFTER `description`',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_version' AND column_name='model_config_json');
SET @s := IF(@c=0,
  'ALTER TABLE `aid_skill_version` ADD COLUMN `model_config_json` text DEFAULT NULL COMMENT ''不可变版本的默认及可选模型配置'' AFTER `model_code`',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- Preserve the distinction between an absent reasoning budget and an explicit positive budget.
-- Legacy rows may still contain 0; runtime normalization treats those values as absent.
SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_run'
    AND column_name='reasoning_budget_tokens'
    AND (is_nullable<>'YES' OR column_default IS NOT NULL));
SET @s := IF(@c>0,
  'ALTER TABLE `aid_skill_run` MODIFY COLUMN `reasoning_budget_tokens` int NULL DEFAULT NULL COMMENT ''总输出上限内的思考子预算''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(1) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='aid_skill_run_event' AND column_name='event_key');
SET @s := IF(@c=0,
  'ALTER TABLE `aid_skill_run_event` ADD COLUMN `event_key` varchar(100) DEFAULT NULL COMMENT ''权威阶段与终态事件幂等键'' AFTER `event_type`',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='aid_skill_run_event'
    AND index_name='uk_skill_event_key');
SET @s := IF(@c=0,
  'ALTER TABLE `aid_skill_run_event` ADD UNIQUE INDEX `uk_skill_event_key` (`run_id`,`event_key`)',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- Old immutable versions retain their single-model behavior through an explicit compatibility snapshot.
UPDATE aid_skill_version
SET model_config_json=CONCAT('{"defaultModelCode":',JSON_QUOTE(model_code),
  ',"selectableModelCodes":[',JSON_QUOTE(model_code),']}')
WHERE (model_config_json IS NULL OR model_config_json='') AND model_code IS NOT NULL AND model_code<>'';

UPDATE aid_skill
SET capability_description='支持从创作意图澄清到完整剧本、续写、改稿、选段批注和独立复核的连续创作；支持实时正文与模型公开创作思路流、断线续传和版本化模型选择。',
    update_by='system',update_time=NOW()
WHERE skill_code='screenplay'
  AND (capability_description IS NULL OR TRIM(capability_description)='');

SET @root_skill_id := (SELECT id FROM aid_skill WHERE skill_code='screenplay' LIMIT 1);
SET @write_skill_id := (SELECT id FROM aid_skill WHERE skill_code='screenplay-write' LIMIT 1);
SET @review_skill_id := (SELECT id FROM aid_skill WHERE skill_code='screenplay-review' LIMIT 1);
SET @source_root_version_id := (SELECT current_version_id FROM aid_skill WHERE id=@root_skill_id);
SET @source_write_version_id := (SELECT current_version_id FROM aid_skill WHERE id=@write_skill_id);
SET @source_review_version_id := (SELECT current_version_id FROM aid_skill WHERE id=@review_skill_id);

-- The wording is intentionally principle-based: all user-facing questions and replies are generated by the model.
-- Craft constraints are distilled from drama-skills-main without copying canned dialogue or filesystem workflow text.
SET @screenplay_root_prompt := '你是AID剧本创作入口与意图调度模型。完整理解用户本轮原话、项目事实、已接受剧本、最近草稿、父子Run对话和明确批注选段，再自然决定是回答、澄清还是进入创作。禁止复读固定话术，禁止因为短句、错别字或口语表达就机械判成追问；要结合连续对话推断真实意图。只询问会实质改变本次成稿的必要信息，问题、候选项、推荐项和说明都由你针对当前上下文生成：新建完整剧本若未给目标时长或明确篇幅，必须先询问时长；已经给出秒数、分钟或明确篇幅时不得重复询问。用户给出批注选段时，修改对象已明确，不得要求再次粘贴或定位，直接路由到定点修改。已有材料足够时直接执行，不为补齐表单、格式或内部流程提问。你不直接编造正文；输出严格符合当前路由结构，供服务端选择对话、写作或审核。';
SET @screenplay_write_prompt := '你是AID专业编剧。写作标准参考成熟短剧制作流程，但只服务于当前用户要求，不复制固定模板。先服从用户明确事实、禁令、风格、目标时长、已接受内容和批注范围，再组织观看承诺、人物目标、有效阻力、因果转向、局部兑现与退出状态。每场必须产生可见的信息、权力、关系、情绪、风险或物理状态变化；动作、对白、沉默、空间、声音和画面文字都要可表演、可拍摄。严格区分世界真相、人物认知和观众已知，不为套规则补造关系、证据、资源、权限、精确数字或机关。对话应体现争取、回避、试探、逼迫或关系重定义，避免用解释替代行动。目标时长按真实对白、动作和停顿控制容量，先删重复验证、同义收尾和无变化场。REWRITE、NORMALIZE和REPAIR保护未命中内容；明确批注只返回可直接替换的正文，多选段严格按约定分段，禁止加入备注、修改说明或评价。输出前静默检查因果、连续性、时长与格式。完整剧本只输出AID规范纯文本：剧集首行为“本集正文”，电影首行为“电影正文”，每场使用“场次 N：地点 内/外 日/夜”，说话人独占一行使用“人物名：”；禁止JSON、Markdown围栏、审核报告、版本元数据和面向用户的附注。';
SET @screenplay_review_prompt := '你是AID独立剧本审核员。只审核当前正文并输出证据化报告，不改写原稿，不暴露模型内部推理。先检查可证明的格式、事实和连续性，再审查观看承诺、因果、场景变化、可表演行动、对白策略、目标时长容量与退出状态；审美偏好不得伪装成客观缺陷。每个问题必须给出有界位置、必要短证据、实际影响、修订目标、严重程度和STRUCTURAL/OBJECTIVE/CRAFT/AESTHETIC性质。第一行使用“审核结论：APPROVE|APPROVE_WITH_NOTES|REVISE|PROVISIONAL”。只有存在无需用户选择、由正文证据证明的STRUCTURAL或OBJECTIVE级BLOCKER/MAJOR时，REPAIR_REQUIRED才为YES；报告末尾分别输出REPAIR_REQUIRED和AESTHETIC_CHOICE_REQUIRED标记。';

SET @active_default_model := (SELECT v.model_code FROM aid_skill_version v
  JOIN aid_ai_model m ON BINARY m.model_code=BINARY v.model_code
  JOIN aid_ai_provider p ON p.id=m.provider_id
  WHERE v.id=@source_root_version_id AND m.model_type='text'
    AND m.status='0' AND m.del_flag='0' AND p.status='0' AND p.del_flag='0' LIMIT 1);
SET @active_default_model := COALESCE(@active_default_model,(SELECT m.model_code FROM aid_ai_model m
  JOIN aid_ai_provider p ON p.id=m.provider_id
  WHERE m.model_type='text' AND m.status='0' AND m.del_flag='0'
    AND p.status='0' AND p.del_flag='0'
  ORDER BY m.priority DESC,m.id ASC LIMIT 1));
SET @has_active_model := IF(@active_default_model IS NULL,0,1);
-- 数据库迁移不能依赖运行时已经配置模型。没有可用模型时沿用基础版本编码并保持 Skill 禁用，
-- 待管理员配置文本模型后再启用，避免新装或升级因环境配置缺失而中断。
SET @default_model := COALESCE(@active_default_model,(SELECT model_code FROM aid_skill_version
  WHERE id=@source_root_version_id LIMIT 1),'unconfigured-text-model');

SELECT GROUP_CONCAT(JSON_QUOTE(pool.model_code) ORDER BY pool.sort_order SEPARATOR ',') INTO @model_codes
FROM (
  SELECT m.model_code,
    CASE
      WHEN BINARY m.model_code=BINARY @default_model THEN 0
      WHEN BINARY m.model_code=BINARY 'deepseek-v4-pro' THEN 1
      WHEN BINARY m.model_code=BINARY 'deepseek-v4-flash' THEN 2
      WHEN BINARY m.model_code=BINARY 'qwen3.7-max' THEN 3
      WHEN BINARY m.model_code=BINARY 'qwen3.7-plus' THEN 4
      WHEN BINARY m.model_code=BINARY 'gemini-3.1-pro-preview' THEN 5
      WHEN BINARY m.model_code=BINARY 'gemini-3-flash-preview' THEN 6
      WHEN BINARY m.model_code=BINARY 'gpt-5.6' THEN 7
      WHEN BINARY m.model_code=BINARY 'gpt-5.5' THEN 8
      WHEN BINARY m.model_code=BINARY 'gpt-5.4' THEN 9
      WHEN BINARY m.model_code=BINARY 'agnes-2.5-flash' THEN 10 ELSE 100 END AS sort_order
  FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id
  WHERE m.model_type='text' AND m.status='0' AND m.del_flag='0'
    AND p.status='0' AND p.del_flag='0'
    AND (BINARY m.model_code=BINARY @default_model OR BINARY m.model_code IN (
      'deepseek-v4-pro','deepseek-v4-flash','qwen3.7-max','qwen3.7-plus',
      'gemini-3.1-pro-preview','gemini-3-flash-preview','gpt-5.6','gpt-5.5','gpt-5.4',
      'agnes-2.5-flash'))
  ORDER BY sort_order,m.id LIMIT 20
) pool;
SET @model_codes := COALESCE(NULLIF(@model_codes,''),JSON_QUOTE(@default_model));
SET @model_config := CONCAT('{"defaultModelCode":',JSON_QUOTE(@default_model),
  ',"selectableModelCodes":[',COALESCE(@model_codes,''),']}');

INSERT INTO aid_skill_version (skill_id,version_code,visibility,invocation_scope,publish_status,
 executor_type,model_code,model_config_json,package_digest,manifest_json,input_schema_json,
 output_schema_json,system_prompt,definition_json,max_output_tokens,context_window_tokens,
 safety_margin_tokens,status,del_flag,create_by,create_time,update_by,update_time,remark)
SELECT source.skill_id,'1.2.0',source.visibility,source.invocation_scope,source.publish_status,
 source.executor_type,@default_model,@model_config,
 SHA2(CONCAT('pending:1.2.0:',identity.skill_code),256),
 CONCAT('{"code":',JSON_QUOTE(identity.skill_code),',"version":"1.2.0","invocationScope":',
   JSON_QUOTE(source.invocation_scope),',"source":"DATABASE","digestAlgorithm":"aid-db-package-v3","resources":[]}'),
 source.input_schema_json,source.output_schema_json,
 CASE identity.skill_code
   WHEN 'screenplay' THEN @screenplay_root_prompt
   WHEN 'screenplay-write' THEN @screenplay_write_prompt
   WHEN 'screenplay-review' THEN @screenplay_review_prompt END,
 CASE identity.skill_code
   WHEN 'screenplay' THEN '{"schemaVersion":2,"children":["screenplay-write","screenplay-review"],"interaction":"dynamic","stream":true,"reasoningEnabled":true,"showReasoning":true,"reasoningLevel":"high"}'
   WHEN 'screenplay-write' THEN '{"schemaVersion":2,"actions":["CREATE","REWRITE","CONTINUE","NORMALIZE","REPAIR"],"canonicalFormat":"aid-plaintext"}'
   WHEN 'screenplay-review' THEN '{"schemaVersion":2,"readOnly":true,"evidenceBased":true}' END,
 source.max_output_tokens,source.context_window_tokens,source.safety_margin_tokens,
 IF(@has_active_model=1,'0','1'),'0','system',NOW(),'system',NOW(),
 IF(@has_active_model=1,'剧本运行流式与版本模型池','等待管理员配置可用文本模型')
FROM aid_skill_version source JOIN aid_skill identity ON identity.id=source.skill_id
WHERE source.id IN (@source_root_version_id,@source_write_version_id,@source_review_version_id)
  AND NOT EXISTS (SELECT 1 FROM aid_skill_version existing
    WHERE existing.skill_id=source.skill_id AND existing.version_code='1.2.0');

INSERT INTO aid_skill_resource (skill_version_id,resource_key,resource_type,object_key,
 content_digest,mime_type,size_bytes,route_json,content_text,status,del_flag,
 create_by,create_time,update_by,update_time)
SELECT target.id,source.resource_key,source.resource_type,source.object_key,
 source.content_digest,source.mime_type,source.size_bytes,source.route_json,source.content_text,
 source.status,source.del_flag,'system',NOW(),'system',NOW()
FROM aid_skill identity
JOIN aid_skill_version target ON target.skill_id=identity.id AND target.version_code='1.2.0'
JOIN aid_skill_resource source ON source.skill_version_id=CASE identity.skill_code
  WHEN 'screenplay' THEN @source_root_version_id
  WHEN 'screenplay-write' THEN @source_write_version_id
  WHEN 'screenplay-review' THEN @source_review_version_id END
WHERE identity.skill_code IN ('screenplay','screenplay-write','screenplay-review')
  AND source.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_skill_resource existing
    WHERE existing.skill_version_id=target.id AND existing.resource_key=source.resource_key);

SET @root_version_id := (SELECT id FROM aid_skill_version
  WHERE skill_id=@root_skill_id AND version_code='1.2.0' LIMIT 1);
SET @write_version_id := (SELECT id FROM aid_skill_version
  WHERE skill_id=@write_skill_id AND version_code='1.2.0' LIMIT 1);
SET @review_version_id := (SELECT id FROM aid_skill_version
  WHERE skill_id=@review_skill_id AND version_code='1.2.0' LIMIT 1);

INSERT INTO aid_skill_relation (parent_version_id,child_skill_id,child_version_id,relation_type,
 relation_key,required_flag,del_flag,create_by,create_time,update_by,update_time)
SELECT @root_version_id,@write_skill_id,@write_version_id,'CHILD','screenplay-write',1,'0',
 'system',NOW(),'system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM aid_skill_relation WHERE parent_version_id=@root_version_id
  AND relation_type='CHILD' AND relation_key='screenplay-write');
INSERT INTO aid_skill_relation (parent_version_id,child_skill_id,child_version_id,relation_type,
 relation_key,required_flag,del_flag,create_by,create_time,update_by,update_time)
SELECT @root_version_id,@review_skill_id,@review_version_id,'CHILD','screenplay-review',1,'0',
 'system',NOW(),'system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM aid_skill_relation WHERE parent_version_id=@root_version_id
  AND relation_type='CHILD' AND relation_key='screenplay-review');

-- Rebuild package digests with the same ordered JSON basis as SkillPackageDigestCalculator.
UPDATE aid_skill_version v JOIN aid_skill s ON s.id=v.skill_id
SET v.package_digest=SHA2(CONCAT(
  '{"format":"aid-db-package-v3","skillCode":',JSON_QUOTE(s.skill_code),
  ',"versionCode":',JSON_QUOTE(v.version_code),
  ',"visibility":',JSON_QUOTE(v.visibility),
  ',"invocationScope":',JSON_QUOTE(v.invocation_scope),
  ',"executorType":',JSON_QUOTE(v.executor_type),
  ',"modelCode":',JSON_QUOTE(v.model_code),
  ',"modelConfigJson":',JSON_QUOTE(COALESCE(v.model_config_json,'')),
  ',"systemPromptDigest":',JSON_QUOTE(SHA2(COALESCE(v.system_prompt,''),256)),
  ',"inputSchemaDigest":',JSON_QUOTE(SHA2(COALESCE(v.input_schema_json,''),256)),
  ',"outputSchemaDigest":',JSON_QUOTE(SHA2(COALESCE(v.output_schema_json,''),256)),
  ',"definitionDigest":',JSON_QUOTE(SHA2(COALESCE(v.definition_json,''),256)),
  ',"maxOutputTokens":',v.max_output_tokens,
  ',"contextWindowTokens":',v.context_window_tokens,
  ',"safetyMarginTokens":',v.safety_margin_tokens,
  ',"manifestDigest":',JSON_QUOTE(SHA2(COALESCE(v.manifest_json,''),256)),
  ',"resources":[',COALESCE((SELECT GROUP_CONCAT(CONCAT(
    '{"key":',JSON_QUOTE(r.resource_key),
    ',"type":',JSON_QUOTE(r.resource_type),
    ',"mimeType":',JSON_QUOTE(r.mime_type),
    ',"digest":',JSON_QUOTE(r.content_digest),
    ',"sizeBytes":',r.size_bytes,
    ',"routeJson":',JSON_QUOTE(COALESCE(r.route_json,'')),'}')
    ORDER BY r.id SEPARATOR ',') FROM aid_skill_resource r
    WHERE r.skill_version_id=v.id AND r.status='0' AND r.del_flag='0'),''),
  '],"relations":[',COALESCE((SELECT GROUP_CONCAT(CONCAT(
    '{"type":',JSON_QUOTE(rel.relation_type),
    ',"key":',JSON_QUOTE(rel.relation_key),
    ',"childSkillId":',rel.child_skill_id,
    ',"childVersionId":',rel.child_version_id,
    ',"required":',IF(rel.required_flag=1,'true','false'),'}')
    ORDER BY rel.relation_key SEPARATOR ',') FROM aid_skill_relation rel
    WHERE rel.parent_version_id=v.id AND rel.del_flag='0'),''),']}'
),256),v.update_by='system',v.update_time=NOW()
WHERE v.id IN (@root_version_id,@write_version_id,@review_version_id);

UPDATE aid_skill s
JOIN aid_skill_version v ON v.skill_id=s.id AND v.version_code='1.2.0'
LEFT JOIN aid_skill_version current ON current.id=s.current_version_id
SET s.current_version_id=v.id,s.model_code=v.model_code,s.system_prompt=v.system_prompt,
 s.input_schema_json=v.input_schema_json,s.output_schema_json=v.output_schema_json,
 s.definition_json=v.definition_json,s.max_output_tokens=v.max_output_tokens,
 s.context_window_tokens=v.context_window_tokens,s.safety_margin_tokens=v.safety_margin_tokens,
 s.status=IF(@has_active_model=1,s.status,'1'),
 s.config_hash=SHA2(CONCAT(s.skill_code,'|',v.version_code,'|',v.package_digest),256),
 s.update_by='system',s.update_time=NOW()
WHERE s.skill_code IN ('screenplay','screenplay-write','screenplay-review')
  AND (current.id IS NULL OR current.id=v.id OR
    CAST(SUBSTRING_INDEX(current.version_code,'.',1) AS UNSIGNED)<1 OR
    (CAST(SUBSTRING_INDEX(current.version_code,'.',1) AS UNSIGNED)=1 AND
      CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(current.version_code,'.',2),'.',-1) AS UNSIGNED)<2) OR
    (CAST(SUBSTRING_INDEX(current.version_code,'.',1) AS UNSIGNED)=1 AND
      CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(current.version_code,'.',2),'.',-1) AS UNSIGNED)=2 AND
      CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(current.version_code,'.',3),'.',-1) AS UNSIGNED)=0));

-- Existing Run, billing, task and history rows are intentionally preserved.
SET @screenplay_root_prompt := NULL;
SET @screenplay_write_prompt := NULL;
SET @screenplay_review_prompt := NULL;
-- 后台管理菜单与权限。
SET @skill_parent_id := (
  SELECT `menu_id` FROM `sys_menu`
  WHERE `parent_id`=0 AND `menu_type`='M' AND `path`='ai-model'
  ORDER BY `menu_id` LIMIT 1
);
SET @skill_menu_id := (
  SELECT `menu_id` FROM `sys_menu`
  WHERE `component`='aid/skill/index' OR `perms`='aid:skill:list'
  ORDER BY `menu_id` LIMIT 1
);
INSERT INTO `sys_menu`
(`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,`is_frame`,`is_cache`,
 `menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`)
SELECT 'Skill 管理',@skill_parent_id,9,'skill','aid/skill/index',NULL,'',1,0,
       'C','0','0','aid:skill:list','chat','admin',NOW(),'',NULL,'Skill 配置、不可变版本与运行审计'
WHERE @skill_parent_id IS NOT NULL AND @skill_menu_id IS NULL;

SET @skill_menu_id := (
  SELECT `menu_id` FROM `sys_menu`
  WHERE `component`='aid/skill/index' OR `perms`='aid:skill:list'
  ORDER BY `menu_id` LIMIT 1
);
UPDATE `sys_menu`
SET `menu_name`='Skill 管理',`parent_id`=@skill_parent_id,`order_num`=9,
    `path`='skill',`component`='aid/skill/index',`perms`='aid:skill:list',
    `visible`='0',`status`='0',`update_by`='system',`update_time`=NOW(),
    `remark`='Skill 配置、不可变版本与运行审计'
WHERE `menu_id`=@skill_menu_id AND @skill_parent_id IS NOT NULL;

INSERT INTO `sys_menu`
(`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,`is_frame`,`is_cache`,
 `menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`)
SELECT p.menu_name,@skill_menu_id,p.order_num,'#',NULL,NULL,'',1,0,'F','0','0',p.perms,'#',
       'admin',NOW(),'',NULL,''
FROM (
  SELECT 'Skill 详情' menu_name,1 order_num,'aid:skill:query' perms
  UNION ALL SELECT 'Skill 编辑',2,'aid:skill:edit'
  UNION ALL SELECT 'Skill 删除',3,'aid:skill:remove'
  UNION ALL SELECT 'Skill 恢复',4,'aid:skill:restore'
  UNION ALL SELECT 'Skill 运行列表',5,'aid:skill:run:list'
  UNION ALL SELECT 'Skill 运行详情',6,'aid:skill:run:query'
) p
WHERE @skill_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.`perms`=p.perms);

INSERT IGNORE INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT 1,`menu_id` FROM `sys_menu`
WHERE `menu_id`=@skill_menu_id OR `parent_id`=@skill_menu_id;

-- 实时事件负责正常收口，定时任务补偿进程退出和关联落库窗口。
SET @skill_runtime_job_id := (
  SELECT MIN(`job_id`) FROM `sys_job`
  WHERE `invoke_target`='skillRuntimeTask.reconcileStaleRuns()'
);
INSERT INTO `sys_job`
(`job_name`,`job_group`,`invoke_target`,`job_type`,`cron_expression`,`misfire_policy`,
 `concurrent`,`status`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`)
SELECT 'Skill Runtime 补偿','SYSTEM','skillRuntimeTask.reconcileStaleRuns()','1','0 0/1 * * * ?','3','1','0',
       'system',NOW(),'system',NOW(),'按媒体任务终态补偿 Skill Runtime 运行状态'
WHERE @skill_runtime_job_id IS NULL;
SET @skill_runtime_job_id := COALESCE(@skill_runtime_job_id,(
  SELECT MIN(`job_id`) FROM `sys_job`
  WHERE `invoke_target`='skillRuntimeTask.reconcileStaleRuns()'
));
UPDATE `sys_job`
SET `job_name`='Skill Runtime 补偿',`job_group`='SYSTEM',`job_type`='1',
    `cron_expression`='0 0/1 * * * ?',`misfire_policy`='3',`concurrent`='1',`status`='0',
    `update_by`='system',`update_time`=NOW(),`remark`='按媒体任务终态补偿 Skill Runtime 运行状态'
WHERE `job_id`=@skill_runtime_job_id;
-- ===== END 090-skill-runtime.sql =====

-- ===== BEGIN 100-task-error-snapshot.sql =====
-- v2.0.0：任务错误快照。兼容 MySQL 5.7，可重复执行。
-- 独立迁移段，无其他增量迁移依赖；不修改历史任务、配置和规则。

SET @aid_error_snapshot_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'aid_extract_task' AND column_name = 'error_detail_json'),
  'SELECT 1',
  'ALTER TABLE `aid_extract_task` ADD COLUMN `error_detail_json` varchar(4000) NULL DEFAULT NULL COMMENT ''安全错误码与提示快照'' AFTER `error_message`');
PREPARE aid_error_snapshot_stmt FROM @aid_error_snapshot_ddl;
EXECUTE aid_error_snapshot_stmt;
DEALLOCATE PREPARE aid_error_snapshot_stmt;

SET @aid_error_snapshot_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'aid_media_task' AND column_name = 'error_detail_json'),
  'SELECT 1',
  'ALTER TABLE `aid_media_task` ADD COLUMN `error_detail_json` varchar(4000) NULL DEFAULT NULL COMMENT ''安全错误码与提示快照'' AFTER `error_message`');
PREPARE aid_error_snapshot_stmt FROM @aid_error_snapshot_ddl;
EXECUTE aid_error_snapshot_stmt;
DEALLOCATE PREPARE aid_error_snapshot_stmt;
-- ===== END 100-task-error-snapshot.sql =====

-- ===== BEGIN 110-character-form-reference.sql =====
-- ============================================================================
-- 角色形态参考图
-- ============================================================================
-- 角色自动生图直接复用现有角色设定卡形态。
-- MySQL 5.7 compatible; safe to execute repeatedly.
-- 仅迁移公开元数据，不读取或覆盖用户已经维护的提示词正文。

SET NAMES utf8mb4;

UPDATE `aid_agent` AS `target`
SET `target`.`name` = IF(`target`.`name` = '角色白底主图', '角色形态图', `target`.`name`),
    `target`.`introduction` = IF(
        `target`.`introduction` IS NULL
        OR CHAR_LENGTH(TRIM(`target`.`introduction`)) = 0
        OR `target`.`introduction` = '生成角色纯白背景全身主视图'
        OR `target`.`introduction` = '生成白色背景的角色多视角形态图',
        '自动生成角色时直接输出角色设定卡形态图',
        `target`.`introduction`
    ),
    `target`.`update_by` = 'system',
    `target`.`update_time` = NOW()
WHERE `target`.`agent_code` = 'aid_character_form_image_background_white'
  AND `target`.`biz_category_code` = 'main_character_image'
  AND `target`.`del_flag` = '0'
  AND (
      `target`.`name` = '角色白底主图'
      OR `target`.`introduction` IS NULL
      OR CHAR_LENGTH(TRIM(`target`.`introduction`)) = 0
      OR `target`.`introduction` = '生成角色纯白背景全身主视图'
      OR `target`.`introduction` = '生成白色背景的角色多视角形态图'
  );

UPDATE `aid_ai_model_func_config`
SET `func_name` = '角色形态图',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `func_code` = 'main_character_image'
  AND `func_name` = '角色白底主图'
  AND `del_flag` = '0';
-- ===== END 110-character-form-reference.sql =====

-- ===== BEGIN 120-public-baseline-cleanup.sql =====
-- ============================================================================
-- 撤销历史公开版本中非当前公共基线的遗留菜单与配置。
-- 历史孤立表与用户数据保持原样，仅由当前公共代码停止访问，避免升级时破坏既有数据。
-- ============================================================================
DELETE FROM `aid_config`
WHERE `category` = 'basic' AND `config_name` = 'work_publish_enabled';

DELETE role_menu
FROM `sys_role_menu` role_menu
INNER JOIN `sys_menu` menu ON menu.`menu_id` = role_menu.`menu_id`
WHERE menu.`perms` LIKE 'aid:homebanner:%'
   OR menu.`perms` LIKE 'aid:publish:%'
   OR menu.`perms` LIKE 'aid:audit:%'
   OR (menu.`path` = 'homebanner' AND menu.`component` = 'aid/homebanner/index')
   OR (menu.`path` = 'publishmanage' AND menu.`component` = 'aid/publishmanage/index')
   OR (menu.`path` = 'comicaudit' AND menu.`component` = 'aid/audit/index');

DELETE FROM `sys_menu`
WHERE `perms` LIKE 'aid:homebanner:%'
   OR `perms` LIKE 'aid:publish:%'
   OR `perms` LIKE 'aid:audit:%'
   OR (`path` = 'homebanner' AND `component` = 'aid/homebanner/index')
   OR (`path` = 'publishmanage' AND `component` = 'aid/publishmanage/index')
   OR (`path` = 'comicaudit' AND `component` = 'aid/audit/index');
-- ===== END 120-public-baseline-cleanup.sql =====
