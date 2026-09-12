package com.aid.model.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 模型能力配置VO。
 *
 * @author 视觉AID
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /** 可灵视频请求场景标识，C 端据此选择对应素材输入面板。 */
    private String klingScenario;

    /** 视频生成细分场景，供同一上游模型的多个平台模型编码区分请求结构。 */
    private String videoScenario;

    /** Seedance Generations 当前模型允许的显式任务类型。 */
    private List<String> seedanceTaskTypeOptions;

    /** 当前模型允许的业务场景白名单。 */
    private List<String> allowedScenes;

    /** 视频输出容器格式枚举（如 mp4/mov）。 */
    private List<String> outputFormatOptions;

    /** 默认视频输出容器格式。 */
    private String defaultOutputFormat;

    /** 视频输出帧率枚举；仅描述上游可选或固定输出，不与参考视频输入帧率混用。 */
    private List<Integer> outputFpsOptions;

    /** 默认视频输出帧率。 */
    private Integer defaultOutputFps;

    /** 提示词最大字符数。 */
    private Integer maxPromptCharacters;

    /** 提示词包含中日韩文字时的最大字符数；未配置时回退 maxPromptCharacters。 */
    private Integer maxPromptCharactersCjk;

    /** 可灵场景允许的音频模式枚举，如 off、native、original。 */
    private List<String> audioModeOptions;

    /** 是否支持「音画同出」（视频与声音一起生成）；C 端据此显示音画同出开关 */
    private Boolean supportsAudio;

    /** 未显式传音频开关时的服务端默认值；缺省兼容历史行为（supportsAudio=true 时默认开启）。 */
    private Boolean defaultAudio;

    /** 是否支持外部参考音频输入。 */
    private Boolean supportsReferenceAudio;

    /** 参考音频是否必须与图片或视频素材同时提交。 */
    private Boolean referenceAudioRequiresVisualInput;

    /** 单次最多参考音频数量。 */
    private Integer maxReferenceAudios;

    /** 单段参考音频最短时长，秒。 */
    private java.math.BigDecimal referenceAudioMinDurationSeconds;

    /** 单段参考音频最长时长，秒。 */
    private java.math.BigDecimal referenceAudioMaxDurationSeconds;

    /** 单次参考音频总时长上限，秒。 */
    private java.math.BigDecimal referenceAudioMaxTotalDurationSeconds;

    /** 支持的参考音频格式。 */
    private List<String> referenceAudioFormats;

    /** 单个参考音频最大文件大小（MB）。 */
    private java.math.BigDecimal referenceAudioMaxFileSizeMb;

    /**
     * 音画同出开关默认值；视频模型必返。
     * 与 defaultAudio 的生效值一致；未声明 defaultAudio 时兼容历史行为。
     */
    private Boolean defaultGenerateAudio;

    /** 是否支持「背景音乐」；C 端据此显示 BGM 开关 */
    private Boolean supportsBgm;

    /** 是否支持指定音色（voice_id）；仅在音画同出开启时有意义 */
    private Boolean supportsVoiceId;

    /** 音频类型枚举（all/speech_only/sound_effect_only），仅在音画同出开启时可选 */
    private List<String> audioTypes;

    /** 是否支持可灵主体元素输入。 */
    private Boolean supportsElements;

    /** 可灵主体元素数量上限。 */
    private Integer maxElements;

    /** 可灵主体元素是否必须声明类型。 */
    private Boolean elementTypeRequired;

    /** 是否支持参考视频或待编辑视频输入。 */
    private Boolean supportsVideoInput;

    /** 单次最多允许的参考视频数量。 */
    private Integer maxReferenceVideos;

    /** 单次图片、视频与音频参考素材合计上限。 */
    private Integer maxReferenceMaterials;

    /** 参考视频单段/合计时长及文件格式约束。 */
    private java.math.BigDecimal referenceVideoMinDurationSeconds;
    private java.math.BigDecimal referenceVideoMaxDurationSeconds;
    private java.math.BigDecimal referenceVideoMaxTotalDurationSeconds;
    /** 输入视频总时长与输出视频时长之和上限，秒。 */
    private java.math.BigDecimal maxInputOutputVideoDurationSeconds;
    private List<String> referenceVideoFormats;
    private java.math.BigDecimal referenceVideoMaxFileSizeMb;
    private Integer referenceVideoMinDimensionPixels;
    private Integer referenceVideoMaxDimensionPixels;
    private Long referenceVideoMinPixels;
    private Long referenceVideoMaxPixels;
    private Double referenceVideoMinAspectRatio;
    private Double referenceVideoMaxAspectRatio;
    private Double referenceVideoMinFps;
    private Double referenceVideoMaxFps;

    /** 可灵参考视频场景下的图片、主体组合约束。 */
    private Map<String, Object> referenceVideoRules;

    /** 是否支持指定音色控制。 */
    private Boolean supportsVoiceControl;

    /** 音频模型的业务操作：synthesis、design 或 clone。 */
    private String audioOperation;

    /** TTS 文本和音色是否必填。 */
    private Boolean ttsTextRequired;
    private Boolean ttsVoiceRequired;

    /** 可选的预置音色、输出格式和采样率。 */
    private List<String> builtInVoiceOptions;
    private List<String> audioFormatOptions;
    private String defaultAudioFormat;
    private List<Integer> audioSampleRateOptions;
    private Integer defaultAudioSampleRate;

    /** 语音合成专属能力与控制范围。 */
    private Boolean supportsAudioStreaming;
    private Boolean supportsTimestamp;
    private Integer speechRateMin;
    private Integer speechRateMax;
    private Integer loudnessRateMin;
    private Integer loudnessRateMax;
    private Integer pitchMin;
    private Integer pitchMax;
    private List<String> emotionOptions;
    private Boolean supportsEmotionScale;

    /** 参考样本型音色的输入约束。 */
    private Boolean voiceSampleRequired;
    private List<String> voiceSampleFormats;
    private java.math.BigDecimal voiceSampleMaxFileSizeMb;

    /** 是否允许用户自定义宽高（脱离 sizeOptions），当前项目策略统一为 false */
    private Boolean allowCustomWH;

    /**
     * 场景规则：按"使用场景"提供差异化能力开关。
     * key 取值：textOnly / textToImage / imageToImage / textToVideo / imageToVideo；
     * value 可声明 supportsAspectRatio / supportsSizePreset / supportsDuration / aspectRatioFollowInput，
     * 也可按场景覆盖 sizeOptions / defaultSize / aspectRatioOptions / defaultAspectRatio。
     * 多图能力（含是否支持、上限张数）唯一权威口径是顶层 supportsMultiImageInput + maxOutputCount，
     * sceneRules 内不再重复声明 supportsMultiImageInput / maxImageCount。
     */
    private Map<String, Map<String, Object>> sceneRules;

    /**
     * 参考图最大张数（图片 / 视频参考图治理规范）。四态语义：。
     */
    private Integer maxReferenceImages;

    /**
     * 参考图最少张数（必须带图的模型配 N&gt;=1，如图生视频/首尾帧/参考生图）。
     * 0 或缺省 = 不要求带图；C 端在触发任务前按此值做前置校验与界面提示。
     */
    private Integer minReferenceImages;

    /** H3 等多模态模型的参考图片文件约束。 */
    private List<String> referenceImageFormats;
    private java.math.BigDecimal referenceImageMaxFileSizeMb;
    private Integer referenceImageMinDimensionPixels;
    private Integer referenceImageMaxDimensionPixels;
    private Double referenceImageMinAspectRatio;
    private Double referenceImageMaxAspectRatio;

    /** 单张参考图片的总像素范围。 */
    private Long referenceImageMinPixels;
    private Long referenceImageMaxPixels;

    /** 同次请求输入媒体总文件大小（MB）。 */
    private java.math.BigDecimal maxInputMediaTotalFileSizeMb;

    /** 外部媒体 URL 的最大长度；缺失表示厂商未声明或当前协议不限制。 */
    private Integer inputMediaMaxUrlLength;

    /** 官方接口是否支持 Base64 传图（能力位，依官方文档配置；false/缺省时启用开关不可选） */
    private Boolean supportsBase64Image;

    /** 是否启用 Base64 传图（运营开关，仅 supportsBase64Image=true 时生效；开启后参考图下载转 Base64 下发） */
    private Boolean base64ImageEnabled;

    /** 文本模型允许的输入与输出模态，取值为 TEXT、IMAGE、VIDEO、AUDIO、DOCUMENT。 */
    private List<String> inputModalities;
    private List<String> outputModalities;

    /** 文本模型各输入模态能力及单次数量上限。 */
    private Boolean supportsImageInput;
    private Boolean supportsAudioInput;
    private Boolean supportsDocumentInput;
    private Integer maxInputImages;
    private Integer maxInputVideos;
    private Integer maxInputAudios;
    private Integer maxInputDocuments;

    /** 文本模型多模态文件约束。 */
    private List<String> inputImageFormats;
    private List<String> inputVideoFormats;
    private List<String> inputAudioFormats;
    private List<String> inputDocumentFormats;
    private java.math.BigDecimal maxInputImageFileSizeMb;
    private java.math.BigDecimal maxInputVideoFileSizeMb;
    private java.math.BigDecimal maxInputAudioFileSizeMb;
    private java.math.BigDecimal maxInputDocumentFileSizeMb;
    private java.math.BigDecimal minInputVideoDurationSeconds;
    private java.math.BigDecimal maxInputVideoDurationSeconds;
    private java.math.BigDecimal minInputAudioDurationSeconds;
    private java.math.BigDecimal maxInputAudioDurationSeconds;
    private java.math.BigDecimal maxInputVideoTotalDurationSeconds;
    private java.math.BigDecimal maxInputAudioTotalDurationSeconds;
    private Integer maxInputDocumentPages;

    /** 文本多模态模型的图片与视频画面硬约束。 */
    private Integer inputImageMinDimensionPixels;
    private Integer inputImageMaxDimensionPixels;
    private Long inputImageMinPixels;
    private Long inputImageMaxPixels;
    private Double inputImageMinAspectRatio;
    private Double inputImageMaxAspectRatio;
    private Integer inputVideoMinDimensionPixels;
    private Integer inputVideoMaxDimensionPixels;
    private Long inputVideoMinPixels;
    private Long inputVideoMaxPixels;
    private Double inputVideoMinAspectRatio;
    private Double inputVideoMaxAspectRatio;
    private Double inputVideoMinFps;
    private Double inputVideoMaxFps;

    /** 图片达到指定数量后收紧单边尺寸，用于原厂公布的分段硬约束。 */
    private Integer inputImageHighCountThreshold;
    private Integer inputImageHighCountMaxDimensionPixels;

    /** 非文本内容允许出现的消息角色；空值表示当前协议没有额外角色限制。 */
    private List<String> inputMediaAllowedMessageRoles;

    /** 文本上下文与输出上限。 */
    private Integer contextWindowTokens;
    private Integer maxOutputTokens;
    private Long minOutputPixels;
    private Long maxOutputPixels;
    private Double minOutputAspectRatio;
    private Double maxOutputAspectRatio;

    /** 文本模型思考能力与统一调用参数。 */
    private Boolean supportsReasoning;
    private Boolean supportsReasoningDisable;
    private Boolean supportsReasoningContent;
    /** 旧能力字段，读取历史配置时继续兼容。 */
    private Boolean returnsReasoningContent;
    private Boolean supportsReasoningBudget;
    private Boolean defaultReasoningEnabled;
    private Integer defaultReasoningBudgetTokens;
    private Integer maxReasoningBudgetTokens;
    private String defaultReasoningLevel;
    private List<String> allowedReasoningLevels;
    private String reasoningApiStyle;

    /** 文本协议的通用运行能力；缺失表示沿用历史兼容行为，显式 false 才拒绝对应请求。 */
    private Boolean supportsStreaming;
    private Boolean supportsToolCalling;
    private Boolean supportsChatPrefix;
    private Boolean supportsStructuredOutput;
    private Boolean supportsContextCaching;
    private Boolean supportsBuiltinTools;
}

