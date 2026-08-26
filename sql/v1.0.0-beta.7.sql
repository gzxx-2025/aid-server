-- v1.0.0-beta.7：模型展示字段、Wan3.0 与 Agnes Video 2.5 模型及参考音频能力。
-- 结构升级保持幂等，模型默认停用并由后台按需启用。

-- 模型可单独配置展示 LOGO；为空时由模型列表接口回退所属服务商 LOGO。
SET @model_logo_url_column_exists := (
  SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
  WHERE `TABLE_SCHEMA`=DATABASE() AND `TABLE_NAME`='aid_ai_model' AND `COLUMN_NAME`='logo_url'
);
SET @model_logo_url_ddl := IF(
  @model_logo_url_column_exists=0,
  'ALTER TABLE `aid_ai_model` ADD COLUMN `logo_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''模型专属LOGO（为空回退服务商LOGO）'' AFTER `model_name`',
  'SELECT 1'
);
PREPARE model_logo_url_stmt FROM @model_logo_url_ddl;
EXECUTE model_logo_url_stmt;
DEALLOCATE PREPARE model_logo_url_stmt;

START TRANSACTION;

SET @wan30_provider_id := (
  SELECT `id` FROM `aid_ai_provider` WHERE `provider_code`='dashscope' AND `del_flag`='0' LIMIT 1
);

-- 两个模型独立保存能力矩阵，后续任一模型能力调整时不会误改另一模型。
SET @wan30_prime_capability := '{"requiresConfiguredBilling":true,"strictSceneRules":true,"maxPromptCharacters":20000,"sizeOptions":["480P","720P","1080P"],"defaultSize":"720P","aspectRatioOptions":["adaptive","16:9","4:3","1:1","3:4","9:16"],"defaultAspectRatio":"adaptive","durationOptions":[-1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30],"defaultDurationSeconds":5,"supportsAudio":true,"defaultAudio":true,"minReferenceImages":0,"maxReferenceImages":10,"referenceImageFormats":["jpeg","jpg","png","bmp","webp"],"referenceImageMaxFileSizeMb":20,"referenceImageMinDimensionPixels":240,"referenceImageMaxDimensionPixels":8000,"referenceImageMinAspectRatio":0.125,"referenceImageMaxAspectRatio":8.0,"supportsBase64Image":true,"supportsVideoInput":true,"minReferenceVideos":0,"maxReferenceVideos":5,"referenceVideoFormats":["mp4","mov"],"referenceVideoMinDurationSeconds":1,"referenceVideoMaxDurationSeconds":15,"referenceVideoMaxTotalDurationSeconds":15,"maxInputOutputVideoDurationSeconds":30,"referenceVideoMaxFileSizeMb":100,"referenceVideoMinDimensionPixels":240,"referenceVideoMaxDimensionPixels":4096,"referenceVideoMinAspectRatio":0.125,"referenceVideoMaxAspectRatio":8.0,"supportsReferenceAudio":true,"minReferenceAudios":0,"maxReferenceAudios":5,"referenceAudioFormats":["wav","mp3"],"referenceAudioMinDurationSeconds":1,"referenceAudioMaxDurationSeconds":15,"referenceAudioMaxTotalDurationSeconds":15,"referenceAudioMaxFileSizeMb":15,"maxReferenceMaterials":20,"sceneRules":{"textToVideo":{"allowedInputs":["text"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"imageToVideo":{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"startEndToVideo":{"requiredInputs":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"referenceToVideo":{"requiredAnyOf":["image","video","audio"],"allowedInputs":["text","image","video","audio"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true}}}';
SET @wan30_standard_capability := '{"requiresConfiguredBilling":true,"strictSceneRules":true,"maxPromptCharacters":20000,"sizeOptions":["480P","720P","1080P"],"defaultSize":"720P","aspectRatioOptions":["adaptive","16:9","4:3","1:1","3:4","9:16"],"defaultAspectRatio":"adaptive","durationOptions":[-1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30],"defaultDurationSeconds":5,"supportsAudio":true,"defaultAudio":true,"minReferenceImages":0,"maxReferenceImages":10,"referenceImageFormats":["jpeg","jpg","png","bmp","webp"],"referenceImageMaxFileSizeMb":20,"referenceImageMinDimensionPixels":240,"referenceImageMaxDimensionPixels":8000,"referenceImageMinAspectRatio":0.125,"referenceImageMaxAspectRatio":8.0,"supportsBase64Image":true,"supportsVideoInput":true,"minReferenceVideos":0,"maxReferenceVideos":5,"referenceVideoFormats":["mp4","mov"],"referenceVideoMinDurationSeconds":1,"referenceVideoMaxDurationSeconds":15,"referenceVideoMaxTotalDurationSeconds":15,"maxInputOutputVideoDurationSeconds":30,"referenceVideoMaxFileSizeMb":100,"referenceVideoMinDimensionPixels":240,"referenceVideoMaxDimensionPixels":4096,"referenceVideoMinAspectRatio":0.125,"referenceVideoMaxAspectRatio":8.0,"supportsReferenceAudio":true,"minReferenceAudios":0,"maxReferenceAudios":5,"referenceAudioFormats":["wav","mp3"],"referenceAudioMinDurationSeconds":1,"referenceAudioMaxDurationSeconds":15,"referenceAudioMaxTotalDurationSeconds":15,"referenceAudioMaxFileSizeMb":15,"maxReferenceMaterials":20,"sceneRules":{"textToVideo":{"allowedInputs":["text"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"imageToVideo":{"requiredInputs":["firstFrame"],"allowedInputs":["text","firstFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"startEndToVideo":{"requiredInputs":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"referenceToVideo":{"requiredAnyOf":["image","video","audio"],"allowedInputs":["text","image","video","audio"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true}}}';

