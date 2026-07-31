package com.aid.billing.util;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.util.ImageBillingCapabilityHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费参数提取工具：从不同请求类型中提取统一的计费参数。
 * 提供静态方法，不持有状态，不依赖Spring容器。
 */
public final class BillingInputExtractor {

    /**
     * estimatedOutputChars/max_tokens/expectedImageCount/n/videoDuration 等参数统一做硬上限保护，
     * 防止前端构造巨大参数导致结算层金额溢出或冲垮上游配额。
     */
    /** 文本预估输出字符硬上限（= 2M 字符，对应约 1.6M token）。 */
    private static final int TEXT_ESTIMATED_OUTPUT_CHARS_HARD_CAP = 2_000_000;
    /** 文本预估输出 token 硬上限。 */
    private static final int TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP = 1_600_000;
    /** 图片 expectedImageCount 硬上限（在 capability 上限之上再加一层绝对兜底）。 */
    private static final int IMAGE_EXPECTED_COUNT_HARD_CAP = 16;
    /** 视频 durationSeconds 硬上限：300 秒足够覆盖任何合法视频生成。 */
    private static final int VIDEO_DURATION_HARD_CAP_SECONDS = 300;
    /** TTS text 字符硬上限（与 StoryboardWorkbenchServiceImpl.TTS_TEXT_MAX_LENGTH 对齐）。 */
    private static final int TTS_TEXT_HARD_CAP_CHARS = 50_000;
    /** Prompt 字符硬上限（inputChars 的保护，避免 BillingEstimateResolver 侧被打爆）。 */
    private static final int PROMPT_INPUT_HARD_CAP_CHARS = 2_000_000;

    private BillingInputExtractor() {
    }

