package com.aid.media.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.provider.ReferenceAudioLimiter.ReferenceAudioCapability;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/** 模型能力参数校验器。 */
@Slf4j
public final class ModelCapabilityValidator {

    /** capability_json 键：清晰度档位白名单 */
    private static final String KEY_SIZE_OPTIONS = ModelCapabilityResolver.KEY_SIZE_OPTIONS;

    /** capability_json 键：画面比例白名单 */
    private static final String KEY_ASPECT_RATIO_OPTIONS = ModelCapabilityResolver.KEY_ASPECT_RATIO_OPTIONS;

    /** capability_json 键：视频时长白名单（秒） */
    private static final String KEY_DURATION_OPTIONS = "durationOptions";

    /** capability_json 键：提示词最大字符数 */
    private static final String KEY_MAX_PROMPT_CHARACTERS = "maxPromptCharacters";
    private static final String KEY_MAX_PROMPT_CHARACTERS_CJK = "maxPromptCharactersCjk";

    /** capability_json 键：是否支持音画同出用户开关 */
    private static final String KEY_SUPPORTS_AUDIO = "supportsAudio";
    private static final String KEY_DEFAULT_AUDIO = "defaultAudio";

    /** options 中业务侧音画同出键（火山 Seedance 等历史写法） */
    private static final String OPTION_GENERATE_AUDIO = "generate_audio";

    /** options 中承载清晰度档位的候选键（各链路历史写法不一，统一在此收口） */
    private static final String[] OPTION_SIZE_KEYS = {"resolution", "imageSize", "size"};

    /** options 中承载画面比例的候选键 */
    private static final String[] OPTION_RATIO_KEYS = {"aspect_ratio", "aspectRatio"};

    private ModelCapabilityValidator() {
    }

    /** 明确配置的提示词字符上限在建任务和预冻结前生效。 */
    public static void validatePrompt(AiModelConfigVo modelConfig, String prompt) {
        if (Objects.isNull(modelConfig) || StrUtil.isBlank(prompt)) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        boolean cjk = containsCjk(prompt);
        JsonNode maxNode = capability == null ? null : capability.get(
                cjk ? KEY_MAX_PROMPT_CHARACTERS_CJK : KEY_MAX_PROMPT_CHARACTERS);
        if ((maxNode == null || !maxNode.isNumber()) && cjk && capability != null) {
            maxNode = capability.get(KEY_MAX_PROMPT_CHARACTERS);
        }
        if (maxNode == null || !maxNode.isNumber()) {
            return;
        }
        int max = (int) Math.floor(maxNode.doubleValue());
        if (max >= 0 && prompt.length() > max) {
            log.info("提示词超过模型能力上限: modelCode={}, cjk={}, max={}, actual={}",
                    modelConfig.getModelCode(), cjk, max, prompt.length());
            throw new ServiceException("提示词过长");
        }
    }

