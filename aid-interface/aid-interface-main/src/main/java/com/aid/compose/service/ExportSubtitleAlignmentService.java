package com.aid.compose.service;

import com.aid.aid.domain.AidGenRecord;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.timeline.TimelineSegment;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 导出自动字幕服务。开启后按分镜同步识别视频，并把时间戳字幕回填到合成分组与工程时间轴。
 *
 * @author 视觉AID
 */
public interface ExportSubtitleAlignmentService {

    /** 自动字幕是否开启。 */
    boolean isEnabled();

    /**
     * 统计本次导出需要重新识别的分镜数。
     * 无台词分镜和已持有当前最终人声音源有效时间戳的分镜不计入。
     */
    int countRequired(List<ComposeGroupDto> groups, List<TimelineSegment> matchedSegments,
                      Map<Long, AidGenRecord> selectedVideos);

    /**
     * 同步完成所需分镜的字幕识别；服务请求失败会直接抛错，音源未识别到人声时保留文本兼容排布。
     *
     * @param groups           导出合成分组
     * @param matchedSegments  与分组下标对应的工程分镜，未匹配项为 null
     * @param selectedVideos   服务端当前选中的分镜视频
     * @param progressCallback 已完成数/总数回调
     * @param checkpointCallback 每次状态变化后立即持久化时间轴的回调
     * @param heartbeatCallback  上游等待期间刷新本次导出存活时间的回调
     */
    void align(List<ComposeGroupDto> groups, List<TimelineSegment> matchedSegments,
               Map<Long, AidGenRecord> selectedVideos, BiConsumer<Integer, Integer> progressCallback,
               Runnable checkpointCallback, Runnable heartbeatCallback);
}