SET @wan30_prime_billing_rule := '{"mode":"SKU","meterType":"PER_SECOND","chargeType":"VIDEO","preHold":true,"matchStrategy":"FIRST_HIT","params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["480P","720P","1080P"],"required":true},{"code":"duration","name":"输出时长","type":"NUMBER","unit":"秒","required":true},{"code":"inputVideoCount","name":"输入视频数","type":"INT","required":false},{"code":"inputVideoSeconds","name":"输入视频总时长","type":"NUMBER","unit":"秒","required":false}],"skus":[{"skuCode":"WAN30_PRIME_480P","skuName":"Wan3.0 Prime 480P","priority":10,"enabled":true,"match":{"resolution":"480P"},"price":2.25,"pricePerSecond":0.45,"inputPricing":{"video":{"unitPrice":0.45,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_PRIME_720P","skuName":"Wan3.0 Prime 720P","priority":20,"enabled":true,"match":{"resolution":"720P"},"price":4.5,"pricePerSecond":0.9,"inputPricing":{"video":{"unitPrice":0.9,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_PRIME_1080P","skuName":"Wan3.0 Prime 1080P","priority":30,"enabled":true,"match":{"resolution":"1080P"},"price":9.0,"pricePerSecond":1.8,"inputPricing":{"video":{"unitPrice":1.8,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_PRIME_FALLBACK","skuName":"Wan3.0 Prime 兜底","priority":999,"enabled":true,"match":{},"price":9.0,"pricePerSecond":1.8,"inputPricing":{"video":{"unitPrice":1.8,"maxSeconds":15,"maxCount":5}}}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","allowRefund":true,"allowExtraCharge":false}}';
SET @wan30_standard_billing_rule := '{"mode":"SKU","meterType":"PER_SECOND","chargeType":"VIDEO","preHold":true,"matchStrategy":"FIRST_HIT","params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["480P","720P","1080P"],"required":true},{"code":"duration","name":"输出时长","type":"NUMBER","unit":"秒","required":true},{"code":"inputVideoCount","name":"输入视频数","type":"INT","required":false},{"code":"inputVideoSeconds","name":"输入视频总时长","type":"NUMBER","unit":"秒","required":false}],"skus":[{"skuCode":"WAN30_STANDARD_480P","skuName":"Wan3.0 480P","priority":10,"enabled":true,"match":{"resolution":"480P"},"price":1.5,"pricePerSecond":0.3,"inputPricing":{"video":{"unitPrice":0.3,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_STANDARD_720P","skuName":"Wan3.0 720P","priority":20,"enabled":true,"match":{"resolution":"720P"},"price":3.0,"pricePerSecond":0.6,"inputPricing":{"video":{"unitPrice":0.6,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_STANDARD_1080P","skuName":"Wan3.0 1080P","priority":30,"enabled":true,"match":{"resolution":"1080P"},"price":6.0,"pricePerSecond":1.2,"inputPricing":{"video":{"unitPrice":1.2,"maxSeconds":15,"maxCount":5}}},{"skuCode":"WAN30_STANDARD_FALLBACK","skuName":"Wan3.0 兜底","priority":999,"enabled":true,"match":{},"price":6.0,"pricePerSecond":1.2,"inputPricing":{"video":{"unitPrice":1.2,"maxSeconds":15,"maxCount":5}}}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","allowRefund":true,"allowExtraCharge":false}}';