    private static boolean containsCjk(String prompt) {
        return prompt.codePoints().anyMatch(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL;
        });
    }

    /**
     * 校验图片生成参数（清晰度档位 + 画面比例）。
     *
     * @param modelConfig 模型聚合配置（含 capabilityJson，可空则不校验）
     * @param size        请求顶层 size（可空）
     * @param options     请求扩展参数（可空；从中提取 resolution/imageSize/aspect_ratio 等）
     */
    public static void validateImage(AiModelConfigVo modelConfig, String size, Map<String, Object> options) {
        if (Objects.isNull(modelConfig)) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return;
        }
        // 清晰度：顶层 size 优先，其次 options 内历史键
        String effectiveSize = StrUtil.isNotBlank(size) ? size : readFirstText(options, OPTION_SIZE_KEYS);
        validateOption(capability, KEY_SIZE_OPTIONS, effectiveSize,
                modelConfig.getModelCode(), ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED);
        String ratio = readFirstText(options, OPTION_RATIO_KEYS);
        validateOption(capability, KEY_ASPECT_RATIO_OPTIONS, ratio,
                modelConfig.getModelCode(), ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED);
    }

    /**
     * 按实际生成场景校验图片清晰度与比例。场景能力存在白名单时优先使用场景白名单，
     * 缺失时回退模型顶层白名单，解决同一模型文生图可用 4K、图像编辑仅可用 2K 一类组合约束。
     *
     * @param modelConfig 模型聚合配置
     * @param request     图片生成请求
     */
    public static void validateImage(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        if (Objects.isNull(modelConfig) || Objects.isNull(request)) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return;
        }
        Map<String, Object> options = request.getOptions();
        String sceneCode = resolveImageScene(request);
        JsonNode sceneCapability = capability.path("sceneRules").path(sceneCode);
        String effectiveSize = StrUtil.isNotBlank(request.getSize())
                ? request.getSize() : readFirstText(options, OPTION_SIZE_KEYS);
        validateSceneOption(capability, sceneCapability, KEY_SIZE_OPTIONS, effectiveSize,
                modelConfig.getModelCode(), sceneCode, ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED);
        String ratio = readFirstText(options, OPTION_RATIO_KEYS);
        validateSceneOption(capability, sceneCapability, KEY_ASPECT_RATIO_OPTIONS, ratio,
                modelConfig.getModelCode(), sceneCode, ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED);
    }

    /**
     * 归一化图片画面比例：模型未声明比例能力（{@code aid_ai_model.supports_aspect_ratio} 非 1）时剔除比例参数。
     *
     * <p>画面比例是可选偏好而非必填项，模型不支持时剔除并 warn，不升级为用户可见的失败。
     * 剔除在建任务 / 预冻结之前完成，保证 {@code request_json} 与真正下发厂商的参数一致，
     * 也避免业务链路无差别下发比例后被厂商在提交阶段硬拒绝。</p>
     *
     * @param modelConfig 模型聚合配置
     * @param request     图片生成请求（会被原地归一化）
     */
    public static void normalizeImageAspectRatio(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        if (Objects.isNull(modelConfig) || Objects.isNull(request)
                || Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())) {
            return;
        }
        Map<String, Object> options = mutableOptions(request.getOptions());
        request.setOptions(options);
        Object removed = removeAspectRatioOptions(options);
        if (Objects.nonNull(removed)) {
            log.warn("模型未声明画面比例能力已剔除图片比例参数: modelCode={}, aspectRatio={}",
                    modelConfig.getModelCode(), removed);
        }
    }

    /**
     * 归一化视频画面比例参数，并保留 FOLLOW_INPUT 模型的内部输入图目标。
     *
     * @param modelConfig 模型聚合配置
     * @param request     视频生成请求（会被原地归一化）
     */
    public static void normalizeVideoAspectRatio(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (Objects.isNull(modelConfig) || Objects.isNull(request)) {
            return;
        }
        boolean followInput = ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig);
        if (Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio()) && !followInput) {
            return;
        }
        Map<String, Object> options = mutableOptions(request.getOptions());
        request.setOptions(options);
        Object removed = removeAspectRatioOptions(options);
        if (followInput) {
            if (StrUtil.isBlank(request.getAspectRatio()) && Objects.nonNull(removed)) {
                request.setAspectRatio(String.valueOf(removed).trim());
            }
            // 比例仅供媒体层归一化输入图，Provider 按官方协议不会把它作为独立字段下发。
            return;
        }
        if (StrUtil.isNotBlank(request.getAspectRatio())) {
            removed = request.getAspectRatio();
            request.setAspectRatio(null);
        }
        if (Objects.nonNull(removed)) {
            log.warn("模型未声明画面比例能力已剔除视频比例参数: modelCode={}, aspectRatio={}",
                    modelConfig.getModelCode(), removed);
        }
    }

    /**
     * 剔除扩展参数中的比例键（仅顶层键，不触碰业务上下文等嵌套结构）。
     *
     * @param options 扩展参数（可空）
     * @return 被剔除的比例值；无则返回 null
     */
    private static Object removeAspectRatioOptions(Map<String, Object> options) {
        if (Objects.isNull(options) || options.isEmpty()) {
            return null;
        }
        Object removed = null;
        for (String key : OPTION_RATIO_KEYS) {
            Object value = options.remove(key);
            if (Objects.nonNull(value)) {
                removed = value;
            }
        }
        return removed;
    }

    private static Map<String, Object> mutableOptions(Map<String, Object> options) {
        return Objects.isNull(options) ? null : new LinkedHashMap<>(options);
    }

    /**
     * 校验视频生成参数（清晰度档位 + 画面比例 + 时长）。
     *
     * @param modelConfig     模型聚合配置（含 capabilityJson，可空则不校验）
     * @param durationSeconds 请求时长秒（可空）
     * @param aspectRatio     请求顶层画面比例（可空）
     * @param options         请求扩展参数（可空；从中提取 resolution 等）
     */
    public static void validateVideo(AiModelConfigVo modelConfig, Integer durationSeconds,
                                     String aspectRatio, Map<String, Object> options) {
        if (Objects.isNull(modelConfig)) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return;
        }
        String resolution = readFirstText(options, OPTION_SIZE_KEYS);
        validateOption(capability, KEY_SIZE_OPTIONS, resolution,
                modelConfig.getModelCode(), ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED);
        String ratio = StrUtil.isNotBlank(aspectRatio) ? aspectRatio : readFirstText(options, OPTION_RATIO_KEYS);
        if (ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig)) {
            if (StrUtil.isNotBlank(ratio)) {
                ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig, ratio);
            }
        } else {
            validateOption(capability, KEY_ASPECT_RATIO_OPTIONS, ratio,
                    modelConfig.getModelCode(), ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED);
        }
        validateDuration(capability, durationSeconds, modelConfig.getModelCode());
    }

    /**
     * 归一化并校验视频音画同出参数。
     * 顶层 audio 与 options.generate_audio 双向对齐；supportsAudio 非 true 时禁止开启有声；
     * 对口型请求不走本校验。
     *
     * @param modelConfig 模型聚合配置
     * @param request     视频生成请求（会被原地归一化）
     */
    public static void normalizeAndValidateVideoAudio(AiModelConfigVo modelConfig,
                                                      MediaVideoGenerateRequest request) {
        if (Objects.isNull(modelConfig) || Objects.isNull(request)) {
            return;
        }
        // 对口型：音频来自 audio_url / TTS，不是音画同出开关
        if (isLipSyncRequest(request)) {
            return;
        }
        Boolean audio = request.getAudio();
        Map<String, Object> options = request.getOptions();
        if (Objects.isNull(audio) && Objects.nonNull(options) && options.containsKey(OPTION_GENERATE_AUDIO)) {
            audio = parseBooleanOption(options.get(OPTION_GENERATE_AUDIO));
            request.setAudio(audio);
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        boolean supportsAudio = capability != null
                && capability.path(KEY_SUPPORTS_AUDIO).asBoolean(false);
        // 兼容旧模型：未声明 defaultAudio 时仍默认开启；只有能力明确声明 false 才默认无声。
        if (Objects.isNull(audio) && supportsAudio) {
            JsonNode configuredDefault = capability.get(KEY_DEFAULT_AUDIO);
            audio = configuredDefault != null && configuredDefault.isBoolean()
                    ? configuredDefault.asBoolean() : Boolean.TRUE;
            request.setAudio(audio);
        }
        if (Objects.isNull(audio)) {
            return;
        }
        // 同步写入 options，供火山等仍读 generate_audio 的 Provider 使用
        options = Objects.isNull(options) ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
        request.setOptions(options);
        options.put(OPTION_GENERATE_AUDIO, audio);

        if (!supportsAudio) {
            if (Boolean.TRUE.equals(audio)) {
                log.info("模型不支持音画同出: modelCode={}, audio={}", modelConfig.getModelCode(), audio);
                throw new ServiceException("模型不支持音频");
            }
            // 显式无声或历史脏值：清空，避免误下发
            request.setAudio(null);
            options.remove(OPTION_GENERATE_AUDIO);
        }
    }

    /**
     * 归一化并校验视频参考音频（能力、去重、格式、时长、条数）。
     *
     * <p>失败处理按来源分级：用户显式选择的音频记录不合规先 log 再抛短文案；
     * 由提示词占位推导出的隐式引用不合规则剔除并 warn，降级为不带参考音频继续出片，
     * 避免自动推导出的约束升级为用户可见的强制项。条数超限统一截断，不抛超限异常。</p>
     *
     * @param modelConfig 模型配置
     * @param request     视频生成请求（会被原地归一化）
     */
    public static void normalizeAndValidateReferenceAudios(AiModelConfigVo modelConfig,
                                                           MediaVideoGenerateRequest request) {
        if (Objects.isNull(modelConfig) || Objects.isNull(request) || isLipSyncRequest(request)) {
            return;
        }
        List<ReferenceAudioInput> audios = request.getReferenceAudios();
        if (CollectionUtil.isEmpty(audios)) {
            return;
        }
        ReferenceAudioCapability capability = ReferenceAudioLimiter.readCapability(modelConfig);
        if (capability.isIncomplete()) {
            log.warn("参考音频能力配置不完整按未开启处理: modelCode={}, maxCount={}, formats={}",
                    modelConfig.getModelCode(), capability.getMaxCount(), capability.getFormats());
        }
        if (!capability.isUsable()) {
            dropAllReferenceAudios(modelConfig, request, "模型不支持参考音频", "能力未开启");
            return;
        }
        // 参考音频依赖音画同出：无声视频下发参考音色无意义
        if (!Boolean.TRUE.equals(request.getAudio())) {
            dropAllReferenceAudios(modelConfig, request, "请开启视频声音", "音画同出未开启");
            return;
        }
        long maxTotalDurationMs = capability.getMaxTotalDurationSeconds() * 1000L;
        long totalDurationMs = 0L;
        Set<String> seenUrls = new LinkedHashSet<>();
        List<ReferenceAudioInput> accepted = new ArrayList<>();
        for (ReferenceAudioInput audio : audios) {
            if (Objects.isNull(audio)) {
                continue;
            }
            String reason = resolveRejectReason(capability, audio);
            if (StrUtil.isNotBlank(reason)) {
                rejectReferenceAudio(modelConfig, audio, reason);
                continue;
            }
            // 同一音色被多个占位引用时只下发一次，避免重复计入条数与总时长
            if (!seenUrls.add(audio.getSampleUrl())) {
                log.warn("参考音频重复引用已合并: modelCode={}, name={}",
                        modelConfig.getModelCode(), audio.getName());
                continue;
            }
            if (maxTotalDurationMs > 0 && totalDurationMs + audio.getDurationMs() > maxTotalDurationMs) {
                seenUrls.remove(audio.getSampleUrl());
                rejectReferenceAudio(modelConfig, audio, "总时长超限");
                continue;
            }
            totalDurationMs += audio.getDurationMs();
            accepted.add(audio);
        }
        request.setReferenceAudios(
                ReferenceAudioLimiter.limit(accepted, modelConfig, modelConfig.getModelCode()));
    }

    /**
     * 逐条判定参考音频是否可下发。
     *
     * @param capability 参考音频能力配置
     * @param audio      参考音频
     * @return 不可下发的原因（日志用）；可下发返回 null
     */
    private static String resolveRejectReason(ReferenceAudioCapability capability, ReferenceAudioInput audio) {
        if (!isHttpUrl(audio.getSampleUrl())) {
            return "URL无效";
        }
        if (!capability.acceptsFormat(audio.getFormat())) {
            return "格式:" + StrUtil.nullToDefault(audio.getFormat(), "空");
        }
        if (!capability.acceptsDuration(audio.getDurationMs())) {
            return "时长:" + audio.getDurationMs();
        }
        return null;
    }

    /**
     * 单条参考音频不合规：显式来源抛短文案，隐式来源剔除并 warn。
     *
     * @param modelConfig 模型配置
     * @param audio       参考音频
     * @param reason      日志原因
     */
    private static void rejectReferenceAudio(AiModelConfigVo modelConfig, ReferenceAudioInput audio, String reason) {
        String message = reason.startsWith("格式") ? "参考音频格式不符"
                : (reason.startsWith("时长") || reason.startsWith("总时长")) ? "参考音频时长不符"
                : "参考音频不可用";
        if (audio.isExplicit()) {
            // 显式来源有配音记录与上传音频两类，两个 ID 都打出来才能定位到具体是哪一条
            log.info("视频参考音频校验失败: modelCode={}, reason={}, sourceType={}, audioRecordId={},"
                            + " referenceAudioId={}",
                    modelConfig.getModelCode(), reason, audio.getSourceType(),
                    audio.getAudioRecordId(), audio.getReferenceAudioId());
            throw new ServiceException(message);
        }
        log.warn("参考音频不合规已剔除: modelCode={}, reason={}, name={}",
                modelConfig.getModelCode(), reason, audio.getName());
    }

    /**
     * 能力不可用：含显式选择时抛短文案，否则整体剔除并 warn。
     *
     * @param modelConfig 模型配置
     * @param request     视频生成请求
     * @param message     用户短文案
     * @param reason      日志原因
     */
    private static void dropAllReferenceAudios(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
                                               String message, String reason) {
        List<ReferenceAudioInput> audios = request.getReferenceAudios();
        boolean hasExplicit = audios.stream()
                .anyMatch(audio -> Objects.nonNull(audio) && audio.isExplicit());
        if (hasExplicit) {
            log.info("视频参考音频校验失败: modelCode={}, reason={}, count={}",
                    modelConfig.getModelCode(), reason, audios.size());
            throw new ServiceException(message);
        }
        log.warn("参考音频降级丢弃: modelCode={}, reason={}, count={}",
                modelConfig.getModelCode(), reason, audios.size());
        request.setReferenceAudios(new ArrayList<>());
    }

    private static boolean isHttpUrl(String value) {
        if (StrUtil.isBlank(value) || value.startsWith("data:")) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StrUtil.isNotBlank(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 是否对口型请求：options 同时带 video_url 与 audio_url 契约键。
     */
    private static boolean isLipSyncRequest(MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        if (Objects.isNull(options) || options.isEmpty()) {
            return false;
        }
        // 与 MediaGenerationServiceImpl.isLipSyncRequest 口径一致：键存在即视为对口型
        return options.containsKey("video_url") && options.containsKey("audio_url");
    }

    /**
     * 解析 options 布尔值（兼容 Boolean / "true"/"false"）。
     */
    private static Boolean parseBooleanOption(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && StrUtil.isNotBlank(s)) {
            return Boolean.parseBoolean(s.trim());
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return null;
    }

    /**
     * 单项白名单校验：白名单缺失/为空不拦；入参为空不拦；归一化后未命中抛短文案。
     *
     * @param capability   已解析的 capability 根节点
     * @param whitelistKey 白名单键名
     * @param value        待校验值（可空）
     * @param modelCode    模型编码（日志用）
     * @param errorMessage 未命中时的用户短文案
     */
    private static void validateOption(JsonNode capability, String whitelistKey, String value,
                                       String modelCode, String errorMessage) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        List<String> whitelist = ModelCapabilityResolver.readOptions(capability, whitelistKey);
        if (CollectionUtil.isEmpty(whitelist)) {
            return;
        }
        if (Objects.nonNull(ModelCapabilityResolver.matchOption(whitelist, value))) {
            return;
        }
        log.info("模型能力校验未命中: modelCode={}, key={}, value={}, whitelist={}",
                modelCode, whitelistKey, value, whitelist);
        throw new ServiceException(errorMessage);
    }

    /** 场景白名单优先、模型顶层白名单兜底的单项校验。 */
    private static void validateSceneOption(JsonNode capability, JsonNode sceneCapability,
                                            String whitelistKey, String value, String modelCode,
                                            String sceneCode, String errorMessage) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        List<String> whitelist = ModelCapabilityResolver.readOptions(sceneCapability, whitelistKey);
        if (CollectionUtil.isEmpty(whitelist)) {
            whitelist = ModelCapabilityResolver.readOptions(capability, whitelistKey);
        }
        if (CollectionUtil.isEmpty(whitelist)
                || Objects.nonNull(ModelCapabilityResolver.matchOption(whitelist, value))) {
            return;
        }
        log.info("模型场景能力校验未命中: modelCode={}, scene={}, key={}, value={}, whitelist={}",
                modelCode, sceneCode, whitelistKey, value, whitelist);
        throw new ServiceException(errorMessage);
    }

    /** 根据图片输入与组图开关识别能力场景。 */
    private static String resolveImageScene(MediaImageGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        if (options != null && Boolean.parseBoolean(String.valueOf(options.get("enable_sequential")))) {
            return "sequentialImage";
        }
        if (StrUtil.isNotBlank(request.getReferenceImageUrl()) || containsNonEmptyList(options, "referenceImages")
                || containsNonEmptyList(options, "images")) {
            return "imageToImage";
        }
        return "textToImage";
    }

    /** 判断 options 指定键是否为非空列表。 */
    private static boolean containsNonEmptyList(Map<String, Object> options, String key) {
        return options != null && options.get(key) instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 时长白名单校验：durationOptions 缺失/为空不拦；入参为空不拦；未命中抛「时长不支持」。
     *
     * @param capability      已解析的 capability 根节点
     * @param durationSeconds 请求时长秒
     * @param modelCode       模型编码（日志用）
     */
    private static void validateDuration(JsonNode capability, Integer durationSeconds, String modelCode) {
        if (Objects.isNull(durationSeconds)) {
            return;
        }
        JsonNode node = capability.get(KEY_DURATION_OPTIONS);
        if (Objects.isNull(node) || !node.isArray() || node.isEmpty()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isNumber() && item.intValue() == durationSeconds) {
                return;
            }
        }
        log.info("模型时长校验未命中: modelCode={}, duration={}, whitelist={}",
                modelCode, durationSeconds, node);
        throw new ServiceException("时长不支持");
    }

    /**
     * 取 options 中首个非空文本值。
     *
     * @param options 扩展参数（可空）
     * @param keys    候选键（按优先级）
     * @return 首个非空值；均无返回 null
     */
    private static String readFirstText(Map<String, Object> options, String[] keys) {
        if (Objects.isNull(options) || options.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value instanceof String && StrUtil.isNotBlank((String) value)) {
                return (String) value;
            }
        }
        return null;
    }
}
