package com.aid.domain.dto;

import com.aid.media.dto.MediaTextGenerateRequest;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分镜文本生成请求 DTO
 *
 * 根据分镜信息组装 AI 文本生成请求，调用大模型生成分镜脚本文本内容。
 *
 * @author 视觉AID
 */
@Data
public class TextGenReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 【必传】分镜主键ID
     * 用于关联分镜记录。
     */
    private Long storyboardId;

    /**
     * 【必传】用户ID
     * 用于校验分镜归属权限，同时作为 aid_gen_record 的 userId 写入。
     */
    private Long userId;

    /**
     * 【必传（与 messages 二选一）】单轮用户输入文本
     * 用于调用大模型生成分镜脚本文本内容。
     */
    private String prompt;

    /**
     * 【可选】多轮对话消息列表
     * 元素含 role、content；非空时与 prompt 组合使用。
     */
    private java.util.List<MediaTextGenerateRequest.TextMessageItem> messages;

    /**
     * 【可选】指定模型名称
     * 为空时走后端默认文本模型与协议路由。
     */
    private String modelName;
}
