package com.aid.media.constants;

import java.util.Set;

/**
 * 平台内部 options 键统一清单：仅供业务层与 Provider 之间传递上下文，禁止透传任何上游厂商。
 *
 * @author 视觉AID
 */
public final class MediaInternalOptionKeys {

    private MediaInternalOptionKeys() {
    }

    /** 分镜出图扇入上下文（含原始带占位 prompt、参考图映射、业务参数快照） */
    public static final String SBZ_IMAGE_GEN_CTX = "sbzImageGenCtx";

    /** 分镜出片扇入上下文 */
    public static final String SBZ_VIDEO_GEN_CTX = "sbzVideoGenCtx";

    /** 富化参考图清单（Agnes 渲染策略等本地消费） */
    public static final String REFERENCE_MANIFEST = "referenceManifest";

    /** 业务层参考图列表（各 Provider 显式消费后注入官方字段） */
    public static final String REFERENCE_IMAGES = "referenceImages";

    /** 平台计费语义：强制单图 */
    public static final String FORCE_SINGLE = "force_single";

    /** 平台计费语义：预期出图张数 */
    public static final String EXPECTED_IMAGE_COUNT = "expectedImageCount";

    /** 文本预估输出字数（计费预估用） */
    public static final String ESTIMATED_OUTPUT_CHARS = "estimatedOutputChars";

    /** 文本输出字数硬上限（计费预估用） */
    public static final String MAX_OUTPUT_CHARS = "maxOutputChars";

    private static final Set<String> KEYS = Set.of(
            SBZ_IMAGE_GEN_CTX,
            SBZ_VIDEO_GEN_CTX,
            REFERENCE_MANIFEST,
            REFERENCE_IMAGES,
            FORCE_SINGLE,
            EXPECTED_IMAGE_COUNT,
            ESTIMATED_OUTPUT_CHARS,
            MAX_OUTPUT_CHARS);

    /**
     * 判定 options 键是否为平台内部键（不允许出现在上游请求体）。
     *
     * @param key options 键名
     * @return true 表示内部键，必须过滤
     */
    public static boolean isInternal(String key) {
        return key != null && KEYS.contains(key);
    }
}
