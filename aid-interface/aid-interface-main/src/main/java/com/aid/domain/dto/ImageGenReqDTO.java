package com.aid.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分镜图片生成请求 DTO
 *
 * 根据前端传入的参数（提示词、景别、角度、色调、资产引用等）组装 AI 生图请求，
 * 生成参数存储在 aid_gen_record.gen_params JSON 字段中。
 *
 * @author 视觉AID
 */
@Data
public class ImageGenReqDTO implements Serializable {

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
     * 【必传】生成参数对象
     * 包含画面描述、资产引用、摄影参数等所有生图参数。
     */
    private GenerationParams genParams;
}
