-- ==========================================================================
-- 豆包 Seedance 2.0 Fast：参考 Seedance 2.0 配置，按官方文档替换差异项（幂等）
-- 适用库：aid / aid_test，执行前先 USE 对应库
-- 官方文档：参考文件/doc/usedoc/模型官方文档/火山方舟.txt
--   Model ID：doubao-seedance-2-0-fast-260128
--   分辨率仅 480p / 720p（不支持 1080p、4k）
--   Token 单价：不含输入视频 37 元/百万；含输入视频 22 元/百万
--   时长 4~15 秒；参考图最多 9；输入视频最多 3 段总时长≤15 秒；音画同生
--   秒单价换算沿用 2.0 的 token 用量基准 × Fast 单价比
--     不含视频：480P 0.371628 元/秒、720P 0.7992 元/秒
--     含视频：  480P 0.220968 元/秒、720P 0.4752 元/秒
-- ==========================================================================

-- 1. 幂等插入（已存在则跳过）
INSERT INTO aid_ai_model
    (provider_id, model_code, real_model_code, model_name, model_type, generate_mode,
     api_version, cost_credits, billing_multiplier, api_suffix, protocol, priority,
     status, del_flag, create_time, create_by, update_time, update_by, remark,
     billing_mode, billing_rule_json, billing_version, schedule_strategy_json,
     image_refine, supports_text_input, supports_system_prompt, supports_image_input,
     supports_multi_image_input, max_output_count, default_output_count,
     supports_aspect_ratio, supports_size_preset, supports_duration,
     supports_first_frame, supports_last_frame,
     default_size_code, default_aspect_ratio, default_duration_seconds,
     capability_json, param_mapping_json, capability_inited, extra_body, official_price_url)
SELECT
     p.id,
     'doubao-seedance-2.0-fast',
     'doubao-seedance-2-0-fast-260128',
     '豆包Seedance 2.0 Fast',
     'video',
     'image_to_video',
     NULL,
     0.000000,
     1.0000,
     'SDK:createContentGenerationTask',
     NULL,
     100,
     '1',
     '0',
     NOW(),
     'admin',
     NOW(),
     'admin',
     '豆包Seedance 2.0 Fast；官方原价token精确换算积分/秒；含/不含输入视频双档；仅480P/720P；参考图最多9、输入视频最多3段总时长≤15秒；音画同生；保持停用',
     'SKU',
     '{"mode":"SKU","skus":[{"match":{"resolution":"480P","inputVideoCountMin":1},"price":1.10,"remark":"官方原价含输入视频22元/百万token精确换算0.220968元/秒,输入输出同价双计","enabled":true,"skuCode":"SEEDANCE20_FAST_480P_INVIDEO","skuName":"Seedance2.0 Fast 480P含输入视频","priority":1,"inputPricing":{"video":{"maxCount":3,"unitPrice":0.220968,"maxSeconds":15}},"pricePerSecond":0.220968},{"match":{"resolution":"720P","inputVideoCountMin":1},"price":2.38,"remark":"官方原价含输入视频22元/百万token精确换算0.4752元/秒,输入输出同价双计","enabled":true,"skuCode":"SEEDANCE20_FAST_720P_INVIDEO","skuName":"Seedance2.0 Fast 720P含输入视频","priority":2,"inputPricing":{"video":{"maxCount":3,"unitPrice":0.4752,"maxSeconds":15}},"pricePerSecond":0.4752},{"match":{"resolution":"480P"},"price":1.86,"remark":"官方原价37元/百万token精确换算0.371628元/秒=37.1628积分/秒","enabled":true,"skuCode":"SEEDANCE20_FAST_480P","skuName":"Seedance2.0 Fast 480P","priority":11,"pricePerSecond":0.371628},{"match":{"resolution":"720P"},"price":4.00,"remark":"官方原价37元/百万token精确换算0.7992元/秒=79.92积分/秒","enabled":true,"skuCode":"SEEDANCE20_FAST_720P","skuName":"Seedance2.0 Fast 720P","priority":12,"pricePerSecond":0.7992}],"params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["480P","720P"],"required":true},{"code":"duration","name":"时长","type":"NUMBER","unit":"秒","required":true},{"code":"inputVideoCount","name":"输入视频段数","type":"NUMBER","required":false},{"code":"inputVideoSeconds","name":"输入视频总秒数","type":"NUMBER","required":false}],"preHold":true,"meterType":"PER_SECOND","chargeType":"VIDEO","settleRule":{"settleMode":"DIRECT_SETTLE","allowRefund":true,"usageSource":"REQUEST_PARAM","allowExtraCharge":false},"matchStrategy":"FIRST_HIT"}',
     4,
     '{"maxConcurrency":1}',
     NULL,
     1,
     1,
     1,
     1,
     1,
     1,
     1,
     1,
     1,
     0,
     0,
     '720P',
     '16:9',
     5,
     '{"sizeOptions":["480P","720P"],"defaultSize":"720P","aspectRatioOptions":["16:9","9:16","1:1","4:3","3:4","21:9"],"defaultAspectRatio":"16:9","durationOptions":[4,5,6,7,8,9,10,11,12,13,14,15],"defaultDurationSeconds":5,"supportsAudio":true,"maxReferenceImages":9,"minReferenceImages":0,"supportsVideoInput":true,"maxReferenceVideos":3,"maxInputVideoSeconds":15,"sceneRules":{"textToVideo":{"supportsAspectRatio":true,"supportsSizePreset":true,"supportsDuration":true},"imageToVideo":{"supportsAspectRatio":true,"supportsSizePreset":true,"supportsDuration":true,"aspectRatioFollowInput":true}}}',
     NULL,
     1,
     NULL,
     NULL