INSERT INTO `aid_ai_model` (
  `provider_id`,`model_code`,`real_model_code`,`model_name`,`model_type`,`generate_mode`,`cost_credits`,`billing_multiplier`,
  `api_suffix`,`protocol`,`priority`,`status`,`del_flag`,`create_time`,`create_by`,`update_time`,`update_by`,`remark`,
  `billing_mode`,`billing_rule_json`,`billing_version`,`schedule_strategy_json`,`supports_text_input`,`supports_system_prompt`,
  `supports_image_input`,`supports_multi_image_input`,`max_output_count`,`default_output_count`,`supports_aspect_ratio`,
  `supports_size_preset`,`supports_duration`,`supports_first_frame`,`supports_last_frame`,`default_size_code`,
  `default_aspect_ratio`,`default_duration_seconds`,`capability_json`,`capability_inited`,`official_price_url`,`is_free`
) VALUES
(@wan30_provider_id,'wan3.0-video-prime','wan3.0-video-prime','万相 Wan3.0 Video Prime','video','image_to_video',0,1,
 '/api/v1/services/aigc/video-generation/video-synthesis','dashscope-video',140,'1','0',NOW(),'system',NOW(),'system',
 '万相3.0优速版；文本、首帧、首尾帧与图/视频/音频参考统一模型；官方北京原价；默认停用',
 'SKU',@wan30_prime_billing_rule,7,'{"maxConcurrency":1}',1,0,1,1,1,1,1,1,1,1,1,'720P','adaptive',5,
 @wan30_prime_capability,1,'https://help.aliyun.com/zh/model-studio/model-pricing',0),
(@wan30_provider_id,'wan3.0-video','wan3.0-video','万相 Wan3.0 Video','video','image_to_video',0,1,
 '/api/v1/services/aigc/video-generation/video-synthesis','dashscope-video',139,'1','0',NOW(),'system',NOW(),'system',
 '万相3.0全能版；文本、首帧、首尾帧与图/视频/音频参考统一模型；官方北京原价；默认停用',
 'SKU',@wan30_standard_billing_rule,7,'{"maxConcurrency":1}',1,0,1,1,1,1,1,1,1,1,1,'720P','adaptive',5,
 @wan30_standard_capability,1,'https://help.aliyun.com/zh/model-studio/model-pricing',0)
