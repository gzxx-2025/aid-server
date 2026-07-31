package com.aid.compose.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.timeline.TimelineSegment;

import cn.hutool.core.collection.CollectionUtil;

/**
 * 使用稳定分镜ID匹配导出分组与工程时间线分镜。
 *
 * @author 视觉AID
 */
public final class TimelineSubtitleMatcher {

    private TimelineSubtitleMatcher() {
    }

    /**
     * 为每个导出分组匹配工程分镜，返回列表与 groups 等长且下标一致，无法匹配的位置为 null。
     *
     * @param groups        本次实际导出的分组
     * @param segments      完整工程时间线分镜
     * @return 与导出分组逐项对应的工程分镜
     */
    public static List<TimelineSegment> match(List<ComposeGroupDto> groups,
                                               List<TimelineSegment> segments) {
        List<TimelineSegment> result = new ArrayList<>();
        if (CollectionUtil.isEmpty(groups)) {
            return result;
        }
        for (int i = 0; i < groups.size(); i++) {
            result.add(null);
        }
        if (CollectionUtil.isEmpty(segments)) {
            return result;
        }

        Map<Long, TimelineSegment> segmentByStoryboardId = buildStoryboardIndex(segments);

        for (int i = 0; i < groups.size(); i++) {
            ComposeGroupDto group = groups.get(i);
            if (Objects.isNull(group)) {
                continue;
            }
            result.set(i, segmentByStoryboardId.get(group.getStoryboardId()));
        }
        return result;
    }

    /**
     * 构建分镜 ID 索引；重复 ID 视为歧义并从索引移除，禁止静默覆盖后误配字幕。
     */
    private static Map<Long, TimelineSegment> buildStoryboardIndex(List<TimelineSegment> segments) {
        Map<Long, TimelineSegment> result = new HashMap<>();
        Set<Long> ambiguousIds = new HashSet<>();
        for (TimelineSegment segment : segments) {
            if (Objects.isNull(segment) || Objects.isNull(segment.getStoryboardId())) {
                continue;
            }
            Long storyboardId = segment.getStoryboardId();
            if (result.containsKey(storyboardId)) {
                result.remove(storyboardId);
                ambiguousIds.add(storyboardId);
            } else if (!ambiguousIds.contains(storyboardId)) {
                result.put(storyboardId, segment);
            }
        }
        return result;
    }
}
