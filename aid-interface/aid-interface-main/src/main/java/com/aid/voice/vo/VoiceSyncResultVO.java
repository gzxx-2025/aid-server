package com.aid.voice.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 音色远程同步结果 VO。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceSyncResultVO
{
    /** 被同步的模型ID（aid_ai_model.id） */
    private Long modelId;

    /** 被同步的模型编码 */
    private String modelCode;

    /** 上游拉取到的音色总数 */
    private Integer remoteCount;

    /** 本地新增数 */
    private Integer inserted;

    /** 本地更新数（字段有差异 / 无差异都算） */
    private Integer updated;

    /** 本地恢复软删数（远程重新上架之前下架的 voice_code → del_flag 置回 '0'） */
    private Integer restored;

    /** 本地软删数（远程不存在 → 标记 del_flag='2'） */
    private Integer softDeleted;

    /** 同步耗时（毫秒） */
    private Long costMs;

    /** 同步时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date syncTime;
}
