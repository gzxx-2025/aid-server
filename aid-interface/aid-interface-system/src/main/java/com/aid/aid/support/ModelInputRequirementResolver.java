package com.aid.aid.support;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型「输入要求」统一推导器。
 *
 * <p>背景：generate_mode 是单值字段（如 text_to_image），无法表达"既支持文生图又支持图生图"；
 * 真实的输入要求散落在 capability_json.sceneRules / minReferenceImages / maxReferenceImages /
 * supports_image_input 等多处。本工具把它们收敛成一个可筛选、可展示的标签，
 * 后台管理（模型列表 / 模型池选择器）与 C 端模型列表共用同一推导逻辑，保证口径一致。</p>
 *
 * <p>四个取值：</p>
 * <ul>
 *   <li>{@link #TEXT_ONLY}      —— 纯文本输入即可出结果（文生图 / 文生视频 / LLM / TTS）</li>
 *   <li>{@link #IMAGE_OPTIONAL} —— 图片可选：不传图走文生，传图走图生（如 即梦4.0、gemini image）</li>
 *   <li>{@link #IMAGE_REQUIRED} —— 图片必传：不传图无法生成（如 图片编辑 / 图片高清 / 图生视频专用模型）</li>
 *   <li>{@link #VIDEO_REQUIRED} —— 视频必传：输入必须含视频（如 对口型 / 视频编辑）</li>
 * </ul>
 *
 * @author 视觉AID
 */
@Slf4j
public final class ModelInputRequirementResolver {

    /** 纯文本输入（无需图片/视频） */
    public static final String TEXT_ONLY = "text_only";
    /** 图片可选（传图走图生，不传走文生） */
    public static final String IMAGE_OPTIONAL = "image_optional";
    /** 图片必传（无图无法生成） */
    public static final String IMAGE_REQUIRED = "image_required";
    /** 视频必传（输入必须含视频） */
    public static final String VIDEO_REQUIRED = "video_required";

    /** 模型大类：文本 */
    private static final String TYPE_TEXT = "text";
    /** 模型大类：图片 */
    private static final String TYPE_IMAGE = "image";
    /** 模型大类：视频 */
    private static final String TYPE_VIDEO = "video";
    /** 模型大类：音频 */
    private static final String TYPE_AUDIO = "audio";

    /** 生成模式：视频生视频（对口型/视频编辑等，输入必须含视频） */
    private static final String MODE_VIDEO_TO_VIDEO = "video_to_video";
    /** 生成模式：文生图 */
    private static final String MODE_TEXT_TO_IMAGE = "text_to_image";
    /** 生成模式：文生视频 */
    private static final String MODE_TEXT_TO_VIDEO = "text_to_video";

    /** JSON 解析器（线程安全，可静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelInputRequirementResolver() {
    }

    /**
     * 推导模型的输入要求标签。
     *
     * @param modelType          模型大类（text/image/video/audio）
     * @param generateMode       生成模式细分（可空，作为 sceneRules 缺失时的兜底）
     * @param capabilityJson     capability_json 原文（可空）
     * @param supportsImageInput 是否支持图片输入（可空）
     * @return 输入要求标签（见类注释四个常量），入参无法判断时按大类返回保守值
     */
    public static String resolve(String modelType, String generateMode, String capabilityJson,
                                 Boolean supportsImageInput) {
        // 文本大模型：支持图片输入的多模态 LLM 归为图片可选，否则纯文本
        if (StrUtil.equals(TYPE_TEXT, modelType)) {
            return Boolean.TRUE.equals(supportsImageInput) ? IMAGE_OPTIONAL : TEXT_ONLY;
        }
        // TTS 配音：输入永远是文本
        if (StrUtil.equals(TYPE_AUDIO, modelType)) {
            return TEXT_ONLY;
        }

        CapabilitySignal signal = parseCapability(capabilityJson);

        if (StrUtil.equals(TYPE_IMAGE, modelType)) {
            return resolveImage(generateMode, supportsImageInput, signal);
        }
        if (StrUtil.equals(TYPE_VIDEO, modelType)) {
            return resolveVideo(generateMode, signal);
        }
        // 未知大类：按纯文本兜底（不影响筛选主链路）
        return TEXT_ONLY;
    }

    /** 图片模型推导：sceneRules 优先，minReferenceImages>=1 强制必传，缺失时按 generateMode 兜底。 */
    private static String resolveImage(String generateMode, Boolean supportsImageInput, CapabilitySignal signal) {
        // 显式禁止参考图（maxReferenceImages=0）或不支持图片输入 → 纯文生图
        if (signal.maxRef != null && signal.maxRef == 0) {
            return TEXT_ONLY;
        }
        if (Boolean.FALSE.equals(supportsImageInput)) {
            return TEXT_ONLY;
        }
        // 显式声明最少参考图张数 >=1 → 图片必传
        if (signal.minRef != null && signal.minRef >= 1) {
            return IMAGE_REQUIRED;
        }
        boolean hasT2i = signal.scenes.contains("textToImage");
        boolean hasI2i = signal.scenes.contains("imageToImage");
        if (hasT2i && hasI2i) {
            return IMAGE_OPTIONAL;
        }
        if (hasI2i) {
            return IMAGE_REQUIRED;
        }
        if (hasT2i) {
            return TEXT_ONLY;
        }
        // sceneRules 缺失：按 generateMode 兜底
        if (StrUtil.equals(MODE_TEXT_TO_IMAGE, generateMode)) {
            return Boolean.TRUE.equals(supportsImageInput) ? IMAGE_OPTIONAL : TEXT_ONLY;
        }
        // image_to_image / image_edit / image_upscale 等均需要输入图
        return StrUtil.isBlank(generateMode) ? IMAGE_OPTIONAL : IMAGE_REQUIRED;
    }

    /** 视频模型推导：videoToVideo 优先级最高，其次 sceneRules 组合，缺失时按 generateMode 兜底。 */
    private static String resolveVideo(String generateMode, CapabilitySignal signal) {
        // 视频生视频（对口型/视频编辑）：输入必须含视频
        if (signal.scenes.contains("videoToVideo") || StrUtil.equals(MODE_VIDEO_TO_VIDEO, generateMode)) {
            return VIDEO_REQUIRED;
        }
        boolean hasTextScene = signal.scenes.contains("textToVideo");
        // 需要输入图的场景：图生 / 首尾帧 / 参考图 / 多帧
        boolean hasImageScene = signal.scenes.contains("imageToVideo")
                || signal.scenes.contains("startEndToVideo")
                || signal.scenes.contains("referenceToVideo")
                || signal.scenes.contains("multiFrame");
        // 显式声明最少参考图张数 >=1 → 图片必传
        if (signal.minRef != null && signal.minRef >= 1) {
            return IMAGE_REQUIRED;
        }
        if (hasTextScene && hasImageScene) {
            return IMAGE_OPTIONAL;
        }
        if (hasImageScene) {
            return IMAGE_REQUIRED;
        }
        if (hasTextScene) {
            return TEXT_ONLY;
        }
        // sceneRules 缺失：按 generateMode 兜底
        if (StrUtil.equals(MODE_TEXT_TO_VIDEO, generateMode)) {
            return TEXT_ONLY;
        }
        // image_to_video / start_end_to_video / reference_to_video / multi_frame 等均需要输入图
        return StrUtil.isBlank(generateMode) ? IMAGE_OPTIONAL : IMAGE_REQUIRED;
    }

    /** 从 capability_json 提取推导信号（场景键集合 + 最少/最多参考图张数），解析失败返回空信号。 */
    private static CapabilitySignal parseCapability(String capabilityJson) {
        CapabilitySignal signal = new CapabilitySignal();
        if (StrUtil.isBlank(capabilityJson)) {
            return signal;
        }
        try {
            JsonNode root = MAPPER.readTree(capabilityJson);
            JsonNode sceneRules = root.path("sceneRules");
            if (sceneRules.isObject()) {
                sceneRules.fieldNames().forEachRemaining(signal.scenes::add);
            }
            JsonNode minRefNode = root.path("minReferenceImages");
            if (minRefNode.isInt()) {
                signal.minRef = minRefNode.intValue();
            }
            JsonNode maxRefNode = root.path("maxReferenceImages");
            if (maxRefNode.isInt()) {
                signal.maxRef = maxRefNode.intValue();
            }
        } catch (Exception e) {
            // 能力 JSON 非法不阻断主链路，仅打日志并按空信号兜底
            log.warn("解析 capability_json 失败，按空能力兜底: {}", e.getMessage());
        }
        return signal;
    }

    /** capability_json 中与输入要求推导相关的信号载体。 */
    private static final class CapabilitySignal {
        /** sceneRules 场景键集合（textToImage/imageToImage/textToVideo/...） */
        private final Set<String> scenes = new HashSet<>();
        /** 最少参考图张数（缺省 null） */
        private Integer minRef;
        /** 最多参考图张数（缺省 null） */
        private Integer maxRef;
    }
}