ON DUPLICATE KEY UPDATE
  `provider_id`=VALUES(`provider_id`),`real_model_code`=VALUES(`real_model_code`),`model_name`=VALUES(`model_name`),
  `model_type`=VALUES(`model_type`),`generate_mode`=VALUES(`generate_mode`),
  `api_suffix`=CASE WHEN NULLIF(TRIM(`api_suffix`),'') IS NULL THEN VALUES(`api_suffix`) ELSE `api_suffix` END,
  `protocol`=VALUES(`protocol`),`capability_json`=VALUES(`capability_json`),`capability_inited`=1,
  `supports_text_input`=VALUES(`supports_text_input`),`supports_system_prompt`=VALUES(`supports_system_prompt`),
  `supports_image_input`=VALUES(`supports_image_input`),`supports_multi_image_input`=VALUES(`supports_multi_image_input`),
  `supports_aspect_ratio`=VALUES(`supports_aspect_ratio`),`supports_size_preset`=VALUES(`supports_size_preset`),
  `supports_duration`=VALUES(`supports_duration`),`supports_first_frame`=VALUES(`supports_first_frame`),
  `supports_last_frame`=VALUES(`supports_last_frame`),`default_size_code`=VALUES(`default_size_code`),
  `default_aspect_ratio`=VALUES(`default_aspect_ratio`),`default_duration_seconds`=VALUES(`default_duration_seconds`),
  `official_price_url`=VALUES(`official_price_url`),`del_flag`='0',`update_time`=NOW(),`update_by`='system';

-- 兼容已经执行过早期脚本的数据库：模型唯一编码迁回现有阿里百炼后，直接删除无引用的重复供应商。
DELETE p FROM `aid_ai_provider` p
LEFT JOIN `aid_ai_model` m ON m.`provider_id`=p.`id` AND m.`del_flag`='0'
WHERE p.`provider_code`='dashscope-wan3' AND m.`id` IS NULL;

-- 仅为空或无有效 SKU 的模型回填官方规则，保留后台已维护的倍率与有效计费规则。
UPDATE `aid_ai_model`
SET `billing_mode`='SKU',
    `billing_rule_json`=CASE
      WHEN `model_code`='wan3.0-video-prime' THEN @wan30_prime_billing_rule
      ELSE @wan30_standard_billing_rule
    END,
    `billing_version`=7,`update_time`=NOW(),`update_by`='system'
WHERE `model_code` IN ('wan3.0-video-prime','wan3.0-video')
  AND (`billing_rule_json` IS NULL OR JSON_VALID(`billing_rule_json`)=0
       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(`billing_rule_json`,'$.skus')),0)=0);

-- 两个真实模型编码各保持一条模型记录；按能力加入全部视频功能池，模型仍保持停用。
SET @wan30_model_id := (SELECT `id` FROM `aid_ai_model` WHERE `model_code`='wan3.0-video-prime' LIMIT 1);
UPDATE `aid_ai_model_func_config`
SET `model_ids`=JSON_ARRAY_APPEND(COALESCE(`model_ids`,JSON_ARRAY()),'$',@wan30_model_id)
WHERE `func_code` IN ('main_storyboard_video_image','main_storyboard_video','main_storyboard_video_multi_pro',
                      'main_storyboard_video_edge','main_storyboard_video_grid')
  AND @wan30_model_id IS NOT NULL
  AND NOT JSON_CONTAINS(COALESCE(`model_ids`,JSON_ARRAY()),CAST(CONCAT(@wan30_model_id) AS JSON));

SET @wan30_model_id := (SELECT `id` FROM `aid_ai_model` WHERE `model_code`='wan3.0-video' LIMIT 1);
UPDATE `aid_ai_model_func_config`
SET `model_ids`=JSON_ARRAY_APPEND(COALESCE(`model_ids`,JSON_ARRAY()),'$',@wan30_model_id)
WHERE `func_code` IN ('main_storyboard_video_image','main_storyboard_video','main_storyboard_video_multi_pro',
                      'main_storyboard_video_edge','main_storyboard_video_grid')
  AND @wan30_model_id IS NOT NULL
  AND NOT JSON_CONTAINS(COALESCE(`model_ids`,JSON_ARRAY()),CAST(CONCAT(@wan30_model_id) AS JSON));

