-- v1.0.0-beta.2 数据库迁移脚本。
-- 本脚本可重复执行：配置项依赖 aid_config(category, config_name) 唯一索引防重，
-- 已由管理员填写的 config_value 不会被初始化值覆盖。

START TRANSACTION;

-- 一、后台品牌图片：登录页和后台左上角统一使用平台 LOGO。
INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `version`, `update_ip`, `tenant_id`
)
SELECT
  'admin_brand',
  'platform_logo_url',
  COALESCE(
    NULLIF(TRIM(MAX(CASE WHEN `config_name` = 'login_logo_url' THEN `config_value` END)), ''),
    NULLIF(TRIM(MAX(CASE WHEN `config_name` = 'sidebar_logo_url' THEN `config_value` END)), ''),
    ''
  ),
  '平台LOGO地址',
  '0',
  1,
  NOW(),
  'system',
  'system',
  NOW(),
  '登录页和后台左上角共用',
  NULL,
  NULL,
  0
FROM `aid_config`
WHERE `category` = 'admin_brand'
  AND `config_name` IN ('login_logo_url', 'sidebar_logo_url')
HAVING NOT EXISTS (
  SELECT 1
  FROM `aid_config` existing
  WHERE existing.`category` = 'admin_brand'
    AND existing.`config_name` = 'platform_logo_url'
);

-- 平台 LOGO 已存在但为空时，从旧配置迁移；已有新配置值时保持不变。
UPDATE `aid_config` platform
LEFT JOIN `aid_config` login_logo
  ON login_logo.`category` = 'admin_brand'
 AND login_logo.`config_name` = 'login_logo_url'
LEFT JOIN `aid_config` sidebar_logo
  ON sidebar_logo.`category` = 'admin_brand'
 AND sidebar_logo.`config_name` = 'sidebar_logo_url'
SET platform.`config_value` = COALESCE(
      NULLIF(TRIM(platform.`config_value`), ''),
      NULLIF(TRIM(login_logo.`config_value`), ''),
      NULLIF(TRIM(sidebar_logo.`config_value`), ''),
      ''
    ),
    platform.`config_dict` = '平台LOGO地址',
    platform.`del_flag` = '0',
    platform.`order_num` = 1,
    platform.`update_by` = 'system',
    platform.`update_time` = NOW()
WHERE platform.`category` = 'admin_brand'
  AND platform.`config_name` = 'platform_logo_url';

DELETE FROM `aid_config`
WHERE `category` = 'admin_brand'
  AND `config_name` IN ('login_logo_url', 'sidebar_logo_url');

UPDATE `aid_config`
SET `config_dict` = '浏览器页签图标地址',
    `order_num` = 2,
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `category` = 'admin_brand'
  AND `config_name` = 'favicon_url';

-- 二、删除基础配置中已废弃的版本号；项目升级分类不受影响。
DELETE FROM `aid_config`
WHERE `category` = 'basic'
  AND `config_name` = 'version_number';

-- 三、基础配置：网站 SEO 与会员协议。
INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`
) VALUES
  ('basic', 'site_name', '', '网站名称', '0', 17, NOW(), 'system', 'system', NOW(), '用于搜索展示与浏览器标题', 0),
  ('basic', 'site_description', '', '网站描述', '0', 18, NOW(), 'system', 'system', NOW(), '用于搜索结果摘要', 0),
  ('basic', 'site_keywords', '', '网站关键词', '0', 19, NOW(), 'system', 'system', NOW(), '多个关键词使用英文逗号分隔', 0),
  ('basic', 'membership_agreement', '', '会员协议', '0', 6, NOW(), 'system', 'system', NOW(), '会员开通与权益规则页面地址', 0)
ON DUPLICATE KEY UPDATE
  `config_dict` = VALUES(`config_dict`),
  `del_flag` = '0',
  `order_num` = VALUES(`order_num`),
  `remark` = VALUES(`remark`);

-- 四、短信宝渠道。API Key 留空，由管理员在后台填写。
INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`
) VALUES
  ('sms', 'smsBaoUsername', '', '短信宝用户名', '0', 20, NOW(), 'system', 'system', NOW(), '短信宝官网 smsbao.com', 0),
  ('sms', 'smsBaoApiKey', '', '短信宝API Key', '0', 21, NOW(), 'system', 'system', NOW(), '敏感配置，请在后台填写', 0),
  ('sms', 'smsBaoProductId', '', '短信宝产品ID', '0', 22, NOW(), 'system', 'system', NOW(), '专用通道产品ID，可不填', 0),
  ('sms', 'smsBaoContentTemplate', '【视觉AID】您的验证码是{code}', '短信内容模板（含签名）', '0', 23, NOW(), 'system', 'system', NOW(), '必须包含{code}占位符', 0)
