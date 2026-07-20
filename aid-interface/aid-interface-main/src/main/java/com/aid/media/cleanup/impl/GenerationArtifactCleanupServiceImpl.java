package com.aid.media.cleanup.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.media.cleanup.IGenerationArtifactCleanupService;
import com.aid.media.cleanup.IMediaOssCleanupService;

import cn.hutool.core.collection.CollectionUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 生成产物级联清理服务实现：先按已查出的 id 集合物理删库，再登记 OSS 文件清理。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class GenerationArtifactCleanupServiceImpl implements IGenerationArtifactCleanupService
{
    /** 抽卡生成记录服务 */
    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /** 分镜配音记录服务 */
    @Autowired
    private IAidAudioRecordService aidAudioRecordService;

    /** OSS 文件清理服务 */
    @Autowired
    private IMediaOssCleanupService mediaOssCleanupService;

    /**
     * 按分镜ID集合级联清理生成产物。
     *
     * @param storyboardIds 分镜ID集合
     */
    @Override
    public void cleanupByStoryboardIds(Collection<Long> storyboardIds)
    {
        if (CollectionUtil.isEmpty(storyboardIds))
        {
            return;
        }
        List<AidGenRecord> genRecords = aidGenRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                .in(AidGenRecord::getStoryboardId, storyboardIds));
        List<AidAudioRecord> audioRecords = aidAudioRecordService.list(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getAudioUrl, AidAudioRecord::getSyncVideoUrl)
                .in(AidAudioRecord::getStoryboardId, storyboardIds));
        deleteThenCleanup(genRecords, audioRecords, "storyboardIds=" + storyboardIds);
    }

    /**
     * 按项目ID + 剧集ID级联清理生成产物。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影为0）
     */
    @Override
    public void cleanupByEpisode(Long projectId, Long episodeId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId))
        {
            return;
        }
        List<AidGenRecord> genRecords = aidGenRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                .eq(AidGenRecord::getProjectId, projectId)
                .eq(AidGenRecord::getEpisodeId, episodeId));
        List<AidAudioRecord> audioRecords = aidAudioRecordService.list(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getAudioUrl, AidAudioRecord::getSyncVideoUrl)
                .eq(AidAudioRecord::getProjectId, projectId)
                .eq(AidAudioRecord::getEpisodeId, episodeId));
        deleteThenCleanup(genRecords, audioRecords, "projectId=" + projectId + ",episodeId=" + episodeId);
    }

    /**
     * 按项目ID级联清理生成产物。
     *
     * @param projectId 项目ID
     */
    @Override
    public void cleanupByProject(Long projectId)
    {
        if (Objects.isNull(projectId))
        {
            return;
        }
        List<AidGenRecord> genRecords = aidGenRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                .eq(AidGenRecord::getProjectId, projectId));
        List<AidAudioRecord> audioRecords = aidAudioRecordService.list(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getAudioUrl, AidAudioRecord::getSyncVideoUrl)
                .eq(AidAudioRecord::getProjectId, projectId));
        deleteThenCleanup(genRecords, audioRecords, "projectId=" + projectId);
    }

    /**
     * 公共流程：先按已查出的 id 集合物理删库，再登记 OSS 文件清理（afterCommit 后台执行）。
     *
     * @param genRecords   范围内生成记录（含 id + fileUrl）
     * @param audioRecords 范围内配音记录（含 id + audioUrl / syncVideoUrl）
     * @param scopeDesc    范围描述（日志用）
     */
    private void deleteThenCleanup(List<AidGenRecord> genRecords, List<AidAudioRecord> audioRecords, String scopeDesc)
    {
        if (CollectionUtil.isEmpty(genRecords) && CollectionUtil.isEmpty(audioRecords))
        {
            return;
        }
        List<String> fileUrls = new ArrayList<>();
        // 物理删除生成记录并收集其 OSS 文件
        if (CollectionUtil.isNotEmpty(genRecords))
        {
            List<Long> genIds = new ArrayList<>(genRecords.size());
            for (AidGenRecord r : genRecords)
            {
                genIds.add(r.getId());
                fileUrls.add(r.getFileUrl());
            }
            aidGenRecordService.removeByIds(genIds);
        }
        // 物理删除配音记录并收集其 OSS 文件
        if (CollectionUtil.isNotEmpty(audioRecords))
        {
            List<Long> audioIds = new ArrayList<>(audioRecords.size());
            for (AidAudioRecord a : audioRecords)
            {
                audioIds.add(a.getId());
                fileUrls.add(a.getAudioUrl());
                fileUrls.add(a.getSyncVideoUrl());
            }
            aidAudioRecordService.removeByIds(audioIds);
        }
        // 登记 OSS 文件清理（事务提交成功后后台执行）
        mediaOssCleanupService.cleanupFiles(fileUrls);
        log.info("生成产物级联清理完成, 范围[{}], genRecords={}, audioRecords={}",
                scopeDesc, CollectionUtil.isEmpty(genRecords) ? 0 : genRecords.size(),
                CollectionUtil.isEmpty(audioRecords) ? 0 : audioRecords.size());
    }
}
