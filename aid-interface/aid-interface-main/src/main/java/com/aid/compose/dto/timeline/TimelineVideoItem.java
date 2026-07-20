package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 时间轴视频轨元素：该段的分镜视频（来自「视频生成」步骤的抽卡记录）。
 * 出参恒定：对象永不为 null，无视频时 url/genRecordId=null、durationSeconds=0、音量给默认值。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineVideoItem {

    /** 来源抽卡记录ID（aid_gen_record.id），自动初始化时回填，便于溯源与换视频 */
    private Long genRecordId;

    /** 视频地址。库内存相对路径，出参拼完整域名；为空 = 该分镜还没有视频 */
    private String url;

    /** 视频时长（秒），来自 aid_gen_record.video_duration；无视频为 0 */
    private Double durationSeconds;

    /** 视频原声音量（0-100，默认 100），对应时间轴「音量」轨 */
    private Integer volume;

    /** 是否静音（默认 false）；true 时导出忽略 volume 按 0 处理 */
    private Boolean muted;
}
