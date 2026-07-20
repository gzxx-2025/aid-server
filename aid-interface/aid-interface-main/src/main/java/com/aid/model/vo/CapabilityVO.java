package com.aid.model.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 模型能力配置VO。
 *
 * @author 视觉AID
 */
@Data
public class CapabilityVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 该模型支持的规格档位枚举（图片 1K/2K/4K，视频 720P/1080P） */
    private List<String> sizeOptions;

    /** 默认选中的规格档位，与 AiModelVO.defaultSizeCode 同义 */
    private String defaultSize;

    /** 该模型支持的比例枚举（"宽:高" 字符串） */
    private List<String> aspectRatioOptions;

    /** 默认选中的比例，与 AiModelVO.defaultAspectRatio 同义 */
    private String defaultAspectRatio;

    /** 视频时长枚举（秒），仅 video 模型有效 */
    private List<Integer> durationOptions;

    /** 默认时长（秒），仅 video 模型有效 */
    private Integer defaultDurationSeconds;
    /** 是否支持「音画同出」（视频与声音一起生成）；C 端据此显示音画同出开关 */
    private Boolean supportsAudio;

    /** 是否支持「背景音乐」；C 端据此显示 BGM 开关 */
    private Boolean supportsBgm;

    /** 是否支持指定音色（voice_id）；仅在音画同出开启时有意义 */
    private Boolean supportsVoiceId;

    /** 音频类型枚举（all/speech_only/sound_effect_only），仅在音画同出开启时可选 */
    private List<String> audioTypes;

    /** 是否允许用户自定义宽高（脱离 sizeOptions），当前项目策略统一为 false */
    private Boolean allowCustomWH;

    /**
     * 场景规则：按"使用场景"提供差异化能力开关。
     * key 取值：textOnly / textToImage / imageToImage / textToVideo / imageToVideo；
     * value 取值：supportsAspectRatio / supportsSizePreset / supportsDuration / aspectRatioFollowInput。
     * 多图能力（含是否支持、上限张数）唯一权威口径是顶层 supportsMultiImageInput + maxOutputCount，
     * sceneRules 内不再重复声明 supportsMultiImageInput / maxImageCount。
     */
    private Map<String, Map<String, Boolean>> sceneRules;

    /**
     * 参考图最大张数（图片 / 视频参考图治理规范）。四态语义：。
     */
    private Integer maxReferenceImages;

    /**
     * 参考图最少张数（必须带图的模型配 N&gt;=1，如图生视频/首尾帧/参考生图）。
     * 0 或缺省 = 不要求带图；C 端在触发任务前按此值做前置校验与界面提示。
     */
    private Integer minReferenceImages;

    /** 官方接口是否支持 Base64 传图（能力位，依官方文档配置；false/缺省时启用开关不可选） */
    private Boolean supportsBase64Image;

    /** 是否启用 Base64 传图（运营开关，仅 supportsBase64Image=true 时生效；开启后参考图下载转 Base64 下发） */
    private Boolean base64ImageEnabled;
}

