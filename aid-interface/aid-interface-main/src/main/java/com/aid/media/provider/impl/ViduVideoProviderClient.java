package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import org.apache.commons.lang3.StringUtils;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.constant.HttpConstants;
import com.aid.media.constants.ViduConstants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ModelIoDump; // 【测试日志·上线必删】入参/出参落盘工具
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.ViduCallbackSupport;
import com.aid.media.provider.ViduStatusMapper;
import com.aid.billing.util.ResolutionUtil;
import com.aid.media.provider.VideoProviderClient;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ViduVideoProviderClient implements VideoProviderClient {

    @Override
    public String protocol() {
        // 返回协议名称。
        return ViduConstants.PROTOCOL_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // Vidu 视频：按 provider_code 精确归属
        return providerCode != null
                && ViduConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        // 主体调用形态（options.subjects 非空）：@主体名 是官方引用语义，用保留主体引用的清洗；其余场景全量清洗
        if (hasOptionKey(request, ViduConstants.OPTIONS_SUBJECTS)) {
            ReferencePromptSanitizer.sanitizeInPlacePreservingSubjectRefs(request);
        } else {
            ReferencePromptSanitizer.sanitizeInPlace(request);
        }
        //    推断不到再回退按入参/options 推断，保证与 DB 驱动的端点配置一致。
        ViduScenario scenario = resolveScenarioFromSuffix(modelConfig.getApiSuffix());
        if (scenario == null) {
            scenario = resolveScenario(request);
        }
        String submitUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> body = buildSubmitBody(request, modelConfig, scenario);
        String bodyJson = JSONUtil.toJsonStr(body);
        ModelIoDump.req(submitUrl, bodyJson); // 【测试日志·上线必删】记录下发上游入参
        String raw = doPost(submitUrl, modelConfig.getApiKey(), bodyJson);
        ModelIoDump.resp(submitUrl, raw); // 【测试日志·上线必删】记录上游出参
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskId = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_TASK_ID, ViduConstants.JSON_PATH_DATA_TASK_ID, ViduConstants.JSON_PATH_DATA_ID,
            ViduConstants.JSON_ID, ViduConstants.JSON_PATH_OUTPUT_TASK_ID);
        String directUrl = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_VIDEO_URL, ViduConstants.JSON_PATH_DATA_VIDEO_URL, ViduConstants.JSON_PATH_DATA_URL,
            ViduConstants.JSON_PATH_OUTPUT_VIDEO_URL,
            ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL, ViduConstants.JSON_PATH_CREATIONS_0_URL);
        return ProviderSubmitResult.builder()
            .providerTaskId(taskId)
            .directUrl(directUrl)
            .rawResponse(raw)
            .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        String queryUrl = buildTaskUrl(modelConfig.getBaseUrl(), modelConfig.getTaskQuerySuffix(), providerTaskId);
        String raw = doGet(queryUrl, modelConfig.getApiKey());
        ModelIoDump.resp(queryUrl, raw); // 【测试日志·上线必删】记录上游出参（轮询查询）
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String status = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_STATUS, ViduConstants.JSON_STATE, ViduConstants.JSON_TASK_STATUS,
            ViduConstants.JSON_PATH_DATA_STATUS, ViduConstants.JSON_PATH_DATA_STATE, ViduConstants.JSON_PATH_DATA_TASK_STATUS,
            ViduConstants.JSON_PATH_OUTPUT_TASK_STATUS);
        String normalized = ViduStatusMapper.normalizeStatus(status);
        String url = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL, ViduConstants.JSON_PATH_CREATIONS_0_URL,
            ViduConstants.JSON_VIDEO_URL, ViduConstants.JSON_PATH_DATA_VIDEO_URL, ViduConstants.JSON_PATH_DATA_URL,
            ViduConstants.JSON_PATH_OUTPUT_VIDEO_URL);
        if (StrUtil.isBlank(url)) {
            url = ProviderResponseHelper.findFirstUrl(root);
        }
        String errCode = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_ERR_CODE, ViduConstants.JSON_PATH_DATA_ERR_CODE);
        String beforeClassify = normalized;
        normalized = ViduStatusMapper.applyErrorCodeClassification(normalized, errCode);
        if (ViduConstants.TASK_STATUS_PROCESSING.equals(normalized)
            && ViduConstants.TASK_STATUS_FAILED.equals(beforeClassify)) {
            log.warn("vidu video query 命中可重试错误码 {}，保持 PROCESSING 由轮询兜底", errCode);
        }
        String error = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_ERROR_MESSAGE, ViduConstants.JSON_PATH_ERROR_MSG, ViduConstants.JSON_ERROR,
            ViduConstants.JSON_MESSAGE, ViduConstants.JSON_PATH_DATA_ERROR, ViduConstants.JSON_PATH_DATA_MESSAGE);
        if (StrUtil.isNotBlank(errCode)) {
            error = StrUtil.isBlank(error) ? errCode : (errCode + ":" + error);
        }
        return ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(url)
            .errorMessage(error)
            .rawResponse(raw)
            .build();
    }

    /**
     * 从 api_suffix 路径推断 Vidu 视频场景（端点路由原则：body 形态由后台模型行 api_suffix 决定）。
     * path 含 img2video→图生体、start-end2video→首尾帧、reference2video→参考体、
     * multiframe→多帧、text2video→文生体；无法推断时返回 null，交由入参推断兜底。
     */
    private ViduScenario resolveScenarioFromSuffix(String apiSuffix) {
        if (StrUtil.isBlank(apiSuffix)) {
            return null;
        }
        String path = apiSuffix.trim().toLowerCase();
        if (path.contains("lip-sync") || path.contains("lip_sync") || path.contains("lipsync")) {
            return ViduScenario.LIP_SYNC;
        }
        if (path.contains("start-end2video") || path.contains("start_end2video")) {
            return ViduScenario.START_END_TO_VIDEO;
        }
        if (path.contains("img2video") || path.contains("image2video")) {
            return ViduScenario.IMAGE_TO_VIDEO;
        }
        if (path.contains("reference2video")) {
            return ViduScenario.REFERENCE_TO_VIDEO;
        }
        if (path.contains("multiframe")) {
            return ViduScenario.MULTI_FRAME;
        }
        if (path.contains("text2video")) {
            return ViduScenario.TEXT_TO_VIDEO;
        }
        return null;
    }

    private ViduScenario resolveScenario(MediaVideoGenerateRequest request) {
        String explicit = readScenarioFromOptions(request);
        if (StrUtil.isNotBlank(explicit)) {
            return ViduScenario.fromValue(explicit);
        }
        if (hasOptionKey(request, ViduConstants.OPTIONS_IMAGE_SETTINGS) || hasOptionKey(request, ViduConstants.OPTIONS_START_IMAGE)) {
            return ViduScenario.MULTI_FRAME;
        }
        if (hasOptionKey(request, ViduConstants.OPTIONS_START_END)
            || hasOptionKey(request, ViduConstants.OPTIONS_START_END_MODE)
            || hasOptionKey(request, ViduConstants.OPTIONS_END_IMAGE_URL_CAMEL)
            || hasOptionKey(request, ViduConstants.OPTIONS_END_IMAGE_URL_SNAKE)) {
            return ViduScenario.START_END_TO_VIDEO;
        }
        if (hasOptionKey(request, ViduConstants.OPTIONS_SUBJECTS) || hasOptionKey(request, ViduConstants.OPTIONS_VIDEOS)) {
            return ViduScenario.REFERENCE_TO_VIDEO;
        }
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            return ViduScenario.IMAGE_TO_VIDEO;
        }
        return ViduScenario.TEXT_TO_VIDEO;
    }

    /**
     * 构建 API 请求 URL：base_url + api_suffix（路径全部来自数据库配置）
     */
    private String buildApiUrl(String baseUrl, String apiSuffix) {
        if (StrUtil.isBlank(baseUrl)) {
            log.error("vidu video model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(apiSuffix)) {
            log.error("vidu video model apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        return trimSlash(baseUrl.trim()) + apiSuffix;
    }

    private Map<String, Object> buildSubmitBody(MediaVideoGenerateRequest request,
                                                AiModelConfigVo modelConfig,
                                                ViduScenario scenario) {
        // 对口型(lip-sync)请求体形态与生成类视频完全不同（无 model/duration/aspect_ratio/resolution/音画字段），
        // 单独构建，避免把无关字段下发给上游被拒。
        if (scenario == ViduScenario.LIP_SYNC) {
            return buildLipSyncBody(request, modelConfig);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String modelName = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(
                modelConfig, request.getModelName());
        if (StrUtil.isNotBlank(modelName)) {
            body.put(ViduConstants.JSON_MODEL, modelName);
        }
        if (StrUtil.isNotBlank(request.getPrompt())) {
            body.put(ViduConstants.JSON_PROMPT, request.getPrompt());
        }
        if (request.getDurationSeconds() != null) {
            body.put(ViduConstants.JSON_DURATION, request.getDurationSeconds());
        }
        if (StrUtil.isNotBlank(request.getAspectRatio())) {
            body.put(ViduConstants.JSON_ASPECT_RATIO, request.getAspectRatio());
        }
        String resolution = readOptionAsString(request, ViduConstants.OPTIONS_RESOLUTION);
        if (StrUtil.isNotBlank(resolution)) {
            String tier = ResolutionUtil.parseTier(resolution);
            body.put(ViduConstants.JSON_RESOLUTION, StrUtil.isNotBlank(tier) ? tier : resolution.trim());
        }
        applyAudioFields(body, request, modelConfig);
        applyCallbackUrl(body, modelConfig);
        // 先合并 options 官方字段（subjects / images 等装配策略产出），再做场景兜底补缺：
        // 兜底以「body 已有官方图片字段」为跳过条件，顺序颠倒会在主体调用（subjects）之外
        // 多发一个官方参数表不存在的顶层 images，Vidu 对多余字段会拒单
        mergeTopLevelOptions(body, request);
        applyScenarioSpecificFields(body, request, scenario);
        // Base64 传图开关：官方 images/start_image/key_image 均支持 data URI，启用时统一转内联下发
        applyBase64ImagesIfEnabled(body, modelConfig);
        // 最后按官方各端点的字段白名单裁剪：Vidu 对多余字段会返回 FieldUnwanted 拒单
        pruneUnsupportedFields(body, scenario);
        return body;
    }

    /**
     * 模型开启 Base64 传图时，把请求体里的图片字段（images / start_image / image_settings[].key_image）
     * 从 URL 下载转 data URI 内联下发；subjects 结构保持 URL 不动（官方主体引用建议 URL）。
     */
    @SuppressWarnings("unchecked")
    private void applyBase64ImagesIfEnabled(Map<String, Object> body, AiModelConfigVo modelConfig) {
        if (!com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
            return;
        }
        Object images = body.get(ViduConstants.JSON_IMAGES);
        if (images instanceof List<?> list && !list.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    urls.add(String.valueOf(item));
                }
            }
            body.put(ViduConstants.JSON_IMAGES,
                    com.aid.media.provider.ReferenceImageBase64Support.toDataUris(urls));
            log.info("Vidu 视频图片字段按 base64 内联下发, images={}", urls.size());
        }
        Object startImage = body.get(ViduConstants.JSON_START_IMAGE);
        if (startImage instanceof String s && StrUtil.isNotBlank(s)) {
            List<String> converted = com.aid.media.provider.ReferenceImageBase64Support
                    .toDataUris(Collections.singletonList(s));
            if (!converted.isEmpty()) {
                body.put(ViduConstants.JSON_START_IMAGE, converted.get(0));
            }
        }
        Object settings = body.get(ViduConstants.JSON_IMAGE_SETTINGS);
        if (settings instanceof List<?> settingList) {
            for (Object settingItem : settingList) {
                if (settingItem instanceof Map<?, ?> settingMapRaw) {
                    Map<String, Object> settingMap = (Map<String, Object>) settingMapRaw;
                    Object keyImage = settingMap.get(ViduConstants.JSON_KEY_IMAGE);
                    if (keyImage instanceof String k && StrUtil.isNotBlank(k)) {
                        List<String> converted = com.aid.media.provider.ReferenceImageBase64Support
                                .toDataUris(Collections.singletonList(k));
                        if (!converted.isEmpty()) {
                            settingMap.put(ViduConstants.JSON_KEY_IMAGE, converted.get(0));
                        }
                    }
                }
            }
        }
    }

    /**
     * 按官方文档裁剪各场景不支持的字段（Vidu 会对多余字段报 FieldUnwanted）：
     * - img2video / start-end2video：请求体没有 aspect_ratio（比例跟随输入图）；
     * - multiframe：请求体只有 model/start_image/image_settings/resolution/水印/透传/回调，
     *   顶层 prompt、duration、aspect_ratio、audio 系列字段均不存在（prompt/duration 在 image_settings 内）。
     */
    private void pruneUnsupportedFields(Map<String, Object> body, ViduScenario scenario) {
        if (scenario == ViduScenario.IMAGE_TO_VIDEO || scenario == ViduScenario.START_END_TO_VIDEO) {
            body.remove(ViduConstants.JSON_ASPECT_RATIO);
        }
        if (scenario == ViduScenario.MULTI_FRAME) {
            body.remove(ViduConstants.JSON_PROMPT);
            body.remove(ViduConstants.JSON_DURATION);
            body.remove(ViduConstants.JSON_ASPECT_RATIO);
            body.remove(ViduConstants.JSON_AUDIO);
            body.remove(ViduConstants.JSON_AUDIO_TYPE);
            body.remove(ViduConstants.JSON_VOICE_ID);
            body.remove(ViduConstants.JSON_BGM);
        }
    }

    /**
     * 音画字段下发（capability 门禁）：能力来自 modelConfig.capabilityJson，解析
     * supportsAudio/supportsBgm/supportsVoiceId/audioTypes。规则：
     * - 仅当 supportsAudio 且 request.audio!=null → 下发 audio；
     * - audio=true 且 supportsVoiceId 且 voiceId 非空 → 下发 voice_id；
     * - audio=true 且 audioType 合法（在 capability.audioTypes 或三枚举内）→ 下发 audio_type；
     * - 仅当 supportsBgm 且 request.bgm!=null → 下发 bgm（遵守文档：q3 系列 bgm 不生效，则 capability 不声明 supportsBgm 即可）。
     */
    private void applyAudioFields(Map<String, Object> body, MediaVideoGenerateRequest request, AiModelConfigVo modelConfig) {
        JSONObject capability = parseCapability(modelConfig);
        boolean supportsAudio = getBool(capability, "supportsAudio");
        boolean supportsBgm = getBool(capability, "supportsBgm");
        boolean supportsVoiceId = getBool(capability, "supportsVoiceId");
        boolean audioOn = false;
        if (supportsAudio && request.getAudio() != null) {
            body.put(ViduConstants.JSON_AUDIO, request.getAudio());
            audioOn = Boolean.TRUE.equals(request.getAudio());
        }
        if (audioOn && supportsVoiceId && StrUtil.isNotBlank(request.getVoiceId())) {
            body.put(ViduConstants.JSON_VOICE_ID, request.getVoiceId());
        }
        if (audioOn && StrUtil.isNotBlank(request.getAudioType())
            && isAudioTypeValid(request.getAudioType(), capability)) {
            body.put(ViduConstants.JSON_AUDIO_TYPE, request.getAudioType());
        }
        if (supportsBgm && request.getBgm() != null) {
            body.put(ViduConstants.JSON_BGM, request.getBgm());
        }
    }

    /**
     * 构建对口型(lip-sync)请求体（按 Vidu 官方 lip-sync 接口字段）：
     * 音频驱动（audio_url）与文本驱动（text + voice_id/speed/volume）二选一，
     * options.audio_url 非空时优先走音频驱动，此时不下发 text 系字段——
     * 避免业务层为任务存档填充的 prompt 被误当成文本驱动内容，与 audio_url 冲突被上游拒单。
     */
    private Map<String, Object> buildLipSyncBody(MediaVideoGenerateRequest request, AiModelConfigVo modelConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        String videoUrl = readOptionAsString(request, ViduConstants.OPTIONS_VIDEO_URL);
        if (StrUtil.isBlank(videoUrl)) {
            videoUrl = request.getImageUrl();
        }
        if (StrUtil.isNotBlank(videoUrl)) {
            body.put(ViduConstants.JSON_VIDEO_URL, videoUrl);
        }
        String audioUrl = readOptionAsString(request, ViduConstants.OPTIONS_AUDIO_URL);
        boolean audioDriven = StrUtil.isNotBlank(audioUrl);
        if (audioDriven) {
            body.put(ViduConstants.JSON_AUDIO_URL, audioUrl);
        } else {
            // 文本驱动：text 必填，voice_id / speed / volume 仅该模式生效
            if (StrUtil.isNotBlank(request.getPrompt())) {
                body.put(ViduConstants.JSON_TEXT, request.getPrompt());
            }
            String voiceId = StrUtil.isNotBlank(request.getVoiceId())
                ? request.getVoiceId()
                : readOptionAsString(request, ViduConstants.OPTIONS_VOICE_ID);
            if (StrUtil.isNotBlank(voiceId)) {
                body.put(ViduConstants.JSON_VOICE_ID, voiceId);
            }
            Object speed = readOption(request, ViduConstants.OPTIONS_SPEED);
            if (speed != null) {
                body.put(ViduConstants.JSON_SPEED, speed);
            }
            Object volume = readOption(request, ViduConstants.OPTIONS_VOLUME);
            if (volume != null) {
                body.put(ViduConstants.JSON_VOLUME, volume);
            }
        }
        String refPhotoUrl = readOptionAsString(request, ViduConstants.OPTIONS_REF_PHOTO_URL);
        if (StrUtil.isNotBlank(refPhotoUrl)) {
            body.put(ViduConstants.JSON_REF_PHOTO_URL, refPhotoUrl);
        }
        applyCallbackUrl(body, modelConfig);
        //    resolution/seed/aspect_ratio/generateMode 等无关字段漏发给上游被拒；新参数后续显式补。
        return body;
    }

    /**
     * 注入回调地址（开关）：读取供应商/模型 schedule_strategy_json.callbackBaseUrl，非空则下发 callback_url。
     * 未配置一律降级为「不下发」，回调仅作加速，轮询始终兜底。
     */
    private void applyCallbackUrl(Map<String, Object> body, AiModelConfigVo modelConfig) {
        String callbackUrl = ViduCallbackSupport.resolveCallbackBaseUrl(modelConfig);
        if (StrUtil.isNotBlank(callbackUrl)) {
            body.put(ViduConstants.JSON_CALLBACK_URL, callbackUrl);
        }
    }

    /** 解析 capabilityJson 为 JSONObject，失败返回 null（调用方按能力关闭处理）。 */
    private JSONObject parseCapability(AiModelConfigVo modelConfig) {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getCapabilityJson())) {
            return null;
        }
        try {
            return JSONUtil.parseObj(modelConfig.getCapabilityJson());
        } catch (Exception e) {
            // 解析失败仅记录告警，不阻断主流程，能力位按关闭处理。
            log.warn("vidu video capabilityJson 解析失败，音画能力按关闭处理：{}", e.getMessage());
            return null;
        }
    }

    /** 读取布尔能力位，缺省/异常视为 false。 */
    private boolean getBool(JSONObject capability, String key) {
        if (capability == null) {
            return false;
        }
        return capability.getBool(key, false);
    }

    /** 校验 audio_type 是否合法：优先匹配 capability.audioTypes，缺省回退官方三枚举。 */
    private boolean isAudioTypeValid(String audioType, JSONObject capability) {
        if (capability != null && capability.containsKey("audioTypes")) {
            try {
                List<Object> types = capability.getJSONArray("audioTypes");
                if (types != null && !types.isEmpty()) {
                    for (Object t : types) {
                        if (t != null && audioType.equals(String.valueOf(t))) {
                            return true;
                        }
                    }
                    return false;
                }
            } catch (Exception ignored) {
                // capability.audioTypes 结构异常时回退到三枚举校验。
            }
        }
        return ViduConstants.AUDIO_TYPE_ENUMS.contains(audioType);
    }

    private void applyScenarioSpecificFields(Map<String, Object> body, MediaVideoGenerateRequest request, ViduScenario scenario) {
        switch (scenario) {
            case TEXT_TO_VIDEO -> {
                // 文生视频无需强制图片字段，其他参数由 options 透传。
            }
            case IMAGE_TO_VIDEO -> {
                // 图生视频接口要求 images 数组；优先使用 options.images，缺失时由 imageUrl / referenceImages 自动组装。
                ensureImagesForImageToVideo(body, request);
            }
            case REFERENCE_TO_VIDEO -> {
                // 参考生视频优先使用调用方透传的 subjects/images/videos；缺失时依次回落 imageUrl / referenceImages。
                ensureImagesForReferenceToVideo(body, request);
            }
            case START_END_TO_VIDEO -> {
                // 首尾帧接口要求 images 至少两张，优先使用 options.images；其次使用 imageUrl + endImageUrl 兜底。
                ensureImagesForStartEnd(body, request);
            }
            case MULTI_FRAME -> {
                // 多帧接口需 start_image + image_settings；优先 options 透传，缺失时做最小兜底。
                ensureMultiFrameFields(body, request);
            }
            default -> {
                // 理论不会进入，保底不做额外处理。
            }
        }
    }

    private void ensureImagesForImageToVideo(Map<String, Object> body, MediaVideoGenerateRequest request) {
        if (body.containsKey(ViduConstants.JSON_IMAGES)) {
            return;
        }
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            body.put(ViduConstants.JSON_IMAGES, Collections.singletonList(request.getImageUrl()));
            return;
        }
        // 业务层参考图回落：分镜出片链路把图放在 options.referenceImages（内部键，白名单合并不透传），
        // 未落到官方 images 字段会被 Vidu 以 "field is missing or empty" 拒单，此处补位。
        List<String> referenceImages = readReferenceImages(request);
        if (!referenceImages.isEmpty()) {
            body.put(ViduConstants.JSON_IMAGES, referenceImages);
        }
    }

    private void ensureImagesForReferenceToVideo(Map<String, Object> body, MediaVideoGenerateRequest request) {
        if (body.containsKey(ViduConstants.JSON_SUBJECTS) || body.containsKey(ViduConstants.JSON_IMAGES)
            || body.containsKey(ViduConstants.JSON_VIDEOS)) {
            return;
        }
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            body.put(ViduConstants.JSON_IMAGES, Collections.singletonList(request.getImageUrl()));
            return;
        }
        // 业务层参考图回落：同 img2video，把 options.referenceImages 落到官方 images 字段
        List<String> referenceImages = readReferenceImages(request);
        if (!referenceImages.isEmpty()) {
            body.put(ViduConstants.JSON_IMAGES, referenceImages);
        }
    }

    /**
     * 读取业务层 options.referenceImages（分镜出片链路的参考图载体），去空保序。
     */
    private List<String> readReferenceImages(MediaVideoGenerateRequest request) {
        List<String> result = new ArrayList<>();
        Object raw = readOption(request, VolcengineConstants.OPTIONS_REFERENCE_IMAGES);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private void ensureImagesForStartEnd(Map<String, Object> body, MediaVideoGenerateRequest request) {
        if (body.get(ViduConstants.JSON_IMAGES) instanceof List<?> list && !list.isEmpty()) {
            return;
        }
        String endImageUrl = readOptionAsString(request, ViduConstants.OPTIONS_END_IMAGE_URL_CAMEL, ViduConstants.OPTIONS_END_IMAGE_URL_SNAKE);
        List<String> images = new ArrayList<>();
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            images.add(request.getImageUrl());
        }
        if (StrUtil.isNotBlank(endImageUrl)) {
            images.add(endImageUrl);
        }
        if (!images.isEmpty()) {
            body.put(ViduConstants.JSON_IMAGES, images);
        }
    }

    private void ensureMultiFrameFields(Map<String, Object> body, MediaVideoGenerateRequest request) {
        if (!body.containsKey(ViduConstants.JSON_START_IMAGE) && StrUtil.isNotBlank(request.getImageUrl())) {
            body.put(ViduConstants.JSON_START_IMAGE, request.getImageUrl());
        }
        if (body.containsKey(ViduConstants.JSON_IMAGE_SETTINGS)) {
            return;
        }
        Object keyImagesRaw = readOption(request, ViduConstants.OPTIONS_KEY_IMAGES_ALT, ViduConstants.OPTIONS_KEY_IMAGES_CAMEL);
        if (!(keyImagesRaw instanceof List<?> keyImages) || keyImages.isEmpty()) {
            return;
        }
        List<Map<String, Object>> imageSettings = new ArrayList<>();
        for (Object obj : keyImages) {
            if (!(obj instanceof String keyImageUrl) || StrUtil.isBlank(keyImageUrl)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(ViduConstants.JSON_KEY_IMAGE, keyImageUrl);
            if (StrUtil.isNotBlank(request.getPrompt())) {
                item.put(ViduConstants.JSON_PROMPT, request.getPrompt());
            }
            if (request.getDurationSeconds() != null) {
                item.put(ViduConstants.JSON_DURATION, request.getDurationSeconds());
            }
            imageSettings.add(item);
        }
        if (!imageSettings.isEmpty()) {
            body.put(ViduConstants.JSON_IMAGE_SETTINGS, imageSettings);
        }
    }

    /**
     * options → 请求体透传：仅放行官方字段白名单（{@link ViduConstants#UPSTREAM_FIELD_WHITELIST}）。
     * 业务侧 options 同时承载 referenceImages / generate_audio / 扇入上下文等内部键，
     * Vidu 对多余字段返回 FieldUnwanted 拒单，非白名单键一律丢弃并 debug 记录。
     */
    private void mergeTopLevelOptions(Map<String, Object> body, MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isControlKey(key)) {
                continue;
            }
            if (!ViduConstants.UPSTREAM_FIELD_WHITELIST.contains(key)) {
                // 内部键（referenceImages / generate_audio / 扇入上下文等）不下发上游
                log.debug("vidu video options 非官方字段跳过透传: {}", key);
                continue;
            }
            body.put(key, value);
        }
    }

    private boolean isControlKey(String key) {
        // 内部控制字段清单：仅用于路由判断或本地字段补齐，不参与上游透传。
        return ViduConstants.OPTIONS_VIDU_SCENARIO.equals(key)
            || ViduConstants.OPTIONS_VIDU_ENDPOINT.equals(key)
            || ViduConstants.OPTIONS_SCENARIO.equals(key)
            || ViduConstants.OPTIONS_START_END.equals(key)
            || ViduConstants.OPTIONS_START_END_MODE.equals(key)
            || ViduConstants.OPTIONS_END_IMAGE_URL_CAMEL.equals(key)
            || ViduConstants.OPTIONS_END_IMAGE_URL_SNAKE.equals(key)
            // 音画字段必须经 capability 门禁（applyAudioFields）下发，禁止 options 旁路覆盖绕过能力校验。
            || ViduConstants.JSON_AUDIO.equals(key)
            || ViduConstants.JSON_BGM.equals(key)
            || ViduConstants.JSON_AUDIO_TYPE.equals(key)
            || ViduConstants.JSON_VOICE_ID.equals(key)
            // 回调地址必须由服务端按配置注入，禁止客户端经 options 指定，防回调被重定向到任意地址。
            || ViduConstants.JSON_CALLBACK_URL.equals(key);
    }

    private String readScenarioFromOptions(MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty()) {
            return null;
        }
        Object raw = options.get(ViduConstants.OPTIONS_VIDU_SCENARIO);
        if (raw == null) {
            raw = options.get(ViduConstants.OPTIONS_VIDU_ENDPOINT);
        }
        if (raw == null) {
            raw = options.get(ViduConstants.OPTIONS_SCENARIO);
        }
        return raw == null ? null : String.valueOf(raw);
    }

    private boolean hasOptionKey(MediaVideoGenerateRequest request, String key) {
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty()) {
            return false;
        }
        return options.containsKey(key) && options.get(key) != null;
    }

    private Object readOption(MediaVideoGenerateRequest request, String... keys) {
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (options.containsKey(key) && options.get(key) != null) {
                return options.get(key);
            }
        }
        return null;
    }

    private String readOptionAsString(MediaVideoGenerateRequest request, String... keys) {
        // 读取 options 值并转换为字符串。
        Object value = readOption(request, keys);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 构建任务查询 URL：base_url + task_query_suffix（路径全部来自数据库配置，纯数据库驱动）
     */
    private String buildTaskUrl(String baseUrl, String taskQuerySuffix, String providerTaskId) {
        if (StrUtil.isBlank(baseUrl)) {
            log.error("vidu video model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(taskQuerySuffix)) {
            log.error("vidu video model taskQuerySuffix 为空，请在 aid_ai_provider 表配置 task_query_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(providerTaskId)) {
            return trimSlash(baseUrl);
        }
        return trimSlash(baseUrl.trim()) + String.format(taskQuerySuffix, providerTaskId);
    }

    private String doPost(String url, String apiKey, String json) {
        // POST 请求：官方要求 Authorization: Token {apiKey}。
        // 仅用标准 Authorization 头，避免 API Key 双点暴露。
        try (HttpResponse response = HttpRequest.post(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, ViduConstants.AUTH_TOKEN_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .body(json)
            .timeout(ViduConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            // 返回原始响应给上游统一解析。
            return response.body();
        }
    }

    private String doGet(String url, String apiKey) {
        // GET 请求：同样采用 Token 鉴权。
        try (HttpResponse response = HttpRequest.get(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, ViduConstants.AUTH_TOKEN_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .timeout(ViduConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            // 返回原始响应。
            return response.body();
        }
    }

    private String trimSlash(String base) {
        // 清理末尾 /，保证路径拼接稳定。
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    // 视频场景枚举：用于确定请求体结构（路径已移到数据库 api_suffix 字段）。
    private enum ViduScenario {
        TEXT_TO_VIDEO,
        IMAGE_TO_VIDEO,
        REFERENCE_TO_VIDEO,
        START_END_TO_VIDEO,
        MULTI_FRAME,
        LIP_SYNC;

        private static ViduScenario fromValue(String raw) {
            String normalized = StringUtils.defaultString(raw).trim().toLowerCase();
            normalized = normalized.replace('-', '_').replace('/', '_');
            return switch (normalized) {
                case "text2video", "text_to_video", "text" -> TEXT_TO_VIDEO;
                case "img2video", "image2video", "image_to_video", "image", "i2v" -> IMAGE_TO_VIDEO;
                case "reference2video", "reference_to_video", "reference", "subject" -> REFERENCE_TO_VIDEO;
                case "start_end2video", "start_end_to_video", "start_end", "kf2v", "startend" -> START_END_TO_VIDEO;
                case "multiframe", "multi_frame", "multi" -> MULTI_FRAME;
                case "lip_sync", "lipsync", "lip" -> LIP_SYNC;
                default -> TEXT_TO_VIDEO;
            };
        }
    }
}
