package com.aid.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 媒体生成响应 DTO
 *
 * generateImage / generateVideo 的统一返回对象。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaGenRespDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地生成记录主键（aid_gen_record.id）
     * 后续回调接口通过此 ID 定位记录并回写生成结果
     */
    private Long recordId;

    /**
     * 三方大模型返回的任务ID
     * 用于定时任务轮询查询生成进度/结果
     */
    private String taskId;

    /**
     * 当前任务状态
     * 提交成功后为 "PROCESSING"，后续由回调更新为 "SUCCESS" / "FAILED"
     */
    private String status;
}
