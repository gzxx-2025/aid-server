package com.aid.aid.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.aid.service.IStoryboardSceneSnapshotService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.AssetNameNormalizer;
import com.aid.common.utils.DateUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜场景名称快照维护服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardSceneSnapshotServiceImpl implements IStoryboardSceneSnapshotService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String SCENE_NAME_KEY = "场景名称";
    private static final int PAGE_SIZE = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private IAidStoryboardService storyboardService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int synchronizeSceneName(Long projectId, Long userId, String oldName, String newName)
    {
        String oldNameKey = AssetNameNormalizer.normalize(oldName);
        if (projectId == null || userId == null || StrUtil.isBlank(oldNameKey) || StrUtil.isBlank(newName))
        {
            return 0;
        }

        int updatedCount = 0;
        long cursor = 0L;
        while (true)
        {
            List<AidStoryboard> page = storyboardService.list(
                    Wrappers.<AidStoryboard>lambdaQuery()
                            .select(AidStoryboard::getId, AidStoryboard::getScriptParams)
                            .eq(AidStoryboard::getProjectId, projectId)
                            .eq(AidStoryboard::getUserId, userId)
                            .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                            .gt(AidStoryboard::getId, cursor)
                            .orderByAsc(AidStoryboard::getId)
                            .last("LIMIT " + PAGE_SIZE));
            if (CollectionUtil.isEmpty(page))
            {
                break;
            }

            List<AidStoryboard> updates = new ArrayList<>();
            for (AidStoryboard storyboard : page)
            {
                cursor = storyboard.getId();
                String updatedParams = replaceMatchingSnapshot(
                        storyboard.getId(), storyboard.getScriptParams(), oldNameKey, newName);
                if (updatedParams == null)
                {
                    continue;
                }
                AidStoryboard update = new AidStoryboard();
                update.setId(storyboard.getId());
                update.setScriptParams(updatedParams);
                update.setUpdateTime(DateUtils.getNowDate());
                update.setUpdateBy(String.valueOf(userId));
                updates.add(update);
            }
            if (CollectionUtil.isNotEmpty(updates) && !storyboardService.updateBatchById(updates, PAGE_SIZE))
            {
                log.error("场景改名同步分镜快照失败: projectId={}, userId={}, count={}",
                        projectId, userId, updates.size());
                throw new ServiceException("场景改名失败");
            }
            updatedCount += updates.size();
            if (page.size() < PAGE_SIZE)
            {
                break;
            }
        }
        return updatedCount;
    }

    private String replaceMatchingSnapshot(Long storyboardId, String scriptParams,
                                           String oldNameKey, String newName)
    {
        if (StrUtil.isBlank(scriptParams))
        {
            return null;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(scriptParams);
            if (!(parsed instanceof ObjectNode params))
            {
                return null;
            }
            String snapshotName = params.path(SCENE_NAME_KEY).asText("");
            if (!oldNameKey.equals(AssetNameNormalizer.normalize(snapshotName)))
            {
                return null;
            }
            params.put(SCENE_NAME_KEY, newName);
            return OBJECT_MAPPER.writeValueAsString(params);
        }
        catch (Exception e)
        {
            log.warn("场景改名跳过非法分镜快照: storyboardId={}, err={}", storyboardId, e.getMessage());
            return null;
        }
    }
}
