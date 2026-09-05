package com.aid.media.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 文本生成（Chat Completions）请求体：与 C 端 POST JSON 对齐，禁止用 Map 承载业务参数。
 */
@Data
public class MediaTextGenerateRequest {

    /**
     * 指定模型名称（可选）：为空时走后端默认文本模型与协议路由；方舟场景一般为推理接入点 ID。
     */
    private String modelName;

    /** 项目ID（可选）：用于关联任务到具体项目 */
    private Long projectId;

    /** 剧集ID（可选）：电影模式为0 */
    private Long episodeId;

    /**
     * 单轮用户输入（可选）：与 messages 二选一或组合；当 messages 为空时必填，用于拼成单条 user 消息。
     */
    private String prompt;

    /**
     * 多轮对话消息列表（可选）：元素含 role、content；非空时与 prompt 组合策略为——先注入模型配置的 system（若有），再追加本列表，最后若仍有 prompt 再追加一条 user。
     */
    private List<TextMessageItem> messages;

    /**
     * 扩展参数（可选）：透传 temperature、max_tokens、top_p 等厂商字段，与图片 options 用法一致。
     */
    private Map<String, Object> options;

    /** 业务任务ID（可选）：用于关联触发本媒体任务的业务任务，如 aid_extract_task.id */
    private Long bizTaskId;

    /** 业务任务类型（可选）：如 extract，与 bizTaskId 配合定位具体业务表 */
    private String bizTaskType;

    /** 用户ID（可选）：MQ消费等无登录上下文场景由调用方显式传入 */
    private Long userId;

    /** 计费豁免标记（内部使用）：为 true 时跳过 prepareBilling/settleBilling/refundBilling，由外层任务统一计费 */
    private Boolean billingExempt;

    /**
     * 本次调用是否要求上游流式响应。该值属于调用策略，不从模型 extra_body 读取。
     * {@code generateTextStream} 要求为 true；{@code generateText} 缺省按 false 处理。
     */
    private Boolean stream;

    /** 本次调用是否开启模型思考；缺省关闭，不能由模型 extra_body 写死。 */
    private Boolean reasoningEnabled;

    /** 本次调用的思考档位，如 minimal、low、medium、high、max。 */
    private String reasoningLevel;

    /** 本次调用的思考 Token 预算；仅声明支持预算的模型生效。 */
    private Integer reasoningBudgetTokens;

    /** 是否把模型返回的思考增量交给调用方；不影响思考 Token 的计费统计。 */
    private Boolean includeReasoning;

    /**
     * 旧版非流式偏好，仅用于兼容已落库任务；新调用统一使用 {@link #stream}。
     */
    @Deprecated
    private Boolean preferNonStream;

    /**
     * 任务存档摘要（可选，业务方覆盖）：用于 aid_media_task.prompt 列存档展示。
     */
    private String taskPromptDigest;

    /**
     * 内部幂等调用ID：由父任务类型、父ID、父计费trace、业务阶段及序号共同生成；
     * 同一父周期重投必须保持不变，不允许用 prompt/requestHash 代替。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String callId;

    /** 父计费周期ID（内部使用），用于账单归组及审计。 */
    private String billingAttemptId;

    /** 可读的业务调用身份（内部使用），例如 stage=scene,chunk=2。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String callIdentity;

    /**
     * 单条对话消息：对应上游 messages 数组元素。
     */
    @Data
    public static class TextMessageItem {

        /**
         * 角色：如 system、user、assistant。
         */
        private String role;

        /** 文本内容；与 parts 同时存在时作为第一个文本块。 */
        private String content;

        /**
         * 多模态内容块。模型是否支持图片、视频或音频由 capability_json 校验。
         */
        private List<TextContentPart> parts;
    }

    /** 单个文本模型输入内容块。 */
    @Data
    public static class TextContentPart {

        /** 类型：text、image、video、audio、document。 */
        private String type;

        /** type=text 时的文本。 */
        private String text;

        /** 非文本内容的对象存储 URL 或厂商文件 URI。禁止传 data URI。 */
        private String url;

        /** 媒体 MIME 类型，如 image/png、video/mp4、audio/mpeg。 */
        private String mimeType;

        /** 图片理解精度提示，如 auto、low、high。 */
        private String detail;

        /** Gemini 等厂商使用的媒体解析精度，如 low、medium、high。 */
        private String mediaResolution;

        /** 视频抽帧频率；仅声明支持该参数的模型生效。 */
        private Double fps;

        /** 输入音视频时长（秒），用于能力校验和预估计费。 */
        private Double durationSeconds;

        /** 文件大小（字节），用于能力校验；缺省时由上游按自身限制处理。 */
        private Long sizeBytes;

        /** 文档页数，用于文档能力校验和 Token 预估。 */
        private Integer pageCount;

        /** 图片或视频帧宽度（像素），用于能力校验和 Token 预估。 */
        private Integer width;

        /** 图片或视频帧高度（像素），用于能力校验和 Token 预估。 */
        private Integer height;
    }
}