FROM aid_ai_provider p
WHERE p.provider_code = 'volcengine' AND p.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.model_code = 'doubao-seedance-2.0-fast');

-- 2. 已存在则幂等对齐（按官方 Fast 差异覆盖关键字段）
UPDATE aid_ai_model
SET real_model_code = 'doubao-seedance-2-0-fast-260128',
    model_name = '豆包Seedance 2.0 Fast',
    model_type = 'video',
    generate_mode = 'image_to_video',
    cost_credits = 0.000000,
    billing_multiplier = 1.0000,
    api_suffix = 'SDK:createContentGenerationTask',
    billing_mode = 'SKU',
    billing_version = 4,
    schedule_strategy_json = '{"maxConcurrency":1}',
    supports_text_input = 1,
    supports_system_prompt = 1,
    supports_image_input = 1,
    supports_multi_image_input = 1,
    max_output_count = 1,
    default_output_count = 1,
    supports_aspect_ratio = 1,
    supports_size_preset = 1,
    supports_duration = 1,
    supports_first_frame = 0,
    supports_last_frame = 0,
    default_size_code = '720P',
    default_aspect_ratio = '16:9',
    default_duration_seconds = 5,
    capability_inited = 1,
    capability_json = '{"sizeOptions":["480P","720P"],"defaultSize":"720P","aspectRatioOptions":["16:9","9:16","1:1","4:3","3:4","21:9"],"defaultAspectRatio":"16:9","durationOptions":[4,5,6,7,8,9,10,11,12,13,14,15],"defaultDurationSeconds":5,"supportsAudio":true,"maxReferenceImages":9,"minReferenceImages":0,"supportsVideoInput":true,"maxReferenceVideos":3,"maxInputVideoSeconds":15,"sceneRules":{"textToVideo":{"supportsAspectRatio":true,"supportsSizePreset":true,"supportsDuration":true},"imageToVideo":{"supportsAspectRatio":true,"supportsSizePreset":true,"supportsDuration":true,"aspectRatioFollowInput":true}}}',
    billing_rule_json = '{"mode":"SKU","skus":[{"match":{"resolution":"480P","inputVideoCountMin":1},"price":1.10,"remark":"官方原价含输入视频22元/百万token精确换算0.220968元/秒,输入输出同价双计","enabled":true,"skuCode":"SEEDANCE20_FAST_480P_INVIDEO","skuName":"Seedance2.0 Fast 480P含输入视频","priority":1,"inputPricing":{"video":{"maxCount":3,"unitPrice":0.220968,"maxSeconds":15}},"pricePerSecond":0.220968},{"match":{"resolution":"720P","inputVideoCountMin":1},"price":2.38,"remark":"官方原价含输入视频22元/百万token精确换算0.4752元/秒,输入输出同价双计","enabled":true,"skuCode":"SEEDANCE20_FAST_720P_INVIDEO","skuName":"Seedance2.0 Fast 720P含输入视频","priority":2,"inputPricing":{"video":{"maxCount":3,"unitPrice":0.4752,"maxSeconds":15}},"pricePerSecond":0.4752},{"match":{"resolution":"480P"},"price":1.86,"remark":"官方原价37元/百万token精确换算0.371628元/秒=37.1628积分/秒","enabled":true,"skuCode":"SEEDANCE20_FAST_480P","skuName":"Seedance2.0 Fast 480P","priority":11,"pricePerSecond":0.371628},{"match":{"resolution":"720P"},"price":4.00,"remark":"官方原价37元/百万token精确换算0.7992元/秒=79.92积分/秒","enabled":true,"skuCode":"SEEDANCE20_FAST_720P","skuName":"Seedance2.0 Fast 720P","priority":12,"pricePerSecond":0.7992}],"params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["480P","720P"],"required":true},{"code":"duration","name":"时长","type":"NUMBER","unit":"秒","required":true},{"code":"inputVideoCount","name":"输入视频段数","type":"NUMBER","required":false},{"code":"inputVideoSeconds","name":"输入视频总秒数","type":"NUMBER","required":false}],"preHold":true,"meterType":"PER_SECOND","chargeType":"VIDEO","settleRule":{"settleMode":"DIRECT_SETTLE","allowRefund":true,"usageSource":"REQUEST_PARAM","allowExtraCharge":false},"matchStrategy":"FIRST_HIT"}',
    remark = '豆包Seedance 2.0 Fast；官方原价token精确换算积分/秒；含/不含输入视频双档；仅480P/720P；参考图最多9、输入视频最多3段总时长≤15秒；音画同生；保持停用',
    update_time = NOW(),
    update_by = 'admin'
WHERE model_code = 'doubao-seedance-2.0-fast';
