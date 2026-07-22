-- ==========================================================================
-- Agnes 2.5 Flash 文本模型：官方文档核对后的价格与配置留档（幂等，可反复执行）
-- 适用库：aid（正式）/ aid_test（测试），执行前先 USE 对应库
-- 官方文档：参考文件/doc/usedoc/agnes接口文档.txt「Agnes 2.5 Flash」
--   端点 POST /v1/chat/completions，与 agnes-2.0-flash API 完全兼容
--   上下文窗口 512K；标准价 输入 $0.03/1M、输出 $0.15/1M（1USD=7CNY → 0.21 / 1.05 元）
--   灰度期现价 $0 不采用，按标准原价维护 SKU
--   思考控制走 chat_template_kwargs.enable_thinking，非思考+非流式钳制在 extra_body
-- ==========================================================================

-- 1. 下线替换：物理删除 agnes-1.5-flash（上游渠道已移除，官方文档不再收录）
DELETE FROM aid_ai_model
WHERE model_code = 'agnes-1.5-flash';

-- 2. 幂等插入 agnes-2.5-flash（已存在则跳过；provider_id 动态取 agnes 供应商）
INSERT INTO aid_ai_model
    (provider_id, model_code, real_model_code, model_name, model_type, generate_mode,
     cost_credits, billing_multiplier, api_suffix, protocol, priority, status, del_flag,
     create_time, create_by, billing_mode, billing_version,
     supports_text_input, supports_system_prompt, supports_image_input, supports_multi_image_input,
     max_output_count, default_output_count,
     extra_body, capability_json, billing_rule_json, official_price_url, remark)
SELECT p.id, 'agnes-2.5-flash', 'agnes-2.5-flash', 'Agnes 2.5 Flash', 'text', 'text',
       0, 1.0000, '/v1/chat/completions', 'openai-compatible-text', 100, '0', '0',
       NOW(), 'admin', 'SKU', 1,
       1, 1, 1, 0,
       1, 1,
       '{"stream": false, "chat_template_kwargs": {"enable_thinking": false}}',
       '{"sceneRules":{"textOnly":{"supportsAspectRatio":false,"supportsSizePreset":false,"supportsDuration":false}}}',
       '{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"AGNES_25_FLASH_0_512K","skuName":"输入Token 0-512K","enabled":true,"priority":1,"match":{"inputTokensMin":0,"inputTokensMax":512000},"remark":"官方标准价：输入$0.03/1M=0.21元、输出$0.15/1M=1.05元(1USD=7CNY)；灰度现价$0不采用，按标准原价维护","inputPricePerMillion":0.21,"outputPricePerMillion":1.05}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"allowRefund":true,"allowExtraCharge":false}}',
       'https://wiki.agnes-ai.com',
       'Agnes 2.5 Flash（替换已下线的 agnes-1.5-flash）；官方标准价维护；钳制非思考+非流式'
FROM aid_ai_provider p
WHERE p.provider_code = 'agnes' AND p.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.model_code = 'agnes-2.5-flash');

-- 3. 价格与钳制配置对齐（对已存在行幂等修正：官方标准价 0.21/1.05、非思考+非流式）
UPDATE aid_ai_model
SET billing_rule_json = '{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"skuCode":"AGNES_25_FLASH_0_512K","skuName":"输入Token 0-512K","enabled":true,"priority":1,"match":{"inputTokensMin":0,"inputTokensMax":512000},"remark":"官方标准价：输入$0.03/1M=0.21元、输出$0.15/1M=1.05元(1USD=7CNY)；灰度现价$0不采用，按标准原价维护","inputPricePerMillion":0.21,"outputPricePerMillion":1.05}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"allowRefund":true,"allowExtraCharge":false}}',
    extra_body = '{"stream": false, "chat_template_kwargs": {"enable_thinking": false}}',
    update_time = NOW(),
    update_by = 'admin'
WHERE model_code = 'agnes-2.5-flash'
  AND del_flag = '0';
