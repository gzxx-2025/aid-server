package com.aid.compose.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.compose.service.StoryboardVideoSelectionResolver;
import com.aid.enums.GenTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分镜当前选用视频解析实现。
 *
 * @author 视觉AID
 */
@Service
@RequiredArgsConstructor
public class StoryboardVideoSelectionResolverImpl implements StoryboardVideoSelectionResolver {

    private static final String DEL_FLAG_NORMAL = "0";
    private static final int GEN_STATUS_SUCCESS = 1;
    private static final int SELECTED_YES = 1;
    private static final List<String> VIDEO_GEN_TYPES = List.of(
            GenTypeEnum.I2V.getValue(), GenTypeEnum.MULTI.getValue(), GenTypeEnum.EDGE.getValue(),
            GenTypeEnum.UPLOAD_VIDEO.getValue(), GenTypeEnum.COMPOSE.getValue());

    private final IAidStoryboardService aidStoryboardService;
    private final AidGenRecordMapper aidGenRecordMapper;

    @Override
    public Map<Long, AidGenRecord> resolve(Long projectId, Long episodeId, Long userId,
                                           Collection<Long> storyboardIds) {
        if (projectId == null || episodeId == null || userId == null
                || CollectionUtil.isEmpty(storyboardIds)) {
            return Map.of();
        }
        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long storyboardId : storyboardIds) {
            if (storyboardId != null) {
                distinctIds.add(storyboardId);
            }
        }
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        // 查询字段精简：解析只消费主键与最终视频指针。
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getFinalVideoId)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidStoryboard::getId, distinctIds));
        if (CollectionUtil.isEmpty(storyboards)) {
            return Map.of();
        }
        return resolveRecords(storyboards, userId);
    }

    /** 查询候选记录并按统一优先级选出每个分镜的一条。 */
    private Map<Long, AidGenRecord> resolveRecords(List<AidStoryboard> storyboards, Long userId) {
        Set<Long> storyboardIds = new LinkedHashSet<>();
        for (AidStoryboard storyboard : storyboards) {
            storyboardIds.add(storyboard.getId());
        }
        // 查询字段精简：调用方只消费地址、时长、记录ID与类型。
        List<AidGenRecord> records = aidGenRecordMapper.selectList(
                new LambdaQueryWrapper<AidGenRecord>()
                        .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl,
                                AidGenRecord::getVideoDuration, AidGenRecord::getIsSelected,
                                AidGenRecord::getGenType, AidGenRecord::getUpdateTime)
                        .eq(AidGenRecord::getUserId, userId)
                        .in(AidGenRecord::getStoryboardId, storyboardIds)
                        .in(AidGenRecord::getGenType, VIDEO_GEN_TYPES)
                        .eq(AidGenRecord::getStatus, GEN_STATUS_SUCCESS)
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidGenRecord::getFileUrl)
                        .orderByAsc(AidGenRecord::getId));
        Map<Long, AidGenRecord> byId = new HashMap<>();
        Map<Long, AidGenRecord> latestByStoryboard = new HashMap<>();
        Map<Long, AidGenRecord> latestSelectedByStoryboard = new HashMap<>();
        Map<Long, AidGenRecord> latestSelectedComposeByStoryboard = new HashMap<>();
        for (AidGenRecord record : records) {
            byId.put(record.getId(), record);
            latestByStoryboard.put(record.getStoryboardId(), record);
            if (Objects.equals(record.getIsSelected(), SELECTED_YES)) {
                latestSelectedByStoryboard.put(record.getStoryboardId(), record);
                if (GenTypeEnum.COMPOSE.getValue().equals(record.getGenType())) {
                    latestSelectedComposeByStoryboard.put(record.getStoryboardId(), record);
                }
            }
        }
        Map<Long, AidGenRecord> result = new HashMap<>();
        for (AidStoryboard storyboard : storyboards) {
            AidGenRecord selectedCompose = latestSelectedComposeByStoryboard.get(storyboard.getId());
            AidGenRecord selectedOriginal = storyboard.getFinalVideoId() == null
                    ? null : byId.get(storyboard.getFinalVideoId());
            // 原视频与配音成片是上下游关系：两者历史上可能同时保持选中，必须取最后一次明确选择的记录。
            AidGenRecord chosen = chooseLatestSelection(selectedOriginal, selectedCompose);
            if (chosen == null) {
                chosen = latestSelectedByStoryboard.get(storyboard.getId());
            }
            if (chosen == null) {
                chosen = latestByStoryboard.get(storyboard.getId());
            }
            if (chosen != null) {
                result.put(storyboard.getId(), chosen);
            }
        }
        return result;
    }

    /** 历史双选数据按更新时间判定最后一次选择，时间缺失时以记录ID作为稳定兜底。 */
    AidGenRecord chooseLatestSelection(AidGenRecord original, AidGenRecord compose) {
        if (original == null) {
            return compose;
        }
        if (compose == null) {
            return original;
        }
        Date originalTime = original.getUpdateTime();
        Date composeTime = compose.getUpdateTime();
        if (originalTime != null && composeTime != null && !originalTime.equals(composeTime)) {
            return originalTime.after(composeTime) ? original : compose;
        }
        return original.getId() >= compose.getId() ? original : compose;
    }
}