    /**
     * 从图片生成请求提取计费参数（兼容老调用，使用 request.modelName 作为兜底 modelCode）。
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request) {
        return fromImageRequest(request, request == null ? null : request.getModelName(), null);
    }

    /**
     * 从图片生成请求提取计费参数（兼容老调用，未传配置化的 max_output_count，仅按硬编码兜底）。
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request, String effectiveModelCode) {
        return fromImageRequest(request, effectiveModelCode, null);
    }

    /**
     * 从图片生成请求提取计费参数。
     *
     * @param request                  原始请求
     * @param effectiveModelCode       已解析的最终模型 code（例如 request.modelName 为空时传 modelConfig.modelCode）
     * @param configuredMaxOutputCount aid_ai_model.max_output_count（可空，空则按硬编码兜底）
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request,
                                                String effectiveModelCode,
                                                Integer configuredMaxOutputCount) {
        Map<String, Object> params = new HashMap<>();
        // 分辨率三级取值，保证计费命中的 SKU 与真实出图档位一致：
        // 1) options.resolution 显式档位（Vidu 等厂商为 1080p/2K/4K 枚举，与下发上游的字段同源）
        // 2) size 本身是档位串（业务层存在 setSize("2K") 的用法）
        // 3) size 为宽高尺寸串时按像素推断（4K/2K/1K/SD）
        String resolution = null;
        String explicitResolution = extractFromOptions(request.getOptions(), "resolution");
        if (CharSequenceUtil.isNotBlank(explicitResolution)) {
            String tier = ResolutionUtil.parseTier(explicitResolution);
            resolution = CharSequenceUtil.isNotBlank(tier) ? tier : explicitResolution.trim();
        }
        if (CharSequenceUtil.isBlank(resolution)) {
            resolution = ResolutionUtil.parseTier(request.getSize());
        }
        if (CharSequenceUtil.isBlank(resolution)) {
            resolution = ResolutionUtil.parseResolution(request.getSize());
        }
        params.put("resolution", resolution);
        // 宽高比
        params.put("ratio", ResolutionUtil.parseRatio(request.getSize()));
        // 生成模式：ultra 固定为超分；其余按是否有参考图区分 编辑 / 文生图。必须用最终 modelCode 判定
        String generateMode = resolveGenerateMode(request, effectiveModelCode);
        params.put("generateMode", generateMode);
        // 预期输出张数：模型级上限保护，配置化优先、硬编码兜底
        int expectedImageCount = resolveExpectedImageCount(request);
        expectedImageCount = ImageBillingCapabilityHelper.normalizeExpectedCount(
                effectiveModelCode, expectedImageCount, configuredMaxOutputCount);
        params.put("expectedImageCount", expectedImageCount);
        // 兼容既有 SKU 规则：imageCount 与 expectedImageCount 同值
        params.put("imageCount", expectedImageCount);
        // 通用原始参数提取：输入字符数（TOKEN 模型的估算在 BillingEstimateResolver 中完成）
        int inputChars = request != null && CharSequenceUtil.isNotBlank(request.getPrompt())
                ? request.getPrompt().length() : 0;
        params.put("inputChars", inputChars);
        // 从 options 提取 imageSize（部分厂商使用此字段指定分辨率档位，TOKEN 模型估算时使用）
        if (request != null && request.getOptions() != null) {
            String imageSize = extractFromOptions(request.getOptions(), "imageSize");
            if (CharSequenceUtil.isBlank(imageSize)) {
                imageSize = extractFromOptions(request.getOptions(), "image_size");
            }
            if (CharSequenceUtil.isNotBlank(imageSize)) {
                params.put("imageSize", imageSize.trim().toUpperCase());
            }
            // 提取 aspectRatio（TOKEN 图片模型预估输出 token 时使用）
            String aspectRatio = extractFromOptions(request.getOptions(), "aspect_ratio");
            if (CharSequenceUtil.isBlank(aspectRatio)) {
                aspectRatio = extractFromOptions(request.getOptions(), "aspectRatio");
            }
            if (CharSequenceUtil.isNotBlank(aspectRatio)) {
                params.put("aspectRatio", aspectRatio.trim());
            }
            // 提取 quality（gpt-image 等 TOKEN 图片模型按 quality×size 预估输出 token 时使用）
            String quality = extractFromOptions(request.getOptions(), "quality");
            if (CharSequenceUtil.isNotBlank(quality)) {
                params.put("quality", quality.trim());
            }
        }
        // 参考图张数（gpt-image 等编辑模式按张数预估高保真输入 token 时使用）
        params.put("referenceImageCount", countReferenceImages(request));
        // 保留原始 size 值（TOKEN 模型估算分辨率档位的兜底来源）
        if (request != null && CharSequenceUtil.isNotBlank(request.getSize())) {
            params.put("rawSize", request.getSize().trim());
        }
        return new BillingInput("IMAGE", params);
    }

    /**
     * 统计请求中的参考图张数：顶层 referenceImageUrl + options.images + options.referenceImages。
     * 仅用于 TOKEN 图片模型编辑模式的输入 token 预估，多计为安全方向（预扣宁高勿低）。
     */
    private static int countReferenceImages(MediaImageGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        int count = 0;
        if (CharSequenceUtil.isNotBlank(request.getReferenceImageUrl())) {
            count++;
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            count += sizeOfList(options.get("images"));
            count += sizeOfList(options.get("referenceImages"));
        }
        return count;
    }

