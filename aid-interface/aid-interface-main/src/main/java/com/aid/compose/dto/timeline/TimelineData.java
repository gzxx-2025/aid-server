package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 时间轴工程数据（aid_episode_editor.timeline_json 的落库结构，同时也是接口出入参结构）。
 * 存储模型为「段 × 轨」：外层 segments 按分镜顺序排列（一段 = 时间轴上的一列，与分镜一一对应），
 * 段内挂视频轨 / 配音轨 / 字幕轨在该段的元素；背景音乐是全片级单独一轨（bgm）。
 * 各段首尾相接，段时长 = 该段视频时长；库内所有资源 URL 统一存相对路径，出参自动拼完整域名。
 * 出参结构恒定：本结构及全部子结构标注 {@code @JsonInclude(ALWAYS)}（覆盖全局 non_null 配置），
 * 且出参前统一经过恒定化处理——无论首次初始化、已有工程、某段无配音/无字幕/无背景音乐，
 * 返回的字段名与层级完全一致（无数据的字段返回 null / 默认值，绝不缺字段），前端可放心按固定结构解析。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineData {

    /** 结构版本号，当前固定 1（后续结构升级时用于兼容迁移） */
    private Integer version;

    /** 输出分辨率档：SD/HD/FHD/2K/4K，默认 FHD（导出时可被导出入参覆盖） */
    private String resolution;

    /** 全片总时长（秒，= 各段视频时长之和，保存时后端重算，前端只读展示） */
    private Double totalDurationSeconds;

    /** 分镜段列表，按播放顺序排列（一段 = 一个分镜） */
    private List<TimelineSegment> segments;

    /** 全片背景音乐轨（单条，url 为空 = 无音乐） */
    private TimelineBgm bgm;

    /** 前端自由扩展参数（JSON 字符串，后端不解析、原样存取；预留） */
    private String extraJson;
}
