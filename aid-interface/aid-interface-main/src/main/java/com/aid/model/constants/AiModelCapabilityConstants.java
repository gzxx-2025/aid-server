package com.aid.model.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AI模型能力常量（图片 / 视频）
 * 用于后台校验、前端默认 fallback、capability_json 缺失时的兜底常量。
 * 所有列表统一使用大写或固定枚举值。
 *
 * @author 视觉AID
 */
public final class AiModelCapabilityConstants
{
    private AiModelCapabilityConstants() {}
    /** 文生图（纯提示词 → 图片） */
    public static final int IMAGE_MODEL_TYPE_TEXT_TO_IMAGE = 1;
    /** 图生图（提示词 + 参考图 → 新图片） */
    public static final int IMAGE_MODEL_TYPE_IMAGE_TO_IMAGE = 2;
    /** 图片高清（图片放大 / 超分辨率） */
    public static final int IMAGE_MODEL_TYPE_UPSCALE = 3;
    /** 图片编辑（指令 + 原图 → 编辑后图片） */
    public static final int IMAGE_MODEL_TYPE_EDIT = 4;
    /** 图片规格：仅允许 1K / 2K / 4K */
    public static final List<String> IMAGE_SIZE_OPTIONS = Collections.unmodifiableList(
            Arrays.asList("1K", "2K", "4K"));

    /** 图片比例：固定 11 档枚举，禁止自定义宽高 */
    public static final List<String> IMAGE_ASPECT_RATIO_OPTIONS = Collections.unmodifiableList(
            Arrays.asList("1:1", "2:3", "3:2", "3:4", "4:3", "7:9", "9:7", "9:16", "9:21", "16:9", "21:9"));

    /** 图片默认规格 */
    public static final String IMAGE_DEFAULT_SIZE = "2K";

    /** 图片默认比例 */
    public static final String IMAGE_DEFAULT_ASPECT_RATIO = "1:1";
    /** 视频规格：720P / 1080P */
    public static final List<String> VIDEO_SIZE_OPTIONS = Collections.unmodifiableList(
            Arrays.asList("720P", "1080P"));

    /** 视频比例：5 档常用比例 */
    public static final List<String> VIDEO_ASPECT_RATIO_OPTIONS = Collections.unmodifiableList(
            Arrays.asList("16:9", "9:16", "1:1", "4:3", "3:4"));

    /** 视频时长(秒)：5/10/15/30 */
    public static final List<Integer> VIDEO_DURATION_OPTIONS = Collections.unmodifiableList(
            Arrays.asList(5, 10, 15, 30));

    /** 视频默认规格 */
    public static final String VIDEO_DEFAULT_SIZE = "1080P";

    /** 视频默认比例 */
    public static final String VIDEO_DEFAULT_ASPECT_RATIO = "16:9";

    /** 视频默认时长(秒) */
    public static final Integer VIDEO_DEFAULT_DURATION_SECONDS = 5;
}