    /** 取 List 大小，非 List 返回 0。 */
    private static int sizeOfList(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    /**
     * 解析预期输出张数。
     */
    private static int resolveExpectedImageCount(MediaImageGenerateRequest request) {
        if (request == null) {
            return 1;
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            Object forceSingle = options.get("force_single");
            if (forceSingle != null && Boolean.parseBoolean(String.valueOf(forceSingle))) {
                return 1;
            }
        }
        Integer explicit = request.getExpectedImageCount();
        if (explicit != null && explicit > 0) {
            return Math.min(explicit, IMAGE_EXPECTED_COUNT_HARD_CAP);
        }
        if (options != null) {
            Object n = options.get("n");
            if (n != null) {
                int parsed = toInt(n);
                if (parsed > 0) {
                    return Math.min(parsed, IMAGE_EXPECTED_COUNT_HARD_CAP);
                }
            }
        }
        return 1;
    }

    /**
     * 解析图片生成模式。
     * jimeng-image-ultra 固定 UPSCALE（按真实 modelCode 判定）；其余按三处来源判断是否存在参考图。
     */
    private static String resolveGenerateMode(MediaImageGenerateRequest request, String effectiveModelCode) {
        if (request == null) {
            return "TEXT_TO_IMAGE";
        }
        String modelCode = CharSequenceUtil.isNotBlank(effectiveModelCode)
                ? effectiveModelCode : request.getModelName();
        if (CharSequenceUtil.isNotBlank(modelCode) && "jimeng-image-ultra".equalsIgnoreCase(modelCode)) {
            return "UPSCALE";
        }
        // 参考图来源：顶层 referenceImageUrl、options.images、options.referenceImages
        if (CharSequenceUtil.isNotBlank(request.getReferenceImageUrl())) {
            return "IMAGE_EDIT";
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            if (hasNonEmptyList(options.get("images")) || hasNonEmptyList(options.get("referenceImages"))) {
                return "IMAGE_EDIT";
            }
        }
        return "TEXT_TO_IMAGE";
    }

    private static boolean hasNonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 从音频（TTS）生成请求提取计费参数。
     * 豆包 TTS 单价由 aid_ai_model.cost_credits（FIXED）或 billing_rule_json（SKU）决定；
     * 此处只为 SKU 模式预留文本长度字段 textLength / chars / ttsChars，便于未来按字数定价；
     * FIXED 模式不会读这些字段。
     */
    public static BillingInput fromAudioRequest(MediaAudioGenerateRequest request) {
        Map<String, Object> params = new HashMap<>();
        int textLen = request != null && CharSequenceUtil.isNotBlank(request.getTtsText())
                ? request.getTtsText().length()
                : 0;
        // TTS 文本字符硬上限
        if (textLen > TTS_TEXT_HARD_CAP_CHARS) {
            textLen = TTS_TEXT_HARD_CAP_CHARS;
        }
        params.put("textLength", textLen);
        params.put("chars", textLen);
        params.put("ttsChars", textLen);
        if (request != null && CharSequenceUtil.isNotBlank(request.getVoiceCode())) {
            params.put("voiceCode", request.getVoiceCode());
        }
        if (request != null && CharSequenceUtil.isNotBlank(request.getLanguage())) {
            params.put("language", request.getLanguage());
        }
        return new BillingInput("AUDIO", params);
    }

    /**
     * 从视频生成请求提取计费参数。
     */
    public static BillingInput fromVideoRequest(MediaVideoGenerateRequest request) {
        Map<String, Object> params = new HashMap<>();
        // 时长（秒）：加硬上限避免前端塞超大 duration 打爆结算
        int rawDuration = request.getDurationSeconds() != null ? request.getDurationSeconds() : 5;
        if (rawDuration < 1) {
            rawDuration = 5;
        }
        // 多帧视频（Vidu multiframe 等）：durationSeconds 是"每段"时长，真实出片长度 = 段数 × 每段时长，
        // 计费必须按总时长，否则 9 段视频只按 1 段扣费
        int segments = countMultiFrameSegments(request.getOptions());
        if (segments > 1) {
            rawDuration = rawDuration * segments;
        }
        if (rawDuration > VIDEO_DURATION_HARD_CAP_SECONDS) {
            rawDuration = VIDEO_DURATION_HARD_CAP_SECONDS;
        }
        params.put("duration", rawDuration);
        // 分辨率：显式传入优先，并做档位规范化（1080P→1080p），保证与 SKU match 值一致；
        // 未传时从宽高比推断（默认 720P，SKU 匹配已忽略大小写）
        String resolution = extractFromOptions(request.getOptions(), "resolution");
        if (CharSequenceUtil.isNotBlank(resolution)) {
            String tier = ResolutionUtil.parseTier(resolution);
            params.put("resolution", CharSequenceUtil.isNotBlank(tier) ? tier : resolution.trim());
        } else {
            params.put("resolution", ResolutionUtil.inferVideoResolution(request.getAspectRatio()));
        }
        // 音画同出标记（Vidu 视频 SKU 维度之一）：优先取 DTO.audio，其次 options.audio；缺省 false。
        // 音画同出档位通常更贵，预扣方向宁高勿低，仅在显式开启时记 true。
        Boolean audio = request.getAudio();
        if (audio == null) {
            String optAudio = extractFromOptions(request.getOptions(), "audio");
            if (CharSequenceUtil.isNotBlank(optAudio)) {
                audio = Boolean.parseBoolean(optAudio.trim());
            }
        }
        params.put("audio", Boolean.TRUE.equals(audio));
        // 输入媒体计费参数：参考图张数 + 输入视频段数/秒数（供 inputPricing 附加费计算，未配置价的模型不受影响）
        params.put("referenceImageCount", countVideoInputImages(request));
        params.put("inputVideoCount", countInputVideos(request.getOptions()));
        params.put("inputVideoSeconds", extractInputVideoSeconds(request.getOptions()));
        // 生成模式：有图片则为图生视频
        params.put("generateMode",
                CharSequenceUtil.isNotBlank(request.getImageUrl()) ? "IMAGE_TO_VIDEO" : "TEXT_TO_VIDEO");
        // 从options中提取额外的generateMode覆盖（仅允许白名单值）
        // 限制 generateMode 只能是合法枚举，防止前端伪造任意字符串命中更便宜的 SKU
        String mode = extractFromOptions(request.getOptions(), "generateMode");
        if (CharSequenceUtil.isNotBlank(mode)
                && ("TEXT_TO_VIDEO".equalsIgnoreCase(mode)
                        || "IMAGE_TO_VIDEO".equalsIgnoreCase(mode)
                        || "EDGE_TO_VIDEO".equalsIgnoreCase(mode)
                        || "MULTI_TO_VIDEO".equalsIgnoreCase(mode))) {
            params.put("generateMode", mode.toUpperCase());
        }
        return new BillingInput("VIDEO", params);
    }

    /**
     * 统计视频请求中的输入图片张数：首帧 imageUrl + 尾帧 lastFrameImageUrl + options 参考图列表。
     * 仅用于 inputPricing 图片附加费计算，多计为安全方向（预扣宁高勿低，失败全额退）。
     */
    private static int countVideoInputImages(MediaVideoGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        int count = 0;
        if (CharSequenceUtil.isNotBlank(request.getImageUrl())) {
            count++;
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            Object lastFrame = options.get("lastFrameImageUrl");
            if (lastFrame != null && CharSequenceUtil.isNotBlank(String.valueOf(lastFrame))) {
                count++;
            }
            count += sizeOfList(options.get("referenceImages"));
            count += sizeOfList(options.get("images"));
        }
        return count;
    }

    /**
     * 统计输入视频段数：options.video_url / videoUrl 单段 + referenceVideos / videos 列表。
     */
    private static int countInputVideos(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String key : new String[]{"video_url", "videoUrl", "inputVideoUrl"}) {
            Object v = options.get(key);
            if (v != null && CharSequenceUtil.isNotBlank(String.valueOf(v))) {
                count++;
                break;
            }
        }
        count += sizeOfList(options.get("referenceVideos"));
        count += sizeOfList(options.get("videos"));
        return count;
    }

