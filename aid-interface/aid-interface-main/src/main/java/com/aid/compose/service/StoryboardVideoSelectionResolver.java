package com.aid.compose.service;

import com.aid.aid.domain.AidGenRecord;

import java.util.Collection;
import java.util.Map;

/**
 * 分镜当前选用视频的唯一解析入口，时间轴初始化、自动同步与成片导出必须共用同一口径。
 *
 * @author 视觉AID
 */
public interface StoryboardVideoSelectionResolver {

    /**
     * 批量解析当前选用视频：使用中的配音视频优先，其次 final_video_id，最后才是历史兼容回落。
     */
    Map<Long, AidGenRecord> resolve(Long projectId, Long episodeId, Long userId,
                                    Collection<Long> storyboardIds);
}
