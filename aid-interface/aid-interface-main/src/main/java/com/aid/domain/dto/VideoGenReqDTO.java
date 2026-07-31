package com.aid.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分镜视频生成请求 DTO
 *
 * 基于上一步生成的分镜底图和前端传入的动态参数（运镜、拍摄手法、动作描述、资产引用等）组装 AI 图生视频请求，
 * 生成参数存储在 aid_gen_record.gen_params JSON 字段中。
 *
 * @author 视觉AID
 */
@Data
public class VideoGenReqDTO implements Serializable {

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
     * 【必传】底图生成记录ID（aid_gen_record.id）
     * 即上一步 generateImage 返回的 recordId，方法内部会校验该记录存在且 fileUrl 不为空，
     * 提取其 URL 作为本次图生视频的垫图。
     */
    private Long baseImageRecordId;

    /**
     * 【必传】生成参数对象
     * 包含动作描述、资产引用、运镜参数等所有生视频参数。
     */
    private GenerationParams genParams;
}