COMMIT;

-- Agnes Video 2.5 与 Flash：复用 Agnes 服务商、统一异步视频任务与模型能力计费。
START TRANSACTION;

INSERT INTO `aid_ai_provider` (
  `provider_name`,`provider_code`,`logo_url`,`base_url`,`api_key`,`api_secret`,`auth_header`,`auth_prefix`,
  `api_key_apply_url`,`official_doc_url`,`official_price_url`,`task_query_suffix`,`status`,`del_flag`,
  `create_time`,`create_by`,`update_time`,`update_by`,`remark`,`supports_callback`,`schedule_strategy_json`
) VALUES (
  'Agnes AI','agnes',NULL,'https://apihub.agnes-ai.com','','','Authorization','Bearer ',
  'https://apihub.agnes-ai.com','https://wiki.agnes-ai.cn','https://wiki.agnes-ai.cn',
  '/agnesapi?video_id=%s','1','0',NOW(),'system',NOW(),'system',
  'Agnes 文本、图片与异步视频服务；Video 2.5 查询由服务端追加 model_name',0,
  '{"dispatchMode":"POLL_ONLY","supportsCallback":false,"firstPollDelaySeconds":2,"baseIntervalSeconds":2,"maxIntervalSeconds":30,"backoffFactor":1.5,"maxRetryCount":300,"maxLifeSeconds":7200,"progressTimeoutSeconds":1200,"maxConcurrency":1}'
) ON DUPLICATE KEY UPDATE
  `provider_name`=VALUES(`provider_name`),
  `base_url`=CASE
    WHEN NULLIF(TRIM(`base_url`),'') IS NULL OR TRIM(`base_url`)='https://api.agnes-ai.cn' THEN VALUES(`base_url`)
    ELSE `base_url`
  END,
  `auth_header`=VALUES(`auth_header`),`auth_prefix`=VALUES(`auth_prefix`),
  `task_query_suffix`=CASE
    WHEN NULLIF(TRIM(`task_query_suffix`),'') IS NULL OR `task_query_suffix`='/v1/videos/%s'
      THEN VALUES(`task_query_suffix`)
    ELSE `task_query_suffix`
  END,
  `schedule_strategy_json`=CASE
    WHEN NULLIF(TRIM(`schedule_strategy_json`),'') IS NULL OR JSON_VALID(`schedule_strategy_json`)=0
      THEN VALUES(`schedule_strategy_json`)
    ELSE `schedule_strategy_json`
  END,
  `remark`=CASE
    WHEN NULLIF(TRIM(`remark`),'') IS NULL OR `remark` LIKE 'Agnes AI（Sapiens AI）%'
      THEN VALUES(`remark`)
    ELSE `remark`
  END,
  `del_flag`='0',`update_time`=NOW(),`update_by`='system';

SET @agnes25_provider_id := (
  SELECT `id` FROM `aid_ai_provider` WHERE `provider_code`='agnes' AND `del_flag`='0' LIMIT 1
);

