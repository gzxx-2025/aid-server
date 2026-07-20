package com.aid.compose.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 分段素材导出清单元素：一个分镜（时间轴上的一段）的最终视频与配音。
 * 出参结构恒定：字段全量返回，无数据的字段为 null。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeSegmentVideoItem {

    /** 分镜ID（aid_storyboard.id） */
    private Long storyboardId;

    /** 段序号（分镜 sort_order，从小到大即成片顺序） */
    private Integer sortOrder;

    /** 分镜标题（可空） */
    private String title;

    /** 最终视频记录ID（aid_gen_record.id，即被设为主视频的记录）；该分镜未设最终视频为 null */
    private Long genRecordId;

    /** 最终视频地址（出参自动拼 OSS 域名，可直接下载）；未设最终视频为 null */
    @MediaUrl
    private String videoUrl;

    /** 最终视频时长（秒）；无视频为 null */
    private Long videoDurationSeconds;

    /** 该段配音记录ID（aid_audio_record.id，优先最终选中配音，其次最新成功配音）；无配音为 null */
    private Long audioRecordId;

    /** 该段配音音频地址（出参自动拼域名，mp3）；无配音为 null */
    @MediaUrl
    private String audioUrl;

    /** 配音时长（毫秒）；无配音或未探测到为 null */
    private Integer audioDurationMs;

    /**
     * 对口型合成视频地址（画面+配音合一的单段成片，仅该段开启过对口型且成功时非空）。
     * 前端导出"带配音的分段视频"时优先取本字段，为 null 则分别下载 videoUrl + audioUrl。
     */
    @MediaUrl
    private String lipSyncVideoUrl;

    /** 该段字幕文本（台词已格式化为「人物：说的话」）；无台词为 null */
    private String subtitle;

    /** 该段是否有配音（audioUrl 非空） */
    private Boolean hasDubbing;
}