ON DUPLICATE KEY UPDATE
  `config_dict` = VALUES(`config_dict`),
  `del_flag` = '0',
  `order_num` = VALUES(`order_num`),
  `remark` = VALUES(`remark`);

-- 五、短信验证码策略：缺项时补齐，已有运营配置保持不变。
INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `tenant_id`
) VALUES
  ('sms', 'code_length', '6', '短信验证码长度（位）', '0', 100, NOW(), 'system', 'system', NOW(), NULL, 0),
  ('sms', 'code_expire_minutes', '5', '短信验证码有效期（分钟）', '0', 101, NOW(), 'system', 'system', NOW(), NULL, 0),
  ('sms', 'send_interval_seconds', '120', '同手机号/同IP两次发送的最小间隔（秒）', '0', 102, NOW(), 'system', 'system', NOW(), NULL, 0),
  ('sms', 'daily_limit', '10', '同手机号/同IP每自然日最多发送次数', '0', 103, NOW(), 'system', 'system', NOW(), NULL, 0)
ON DUPLICATE KEY UPDATE
  `config_dict` = VALUES(`config_dict`),
  `del_flag` = '0',
  `order_num` = VALUES(`order_num`);

-- 补充已有短信渠道字段的展示说明，不改变当前选中的渠道。
UPDATE `aid_config`
SET `config_dict` = '服务商类型(aliyun/tencent/smsbao)',
    `remark` = '短信服务商'
WHERE `category` = 'sms'
  AND `config_name` = 'providerType';

-- 六、统一API基础网关；版本与接口路径由模型配置承载。
INSERT INTO `aid_config` (
  `category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`,
  `create_time`, `create_by`, `update_by`, `update_time`, `remark`, `version`, `update_ip`, `tenant_id`
) VALUES (
  'official_gateway', 'base_url', 'https://api.aidstudio.com.cn', '官方统一网关基础地址',
  '0', 2, NOW(), 'system', 'system', NOW(), NULL, NULL, NULL, 0
)
ON DUPLICATE KEY UPDATE
  `config_value` = CASE
    WHEN TRIM(`config_value`) IN (
      '',
      'https://api.aid-demo.example.com',
      'https://api.aid-demo.example.com/',
      'https://api.aid-demo.example.com/v1',
      'https://api.aid-demo.example.com/v1/'
    ) THEN VALUES(`config_value`)
    ELSE `config_value`
  END,
  `config_dict` = VALUES(`config_dict`),
  `del_flag` = '0',
  `order_num` = VALUES(`order_num`),
  `update_by` = 'system',
  `update_time` = NOW();

-- 火山方舟REST模型保留 /api/v3，SDK模型不拼接HTTP接口路径。
UPDATE `aid_ai_model` model
INNER JOIN `aid_ai_provider` provider ON provider.`id` = model.`provider_id`
SET model.`api_suffix` = CONCAT('/api/v3', model.`api_suffix`),
    model.`update_by` = 'system',
    model.`update_time` = NOW()
WHERE provider.`provider_code` = 'volcengine'
  AND model.`api_suffix` LIKE '/%'
  AND model.`api_suffix` <> '/api/v3'
  AND model.`api_suffix` NOT LIKE '/api/v3/%';

UPDATE `aid_ai_provider`
SET `base_url` = 'https://ark.cn-beijing.volces.com',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `provider_code` = 'volcengine'
  AND TRIM(TRAILING '/' FROM TRIM(`base_url`)) = 'https://ark.cn-beijing.volces.com/api/v3';