-- 标准版未公开参考素材数量与音频格式上限：-1 表示数量不限，* 表示不虚构格式白名单；Flash 的 5 图/0 视频为明确硬限制。
SET @agnes25_capability := '{"requiresConfiguredBilling":true,"strictSceneRules":true,"sizeOptions":["720P","960P","2K"],"defaultSize":"720P","durationOptions":[4,5,6,7,8,9,10,11,12],"defaultDurationSeconds":5,"aspectRatioOptions":["21:9","16:9","4:3","1:1","3:4","9:16"],"defaultAspectRatio":"16:9","supportsAudio":true,"defaultAudio":true,"minReferenceImages":0,"maxReferenceImages":-1,"supportsVideoInput":true,"minReferenceVideos":0,"maxReferenceVideos":-1,"supportsReferenceAudio":true,"minReferenceAudios":0,"maxReferenceAudios":-1,"referenceAudioFormats":["*"],"referenceAudioMinDurationSeconds":0,"referenceAudioMaxDurationSeconds":0,"referenceAudioMaxTotalDurationSeconds":0,"sceneRules":{"textToVideo":{"allowedInputs":["text"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"imageToVideo":{"requiredAnyOf":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"startEndToVideo":{"requiredAnyOf":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"referenceToVideo":{"requiredAnyOf":["image","video","audio"],"allowedInputs":["text","image","video","audio"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true}}}';
SET @agnes25_flash_capability := '{"requiresConfiguredBilling":true,"strictSceneRules":true,"sizeOptions":["720P"],"defaultSize":"720P","durationOptions":[4,5,6,7,8,9,10,11,12],"defaultDurationSeconds":5,"aspectRatioOptions":["21:9","16:9","4:3","1:1","3:4","9:16"],"defaultAspectRatio":"16:9","supportsAudio":true,"defaultAudio":true,"minReferenceImages":0,"maxReferenceImages":5,"supportsVideoInput":false,"minReferenceVideos":0,"maxReferenceVideos":0,"supportsReferenceAudio":true,"minReferenceAudios":0,"maxReferenceAudios":-1,"referenceAudioFormats":["*"],"referenceAudioMinDurationSeconds":0,"referenceAudioMaxDurationSeconds":0,"referenceAudioMaxTotalDurationSeconds":0,"sceneRules":{"textToVideo":{"allowedInputs":["text"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"imageToVideo":{"requiredAnyOf":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"startEndToVideo":{"requiredAnyOf":["firstFrame","lastFrame"],"allowedInputs":["text","firstFrame","lastFrame"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true},"referenceToVideo":{"requiredAnyOf":["image","audio"],"allowedInputs":["text","image","audio"],"supportsDuration":true,"supportsSizePreset":true,"supportsAspectRatio":true}}}';

-- 文档美元价按仓库统一的 7 元/美元静态口径折算；前 5 张输入图片免费，第 6 张起 0.035 元/张。
SET @agnes25_billing_rule := '{"mode":"SKU","meterType":"PER_SECOND","chargeType":"VIDEO","preHold":true,"matchStrategy":"FIRST_HIT","params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["720P","960P","2K"],"required":true},{"code":"duration","name":"输出时长","type":"NUMBER","unit":"秒","required":true},{"code":"referenceImageCount","name":"输入图片数","type":"INT","required":false}],"skus":[{"skuCode":"AGNES_VIDEO25_720P","skuName":"Agnes Video 2.5 720P","priority":10,"enabled":true,"match":{"resolution":"720P"},"price":0.875,"pricePerSecond":0.175,"inputPricing":{"image":{"unitPrice":0.035,"freeCount":5}}},{"skuCode":"AGNES_VIDEO25_960P","skuName":"Agnes Video 2.5 960P","priority":20,"enabled":true,"match":{"resolution":"960P"},"price":1.4,"pricePerSecond":0.28,"inputPricing":{"image":{"unitPrice":0.035,"freeCount":5}}},{"skuCode":"AGNES_VIDEO25_2K","skuName":"Agnes Video 2.5 2K","priority":30,"enabled":true,"match":{"resolution":"2K"},"price":1.925,"pricePerSecond":0.385,"inputPricing":{"image":{"unitPrice":0.035,"freeCount":5}}},{"skuCode":"AGNES_VIDEO25_FALLBACK","skuName":"Agnes Video 2.5 兜底","priority":999,"enabled":true,"match":{},"price":1.925,"pricePerSecond":0.385,"inputPricing":{"image":{"unitPrice":0.035,"freeCount":5}}}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","allowRefund":true,"allowExtraCharge":false}}';
SET @agnes25_flash_billing_rule := '{"mode":"SKU","meterType":"PER_SECOND","chargeType":"VIDEO","preHold":true,"matchStrategy":"FIRST_HIT","params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["720P"],"required":true},{"code":"duration","name":"输出时长","type":"NUMBER","unit":"秒","required":true}],"skus":[{"skuCode":"AGNES_VIDEO25_FLASH_720P","skuName":"Agnes Video 2.5 Flash 720P","priority":10,"enabled":true,"match":{"resolution":"720P"},"price":0.875,"pricePerSecond":0.175},{"skuCode":"AGNES_VIDEO25_FLASH_FALLBACK","skuName":"Agnes Video 2.5 Flash 兜底","priority":999,"enabled":true,"match":{},"price":0.875,"pricePerSecond":0.175}],"settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","allowRefund":true,"allowExtraCharge":false}}';