    /**
     * 提取输入视频总秒数（业务层显式传入 options.inputVideoSeconds / referenceVideoSeconds）。
     * 未传返回 0；有输入视频但秒数未知时，计费层按 inputPricing.video.maxSeconds 预扣兜底。
     */
    private static int extractInputVideoSeconds(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        for (String key : new String[]{"inputVideoSeconds", "referenceVideoSeconds", "videoSeconds"}) {
            Object v = options.get(key);
            if (v != null) {
                int parsed = toInt(v);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    /**
     * 统计多帧视频段数：options 携带 imageSettings / keyImages 等关键帧列表时，
     * 列表长度即段数（每个关键帧对应一段视频）；非多帧请求返回 1。
     */
    private static int countMultiFrameSegments(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 1;
        }
        // 与 ViduVideoProviderClient 组装多帧请求体读取的 options 键保持一致
        String[] keys = {"image_settings", "imageSettings", "key_images", "keyImages"};
        for (String key : keys) {
            Object raw = options.get(key);
            if (raw instanceof List<?> list && !list.isEmpty()) {
                return list.size();
            }
        }
        return 1;
    }

    /**
     * 从文本生成请求提取计费参数。
     */
    public static BillingInput fromTextRequest(MediaTextGenerateRequest request) {
        Map<String, Object> params = new HashMap<>();
        // 输入字数：统计prompt和messages的content长度
        int inputChars = 0;
        if (CharSequenceUtil.isNotBlank(request.getPrompt())) {
            inputChars += request.getPrompt().length();
        }
        if (CollectionUtil.isNotEmpty(request.getMessages())) {
            for (MediaTextGenerateRequest.TextMessageItem msg : request.getMessages()) {
                if (msg.getContent() != null) {
                    inputChars += msg.getContent().length();
                }
            }
        }
        // inputChars 硬上限，避免结算估算溢出
        if (inputChars > PROMPT_INPUT_HARD_CAP_CHARS) {
            inputChars = PROMPT_INPUT_HARD_CAP_CHARS;
        }
        params.put("inputChars", inputChars);

        // 预冻结统一换算：5字=4token → tokens = ceil(chars * 4 / 5)
        int estimatedInputTokens = BillingConstants.charsToTokens(inputChars);
        params.put("inputTokens", estimatedInputTokens);

        // 预估输出：优先 estimatedOutputChars（字符），其次 max_tokens（token）
        int estimatedOutputChars = BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
        int estimatedOutputTokens = BillingConstants.charsToTokens(estimatedOutputChars);
        Object estOutputCharsObj = extractFromOptionsObj(request.getOptions(), "estimatedOutputChars");
        if (estOutputCharsObj != null) {
            // estimatedOutputChars 是字符单位，用统一公式转 token
            estimatedOutputChars = toInt(estOutputCharsObj);
            // 硬上限保护，避免前端塞超大 estimatedOutputChars 引发结算溢出
            if (estimatedOutputChars > TEXT_ESTIMATED_OUTPUT_CHARS_HARD_CAP) {
                estimatedOutputChars = TEXT_ESTIMATED_OUTPUT_CHARS_HARD_CAP;
            }
            if (estimatedOutputChars < 0) {
                estimatedOutputChars = BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
            }
            estimatedOutputTokens = BillingConstants.charsToTokens(estimatedOutputChars);
        } else {
            Object maxTokens = extractFromOptionsObj(request.getOptions(), "max_tokens");
            if (maxTokens != null) {
                // max_tokens 已经是 token 单位，直接使用
                estimatedOutputTokens = toInt(maxTokens);
                if (estimatedOutputTokens > TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP) {
                    estimatedOutputTokens = TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP;
                }
                if (estimatedOutputTokens < 0) {
                    estimatedOutputTokens = BillingConstants.charsToTokens(BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS);
                }
            }
        }
        params.put("outputTokens", estimatedOutputTokens);
        // 兼容旧 SKU 匹配：totalChars 仍用字符
        params.put("estimatedOutputChars", estimatedOutputChars);
        params.put("totalChars", inputChars + estimatedOutputChars);
        // 多模态输入图张数（options.images / referenceImages）：供 inputPricing 图片附加费计算；
        // 常规 Token 模型输入图已折算进 token，不配置 inputPricing 时本参数不产生费用
        Map<String, Object> textOptions = request.getOptions();
        if (textOptions != null) {
            int imageCount = sizeOfList(textOptions.get("images")) + sizeOfList(textOptions.get("referenceImages"));
            if (imageCount > 0) {
                params.put("referenceImageCount", imageCount);
            }
        }

        return new BillingInput("TEXT", params);
    }

    /**
     * 从options Map中提取字符串值
     */
    private static String extractFromOptions(Map<String, Object> options, String key) {
        if (options == null) {
            return null;
        }
        Object val = options.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    /**
     * 从options Map中提取Object值
     */
    private static Object extractFromOptionsObj(Map<String, Object> options, String key) {
        if (options == null) {
            return null;
        }
        return options.get(key);
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
        }
    }

    /**
     * 向上取整整数除法：ceil(a / b)。
     * 预扣阶段必须向上取整，避免系统性少扣。
     */
    private static int ceilDiv(int a, int b) {
        if (b <= 0) {
            return a;
        }
        return (a + b - 1) / b;
    }
}
