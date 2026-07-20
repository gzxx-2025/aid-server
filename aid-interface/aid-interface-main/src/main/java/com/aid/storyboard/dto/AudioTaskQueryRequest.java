package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 配音任务查询请求
 *
 * @author 视觉AID
 */
@Data
public class AudioTaskQueryRequest {

    /** 配音记录ID（aid_audio_record.id，配音/对口型受理接口返回的 id） */
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}