INSERT INTO `aid_ai_model` (
  `provider_id`,`model_code`,`real_model_code`,`model_name`,`model_type`,`generate_mode`,`cost_credits`,`billing_multiplier`,
  `api_suffix`,`protocol`,`priority`,`status`,`del_flag`,`create_time`,`create_by`,`update_time`,`update_by`,`remark`,
  `billing_mode`,`billing_rule_json`,`billing_version`,`schedule_strategy_json`,`supports_text_input`,`supports_system_prompt`,
  `supports_image_input`,`supports_multi_image_input`,`max_output_count`,`default_output_count`,`supports_aspect_ratio`,
  `supports_size_preset`,`supports_duration`,`supports_first_frame`,`supports_last_frame`,`default_size_code`,
  `default_aspect_ratio`,`default_duration_seconds`,`capability_json`,`capability_inited`,`official_price_url`,`is_free`
) VALUES
(@agnes25_provider_id,'agnes-video-2.5','agnes-video-2.5','Agnes Video 2.5','video','image_to_video',0,1,
 '/v1/videos','agnes-video',131,'1','0',NOW(),'system',NOW(),'system',
 'Agnes Video 2.5；文本、首尾帧及图片/音频/视频参考；4-12秒；美元价按7元/美元折算；默认停用',
 'SKU',@agnes25_billing_rule,7,'{"maxConcurrency":1}',1,0,1,1,1,1,1,1,1,1,1,'720P','16:9',5,
 @agnes25_capability,1,'https://wiki.agnes-ai.cn',0),
(@agnes25_provider_id,'agnes-video-2.5-flash','agnes-video-2.5-flash','Agnes Video 2.5 Flash','video','image_to_video',0,1,
 '/v1/videos','agnes-video',130,'1','0',NOW(),'system',NOW(),'system',
 'Agnes Video 2.5 Flash；720P、4-12秒、参考图最多5张、禁止参考视频；当前限时免费；默认停用',
 'SKU',@agnes25_flash_billing_rule,7,'{"maxConcurrency":1}',1,0,1,1,1,1,1,1,1,1,1,'720P','16:9',5,
 @agnes25_flash_capability,1,'https://wiki.agnes-ai.cn',1)
ON DUPLICATE KEY UPDATE
  `provider_id`=VALUES(`provider_id`),`real_model_code`=VALUES(`real_model_code`),`model_name`=VALUES(`model_name`),
  `model_type`=VALUES(`model_type`),`generate_mode`=VALUES(`generate_mode`),
  `api_suffix`=CASE WHEN NULLIF(TRIM(`api_suffix`),'') IS NULL THEN VALUES(`api_suffix`) ELSE `api_suffix` END,
  `protocol`=VALUES(`protocol`),`capability_json`=VALUES(`capability_json`),`capability_inited`=1,
  `supports_text_input`=VALUES(`supports_text_input`),`supports_system_prompt`=VALUES(`supports_system_prompt`),
  `supports_image_input`=VALUES(`supports_image_input`),`supports_multi_image_input`=VALUES(`supports_multi_image_input`),
  `max_output_count`=VALUES(`max_output_count`),`default_output_count`=VALUES(`default_output_count`),
  `supports_aspect_ratio`=VALUES(`supports_aspect_ratio`),`supports_size_preset`=VALUES(`supports_size_preset`),
  `supports_duration`=VALUES(`supports_duration`),`supports_first_frame`=VALUES(`supports_first_frame`),
  `supports_last_frame`=VALUES(`supports_last_frame`),`default_size_code`=VALUES(`default_size_code`),
  `default_aspect_ratio`=VALUES(`default_aspect_ratio`),`default_duration_seconds`=VALUES(`default_duration_seconds`),
  `official_price_url`=VALUES(`official_price_url`),`del_flag`='0',`update_time`=NOW(),`update_by`='system';