-- 视频模型错误提示优化：区分内容审核、参考图审核、模型额度与队列繁忙。
UPDATE `aid_provider_error_rule`
SET `user_message` = '提示词或参考图未通过审核，请修改后重试',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `provider_code` = 'jimeng'
  AND `rule_name` = '即梦 50411/50412/50413/50518 输入审核未通过';

UPDATE `aid_provider_error_rule`
SET `user_message` = '生成内容未通过审核，请调整提示词或参考图',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `provider_code` = 'jimeng'
  AND `rule_name` = '即梦 50511/50512/50519 输出审核未通过';

UPDATE `aid_provider_error_rule`
SET `match_pattern` = 'timeout,timed out,超时,deadline_exceeded,context deadline exceeded,requesttimeout,connect timed out,conn timeout,recv timeout,client disconnect',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `rule_name` = '超时(通用)';

UPDATE `aid_provider_error_rule`
SET `match_pattern` = 'image queue is full,video queue is full,queue is full,service busy,no available server,system memory overloaded,server overloaded',
    `user_message` = '当前生成任务较多，稍后重试',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `rule_name` = '模型服务繁忙或队列已满';

UPDATE `aid_provider_error_rule`
SET `user_message` = '提示词或参考图未通过审核，请修改后重试',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `rule_name` = '内容策略拒绝生成';

UPDATE `aid_provider_error_rule`
SET `user_message` = '模型额度不足，请联系管理员',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `rule_name` = '模型供应商额度不足';

INSERT INTO `aid_provider_error_rule`
    (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`,
     `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'vidu', NULL, 'Vidu 提交内容审核未通过', 'KEYWORD',
       'AuditSubmitIllegal,audit submit illegal', NULL, 0, 'UPSTREAM_CONTENT_FILTERED',
       '提示词或参考图未通过审核，请修改后重试', 4, 1, 1,
       'Vidu 提交阶段审核拒绝，无法仅凭错误码区分提示词或参考图',
       'system', NOW(), 'system', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `aid_provider_error_rule`
    WHERE `provider_code` = 'vidu' AND `rule_name` = 'Vidu 提交内容审核未通过'
);

INSERT INTO `aid_provider_error_rule`
    (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`,
     `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT NULL, NULL, '参考图内容审核未通过', 'REGEX',
       '(input image|input images|reference image|source image).{0,120}(sensitive|risk not pass|policy violation|content filter|moderation|audit illegal)',
       NULL, 0, 'UPSTREAM_CONTENT_FILTERED', '参考图未通过内容审核，请更换后重试', 4, 1, 1,
       '输入图或参考图被上游内容审核拒绝', 'system', NOW(), 'system', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `aid_provider_error_rule`
    WHERE `provider_code` IS NULL AND `model_code` IS NULL AND `rule_name` = '参考图内容审核未通过'
);

INSERT INTO `aid_provider_error_rule`
    (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`,
     `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT NULL, NULL, '提示词内容审核未通过', 'REGEX',
       '(prompt|input text|text input).{0,120}(sensitive|risk not pass|policy violation|content filter|moderation|audit illegal)',
       NULL, 0, 'UPSTREAM_CONTENT_FILTERED', '提示词未通过内容审核，请修改后重试', 4, 1, 1,
       '提示词或文本输入被上游内容审核拒绝', 'system', NOW(), 'system', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `aid_provider_error_rule`
    WHERE `provider_code` IS NULL AND `model_code` IS NULL AND `rule_name` = '提示词内容审核未通过'
);

INSERT INTO `aid_provider_error_rule`
    (`provider_code`, `model_code`, `rule_name`, `match_type`, `match_pattern`, `match_field`,
     `case_sensitive`, `error_code`, `user_message`, `priority`, `enabled`, `is_builtin`, `remark`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT NULL, NULL, '生成内容审核未通过', 'REGEX',
       '(output|generated image|generated video|result).{0,120}(sensitive|risk not pass|policy violation|content filter|moderation|audit illegal)',
       NULL, 0, 'UPSTREAM_CONTENT_FILTERED', '生成内容未通过审核，请调整提示词或参考图', 4, 1, 1,
       '模型输出被上游内容审核拒绝', 'system', NOW(), 'system', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `aid_provider_error_rule`
    WHERE `provider_code` IS NULL AND `model_code` IS NULL AND `rule_name` = '生成内容审核未通过'
);

COMMIT;
