package com.aid.media.dto;

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

    /** 非流式偏好（内部使用）：为 true 时优先走 chatSync() 非流式调用，稳定获取 usage；默认 false 走流式 */
    private Boolean preferNonStream;

    /**
     * 任务存档摘要（可选，业务方覆盖）：用于 aid_media_task.prompt 列存档展示。
     */
    private String taskPromptDigest;

    /**
     * 单条对话消息：对应上游 messages 数组元素。
     */
    @Data
    public static class TextMessageItem {

        /**
         * 角色：如 system、user、assistant。
         */
        private String role;

        /**
         * 文本内容。
         */
        private String content;
    }
}