UPDATE `aid_ai_model`
SET `billing_mode`='SKU',
    `billing_rule_json`=CASE
      WHEN `model_code`='agnes-video-2.5' THEN @agnes25_billing_rule
      ELSE @agnes25_flash_billing_rule
    END,
    `billing_version`=7,`update_time`=NOW(),`update_by`='system'
WHERE `model_code` IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND (`billing_rule_json` IS NULL OR JSON_VALID(`billing_rule_json`)=0
       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(`billing_rule_json`,'$.skus')),0)=0);

SET @agnes25_model_id := (SELECT `id` FROM `aid_ai_model` WHERE `model_code`='agnes-video-2.5' LIMIT 1);
UPDATE `aid_ai_model_func_config`
SET `model_ids`=JSON_ARRAY_APPEND(COALESCE(`model_ids`,JSON_ARRAY()),'$',@agnes25_model_id)
WHERE `func_code` IN ('main_storyboard_video_image','main_storyboard_video','main_storyboard_video_multi_pro',
                      'main_storyboard_video_edge','main_storyboard_video_grid')
  AND @agnes25_model_id IS NOT NULL
  AND NOT JSON_CONTAINS(COALESCE(`model_ids`,JSON_ARRAY()),CAST(CONCAT(@agnes25_model_id) AS JSON));

SET @agnes25_model_id := (SELECT `id` FROM `aid_ai_model` WHERE `model_code`='agnes-video-2.5-flash' LIMIT 1);
UPDATE `aid_ai_model_func_config`
SET `model_ids`=JSON_ARRAY_APPEND(COALESCE(`model_ids`,JSON_ARRAY()),'$',@agnes25_model_id)
WHERE `func_code` IN ('main_storyboard_video_image','main_storyboard_video','main_storyboard_video_multi_pro',
                      'main_storyboard_video_edge','main_storyboard_video_grid')
  AND @agnes25_model_id IS NOT NULL
  AND NOT JSON_CONTAINS(COALESCE(`model_ids`,JSON_ARRAY()),CAST(CONCAT(@agnes25_model_id) AS JSON));

COMMIT;

-- 修复既有 Agnes 2.5 参考音频能力：厂商未公布数量、时长和格式白名单时，
-- -1 表示未公布数量上限，0 表示对应时长限制未公布，* 表示未公布格式白名单。
START TRANSACTION;

UPDATE `aid_ai_model` m
JOIN `aid_ai_provider` p ON p.`id`=m.`provider_id`
SET m.`capability_json`=JSON_SET(
      CASE
        WHEN JSON_VALID(m.`capability_json`)=1 THEN m.`capability_json`
        ELSE JSON_OBJECT()
      END,
      '$.supportsAudio',CAST('true' AS JSON),
      '$.supportsReferenceAudio',CAST('true' AS JSON),
      '$.minReferenceAudios',0,
      '$.maxReferenceAudios',-1,
      '$.referenceAudioFormats',JSON_ARRAY('*'),
      '$.referenceAudioMinDurationSeconds',0,
      '$.referenceAudioMaxDurationSeconds',0,
      '$.referenceAudioMaxTotalDurationSeconds',0
    ),
    m.`capability_inited`=1,
    m.`update_time`=NOW(),
    m.`update_by`='system'
WHERE p.`provider_code`='agnes'
  AND p.`del_flag`='0'
  AND m.`model_code` IN ('agnes-video-2.5','agnes-video-2.5-flash')
  AND m.`del_flag`='0';

COMMIT;
