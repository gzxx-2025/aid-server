-- v2.1.1：从 v2.1.0 升级，按顺序补齐风格结构、案例审核菜单及 DeepSeek 模型。
-- 可重复执行；保留已有配置、密钥、倍率、状态和用户数据。

-- v2.1.1：补齐风格管理结构，保留已有素材、推荐、排序和分类。
SET NAMES utf8mb4;
SET @schema_name := DATABASE();

SET @exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_asset' AND column_name='hidden_style_prompt_json');
SET @ddl := IF(@exists=0, 'ALTER TABLE `aid_comic_asset` ADD COLUMN `hidden_style_prompt_json` json NULL COMMENT ''隐藏风格提示词配置''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_asset' AND column_name='is_recommended');
SET @ddl := IF(@exists=0, 'ALTER TABLE `aid_comic_asset` ADD COLUMN `is_recommended` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否推荐风格''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='aid_comic_asset' AND column_name='sort_order');
SET @ddl := IF(@exists=0, 'ALTER TABLE `aid_comic_asset` ADD COLUMN `sort_order` int(11) NOT NULL DEFAULT 1000 COMMENT ''展示排序号''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='aid_comic_asset' AND index_name='idx_asset_style_order');
SET @ddl := IF(@exists=0, 'ALTER TABLE `aid_comic_asset` ADD INDEX `idx_asset_style_order` (`asset_type`,`del_flag`,`is_recommended`,`sort_order`,`id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `aid_comic_asset_category` (
  `asset_id` bigint(20) NOT NULL COMMENT '风格资产ID',
  `category_code` varchar(32) NOT NULL COMMENT '风格分类稳定代码',
  PRIMARY KEY (`asset_id`, `category_code`),
  KEY `idx_style_category_asset` (`category_code`, `asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风格与分类关系表';

-- 为内置风格补齐基础分类；已经通过后台维护过分类的风格保持现状。
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_builtin_style_category;
CREATE TEMPORARY TABLE tmp_builtin_style_category (
  asset_name varchar(100) NOT NULL,
  category_codes varchar(255) NOT NULL,
  PRIMARY KEY (asset_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_builtin_style_category (asset_name, category_codes) VALUES
('赛博朋克风', 'two_d,japanese'),
('水墨国风', 'chinese,two_d'),
('古典油画风', 'two_d,western'),
('治愈水彩', 'two_d'),
('怀旧像素', 'two_d,game'),
('哥特暗黑风', 'two_d,western'),
('蒸汽朋克风', 'two_d,western'),
('极简扁平', 'two_d'),
('传统工笔', 'chinese,two_d'),
('手绘速写', 'two_d'),
('经典日漫', 'two_d,japanese'),
('经典美漫', 'two_d,western'),
('治愈动画', 'two_d,japanese'),
('复古少女', 'two_d,japanese'),
('唯美韩漫', 'two_d,korean'),
('热血漫画', 'two_d,japanese'),
('热血冒险', 'two_d,japanese'),
('童趣幻想', 'two_d,chibi'),
('黑暗漫画', 'two_d,japanese'),
('都市志怪', 'two_d,japanese'),
('华丽战斗', 'two_d,japanese'),
('悬疑推理', 'two_d,japanese'),
('青春竞技', 'two_d,japanese'),
('黑白漫画', 'two_d,japanese'),
('潮酷死神', 'two_d,japanese'),
('粗犷涂鸦', 'two_d'),
('奇幻冒险', 'two_d,western'),
('复古动画', 'two_d,japanese'),
('2DQ版', 'two_d,chibi'),
('惊悚手绘', 'two_d,japanese'),
('梦幻童话', 'two_d,western'),
('复古卡通', 'two_d,western'),
('3D美式', 'three_d,western'),
('3D玄幻', 'comic_drama,three_d,chinese'),
('潮玩盲盒', 'three_d,chibi'),
('3D写实', 'three_d,game,western'),
('3D块面', 'three_d,chibi,game'),
('方块世界', 'three_d,game,western'),
('3D手游', 'three_d,game'),
('三渲二', 'three_d,game,western'),
('定格动画', 'three_d,chibi,western'),
('手办定格', 'three_d,chibi,japanese'),
('黏土定格', 'three_d,chibi,western'),
('积木定格', 'three_d,game,western'),
('毛绒定格', 'three_d,chibi'),
('写实电影', 'live_action,western'),
('复古胶片', 'live_action'),
('真实古装', 'live_action,chinese'),
('复古港片', 'live_action,chinese'),
('复古武侠', 'live_action,chinese'),
('真实光晕', 'live_action');

INSERT IGNORE INTO aid_comic_asset_category (asset_id, category_code)
SELECT a.id, c.category_code
FROM aid_comic_asset a
INNER JOIN tmp_builtin_style_category t ON t.asset_name = a.asset_name
INNER JOIN (
    SELECT 'comic_drama' AS category_code
    UNION ALL SELECT 'live_action'
    UNION ALL SELECT 'three_d'
    UNION ALL SELECT 'chinese'
    UNION ALL SELECT 'two_d'
    UNION ALL SELECT 'chibi'
    UNION ALL SELECT 'game'
    UNION ALL SELECT 'japanese'
    UNION ALL SELECT 'western'
    UNION ALL SELECT 'korean'
) c ON FIND_IN_SET(c.category_code, t.category_codes) > 0
LEFT JOIN (
    SELECT DISTINCT asset_id
    FROM aid_comic_asset_category
) existing ON existing.asset_id = a.id
WHERE a.asset_type = 'style'
  AND a.id BETWEEN 1 AND 51
  AND existing.asset_id IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_builtin_style_category;

COMMIT;

-- v2.1.1：恢复案例广场审核与发布管理入口。
-- 仅补缺失菜单；不覆盖已有菜单状态，不自动授予普通角色权限。
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '漫剧运营',0,1,'comic',NULL,'M','0','0','','video','system',NOW(),'漫剧管理目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='comic' AND menu_type='M' AND parent_id=0);
SET @comic_parent := (SELECT MIN(menu_id) FROM sys_menu WHERE path='comic' AND menu_type='M' AND parent_id=0);

INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '作品审核',@comic_parent,2,'comicaudit','aid/audit/index','C','0','0','aid:audit:list','eye-open','system',NOW(),'案例广场作品审核'
WHERE @comic_parent IS NOT NULL AND NOT EXISTS (
 SELECT 1 FROM sys_menu WHERE menu_type='C' AND (component='aid/audit/index' OR perms='aid:audit:list')
);
SET @audit_parent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type='C' AND (component='aid/audit/index' OR perms='aid:audit:list'));
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '审核详情',@audit_parent,1,'#','','F','0','0','aid:audit:query','#','system',NOW(),''
WHERE @audit_parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='aid:audit:query');
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '审核操作',@audit_parent,2,'#','','F','0','0','aid:audit:audit','#','system',NOW(),''
WHERE @audit_parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='aid:audit:audit');

INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '发布管理',@comic_parent,3,'publishmanage','aid/publishmanage/index','C','0','0','aid:publish:list','eye-open','system',NOW(),'案例广场发布管理'
WHERE @comic_parent IS NOT NULL AND NOT EXISTS (
 SELECT 1 FROM sys_menu WHERE menu_type='C' AND (component='aid/publishmanage/index' OR perms='aid:publish:list')
);
SET @publish_parent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type='C' AND (component='aid/publishmanage/index' OR perms='aid:publish:list'));
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '发布查询',@publish_parent,1,'#','','F','0','0','aid:publish:query','#','system',NOW(),''
WHERE @publish_parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='aid:publish:query');
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '发布操作',@publish_parent,2,'#','','F','0','0','aid:publish:edit','#','system',NOW(),''
WHERE @publish_parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='aid:publish:edit');
INSERT INTO sys_menu (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '白名单管理',@publish_parent,3,'#','','F','0','0','aid:publish:whitelist','#','system',NOW(),''
WHERE @publish_parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='aid:publish:whitelist');

COMMIT;

-- v2.1.1：DeepSeek V4.1 Flash 官方模型，复用现有 DeepSeek 供应商。
-- 幂等新增；不覆盖已有模型、密钥、网关、倍率、状态或自定义计费。
-- 计价沿用固定高峰保守策略；空闲/高峰官方费率保留在 officialPricing。
START TRANSACTION;
SET @ds41_provider_id = (SELECT MIN(id) FROM aid_ai_provider WHERE provider_code='deepseek' AND del_flag='0');
SET @ds41_capability = '{"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"supportsImageInput":true,"supportsVideoInput":false,"supportsAudioInput":false,"supportsDocumentInput":false,"maxInputImages":600,"maxInputVideos":0,"maxInputAudios":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","png","gif","webp"],"maxInputImageFileSizeMb":32,"maxInputMediaTotalFileSizeMb":64,"inputMediaMaxUrlLength":8192,"inputImageMaxDimensionPixels":8192,"inputImageHighCountThreshold":15,"inputImageHighCountMaxDimensionPixels":4096,"inputMediaAllowedMessageRoles":["user"],"contextWindowTokens":1000000,"maxOutputTokens":393216,"concurrencyLimit":2500,"supportsStreaming":true,"supportsJsonObject":true,"supportsStructuredOutput":true,"supportsToolCalling":true,"supportsChatPrefix":true,"supportsReasoning":true,"supportsReasoningDisable":true,"supportsReasoningContent":true,"returnsReasoningContent":true,"supportsReasoningBudget":false,"defaultReasoningEnabled":true,"defaultReasoningLevel":"high","allowedReasoningLevels":["minimal","low","medium","high","xhigh","max","ultra"],"reasoningApiStyle":"DEEPSEEK","outputTokenApiField":"max_tokens","capabilityVerifiedAt":"2026-09-12","capabilitySourceUrls":["https://api-docs.deepseek.com/zh-cn/guides/vision/","https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"]}';
SET @ds41_billing = '{"mode":"SKU","chargeType":"TEXT","meterType":"TOKEN","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"DEEPSEEK_V41_FLASH","skuName":"Flash Token","enabled":true,"priority":1,"match":{},"inputPricePerMillion":2,"outputPricePerMillion":8,"cachedInputPricePerMillion":0.04,"remark":"高峰价固定计价；百万Token缓存未命中2元、命中0.04元、输出8元。未自动按时段切换。"}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2},"officialPricing":{"currency":"CNY","unit":"MILLION_TOKENS","timezone":"Asia/Shanghai","peak":{"weekdays":[1,2,3,4,5],"intervals":[["09:00","12:00"],["14:00","18:00"]],"input":2,"cacheRead":0.04,"output":8},"offPeak":{"input":1,"cacheRead":0.02,"output":4}}}';
INSERT INTO aid_ai_model
(provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
 cost_credits,billing_multiplier,api_suffix,protocol,priority,status,del_flag,
 create_time,create_by,update_time,update_by,remark,billing_mode,billing_rule_json,
 billing_version,supports_text_input,supports_system_prompt,supports_image_input,
 supports_multi_image_input,max_output_count,default_output_count,supports_aspect_ratio,
 supports_size_preset,supports_duration,supports_first_frame,supports_last_frame,
 capability_json,capability_inited,official_price_url,config_version)
SELECT @ds41_provider_id,'deepseek-flash','deepseek-flash','DeepSeek V4.1 Flash','text','text',
 0,1,'/beta/chat/completions','deepseek:chat-completions',100,'1','0',
 NOW(),'system',NOW(),'system','DeepSeek V4.1 Flash；图片理解、思考工具续轮、JSON、前缀续写及FIM；固定高峰价计费，空闲价不自动切换。',
 'SKU',@ds41_billing,1,1,1,1,1,1,1,0,0,0,0,0,@ds41_capability,1,
 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',1
FROM DUAL WHERE @ds41_provider_id IS NOT NULL
 AND NOT EXISTS (SELECT 1 FROM aid_ai_model WHERE model_code='deepseek-flash');
SET @ds41_inserted = ROW_COUNT();
SET @ds41_model_id = IF(@ds41_inserted=1,LAST_INSERT_ID(),NULL);
INSERT INTO aid_ai_model_capability (model_id,capability_code,generate_mode,definition_json,sort_order,create_time,create_by)
SELECT @ds41_model_id,'text','text','{"code":"text","label":"文本与图片理解","generateMode":"text","enabled":true,"defaultCapability":true,"evidenceStatus":"VERIFIED_OFFICIAL","sourceUrls":["https://api-docs.deepseek.com/zh-cn/guides/vision/","https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"],"parameters":[{"name":"prompt","label":"文本提示词","type":"string","widget":"textarea"},{"name":"messages","label":"多轮对话与图片","type":"array","items":{"name":"item","label":"列表项","type":"object"}},{"name":"reasoningEnabled","label":"开启思考","type":"boolean","defaultValue":true},{"name":"reasoningLevel","label":"思考强度","type":"string","choices":["minimal","low","medium","high","xhigh","max","ultra"],"defaultValue":"high"},{"name":"includeReasoning","label":"返回思考内容","type":"boolean"},{"name":"options","label":"生成参数","type":"object","properties":[{"name":"max_tokens","label":"最大输出 Token","type":"integer","minimum":1,"maximum":393216},{"name":"temperature","label":"非思考采样温度","type":"number","minimum":0,"maximum":2},{"name":"top_p","label":"思考核采样概率","type":"number","minimum":0,"maximum":1},{"name":"stop","label":"停止词","type":"array","items":{"name":"item","label":"列表项","type":"string"}},{"name":"tools","label":"客户端工具","type":"array","items":{"name":"item","label":"列表项","type":"object"}},{"name":"response_format","label":"输出格式","type":"object","properties":[{"name":"type","label":"格式","type":"string","choices":["text","json_object"]}]}]}],"rules":[],"presentation":{"supportsTextInput":true,"supportsSystemPrompt":true,"supportsImageInput":true,"supportsMultiImageInput":true,"supportsAspectRatio":false,"supportsSizePreset":false,"supportsDuration":false,"maxOutputCount":1,"defaultOutputCount":1}}',0,NOW(),'system' FROM DUAL WHERE @ds41_model_id IS NOT NULL;
INSERT INTO aid_ai_model_protocol_binding (model_id,capability_code,binding_code,protocol,definition_json,sort_order,create_time,create_by)
SELECT @ds41_model_id,'text','deepseek_chat','deepseek:chat-completions','{"code":"deepseek_chat","protocol":"deepseek:chat-completions","upstreamModel":"deepseek-flash","apiSuffix":"/beta/chat/completions","enabled":true,"defaultBinding":true,"capability":{"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"supportsImageInput":true,"supportsVideoInput":false,"supportsAudioInput":false,"supportsDocumentInput":false,"maxInputImages":600,"maxInputVideos":0,"maxInputAudios":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","png","gif","webp"],"maxInputImageFileSizeMb":32,"maxInputMediaTotalFileSizeMb":64,"inputMediaMaxUrlLength":8192,"inputImageMaxDimensionPixels":8192,"inputImageHighCountThreshold":15,"inputImageHighCountMaxDimensionPixels":4096,"inputMediaAllowedMessageRoles":["user"],"contextWindowTokens":1000000,"maxOutputTokens":393216,"concurrencyLimit":2500,"supportsStreaming":true,"supportsJsonObject":true,"supportsStructuredOutput":true,"supportsToolCalling":true,"supportsChatPrefix":true,"supportsReasoning":true,"supportsReasoningDisable":true,"supportsReasoningContent":true,"returnsReasoningContent":true,"supportsReasoningBudget":false,"defaultReasoningEnabled":true,"defaultReasoningLevel":"high","allowedReasoningLevels":["minimal","low","medium","high","xhigh","max","ultra"],"reasoningApiStyle":"DEEPSEEK","outputTokenApiField":"max_tokens","capabilityVerifiedAt":"2026-09-12","capabilitySourceUrls":["https://api-docs.deepseek.com/zh-cn/guides/vision/","https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"]},"presentation":{"supportsTextInput":true,"supportsSystemPrompt":true,"supportsImageInput":true,"supportsMultiImageInput":true,"supportsAspectRatio":false,"supportsSizePreset":false,"supportsDuration":false,"maxOutputCount":1,"defaultOutputCount":1},"fixedParameters":{},"parameterMapping":{},"billingMode":"SKU","billingRule":{"mode":"SKU","chargeType":"TEXT","meterType":"TOKEN","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"DEEPSEEK_V41_FLASH","skuName":"Flash Token","enabled":true,"priority":1,"match":{},"inputPricePerMillion":2,"outputPricePerMillion":8,"cachedInputPricePerMillion":0.04,"remark":"高峰价固定计价；百万Token缓存未命中2元、命中0.04元、输出8元。未自动按时段切换。"}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2},"officialPricing":{"currency":"CNY","unit":"MILLION_TOKENS","timezone":"Asia/Shanghai","peak":{"weekdays":[1,2,3,4,5],"intervals":[["09:00","12:00"],["14:00","18:00"]],"input":2,"cacheRead":0.04,"output":8},"offPeak":{"input":1,"cacheRead":0.02,"output":4}}},"costCredits":0}',0,NOW(),'system'
FROM DUAL WHERE @ds41_model_id IS NOT NULL;
INSERT INTO aid_ai_model_capability (model_id,capability_code,generate_mode,definition_json,sort_order,create_time,create_by)
SELECT @ds41_model_id,'fim','text','{"code":"fim","label":"前后缀补全","generateMode":"text","enabled":true,"defaultCapability":false,"evidenceStatus":"VERIFIED_OFFICIAL","sourceUrls":["https://api-docs.deepseek.com/api/create-completion/","https://api-docs.deepseek.com/zh-cn/guides/fim_completion/","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"],"parameters":[{"name":"prompt","label":"文本提示词","type":"string","widget":"textarea"},{"name":"options","label":"补全参数","type":"object","properties":[{"name":"suffix","label":"补全后缀","type":"string","widget":"textarea"},{"name":"max_tokens","label":"最大补全 Token","type":"integer","minimum":1,"maximum":4096},{"name":"temperature","label":"采样温度","type":"number","minimum":0,"maximum":2}]}],"rules":[],"presentation":{"supportsTextInput":true,"supportsSystemPrompt":false,"supportsImageInput":false,"supportsMultiImageInput":false,"supportsAspectRatio":false,"supportsSizePreset":false,"supportsDuration":false,"maxOutputCount":1,"defaultOutputCount":1}}',1,NOW(),'system' FROM DUAL WHERE @ds41_model_id IS NOT NULL;
INSERT INTO aid_ai_model_protocol_binding (model_id,capability_code,binding_code,protocol,definition_json,sort_order,create_time,create_by)
SELECT @ds41_model_id,'fim','deepseek_fim','deepseek:fim','{"code":"deepseek_fim","protocol":"deepseek:fim","upstreamModel":"deepseek-flash","apiSuffix":"/beta/completions","enabled":true,"defaultBinding":true,"capability":{"inputModalities":["TEXT"],"outputModalities":["TEXT"],"supportsImageInput":false,"supportsVideoInput":false,"supportsAudioInput":false,"supportsDocumentInput":false,"maxInputImages":0,"maxInputVideos":0,"maxInputAudios":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","png","gif","webp"],"maxInputImageFileSizeMb":32,"maxInputMediaTotalFileSizeMb":64,"inputMediaMaxUrlLength":8192,"inputImageMaxDimensionPixels":8192,"inputImageHighCountThreshold":15,"inputImageHighCountMaxDimensionPixels":4096,"inputMediaAllowedMessageRoles":["user"],"contextWindowTokens":1000000,"maxOutputTokens":4096,"concurrencyLimit":2500,"supportsStreaming":true,"supportsJsonObject":false,"supportsStructuredOutput":false,"supportsToolCalling":false,"supportsChatPrefix":false,"supportsReasoning":false,"supportsReasoningDisable":true,"supportsReasoningContent":false,"returnsReasoningContent":false,"supportsReasoningBudget":false,"defaultReasoningEnabled":false,"defaultReasoningLevel":null,"allowedReasoningLevels":[],"reasoningApiStyle":"DEEPSEEK","outputTokenApiField":"max_tokens","capabilityVerifiedAt":"2026-09-12","capabilitySourceUrls":["https://api-docs.deepseek.com/api/create-completion/","https://api-docs.deepseek.com/zh-cn/guides/fim_completion/","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"]},"presentation":{"supportsTextInput":true,"supportsSystemPrompt":false,"supportsImageInput":false,"supportsMultiImageInput":false,"supportsAspectRatio":false,"supportsSizePreset":false,"supportsDuration":false,"maxOutputCount":1,"defaultOutputCount":1},"fixedParameters":{},"parameterMapping":{},"billingMode":"SKU","billingRule":{"mode":"SKU","chargeType":"TEXT","meterType":"TOKEN","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"DEEPSEEK_V41_FLASH","skuName":"Flash Token","enabled":true,"priority":1,"match":{},"inputPricePerMillion":2,"outputPricePerMillion":8,"cachedInputPricePerMillion":0.04,"remark":"高峰价固定计价；百万Token缓存未命中2元、命中0.04元、输出8元。未自动按时段切换。"}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2},"officialPricing":{"currency":"CNY","unit":"MILLION_TOKENS","timezone":"Asia/Shanghai","peak":{"weekdays":[1,2,3,4,5],"intervals":[["09:00","12:00"],["14:00","18:00"]],"input":2,"cacheRead":0.04,"output":8},"offPeak":{"input":1,"cacheRead":0.02,"output":4}}},"costCredits":0}',0,NOW(),'system'
FROM DUAL WHERE @ds41_model_id IS NOT NULL;
COMMIT;
