package com.aid.rps.cleanup.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.core.redis.RedisCache;
import com.aid.media.cleanup.IMediaOssCleanupService;
import com.aid.rps.cleanup.AssetCleanupProperties;
import com.aid.rps.cleanup.IAssetTombstoneCleanupService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 自动覆盖资产墓碑清理服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AssetTombstoneCleanupServiceImpl implements IAssetTombstoneCleanupService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DEL_FLAG_DELETED = "2";
    private static final String DELETE_REASON_AUTO_OVERWRITE = "auto_overwrite";
    private static final String CREATE_SOURCE_AUTO = "auto";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String LOCK_KEY = "aid:asset-cleanup:lock";
    private static final long LOCK_TTL_HOURS = 6L;

    @Autowired
    private AssetCleanupProperties properties;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidRolePropSceneService assetService;

    @Autowired
    private IAidRolePropSceneFormService formService;

    @Autowired
    private IAidRolePropSceneFormImageService formImageService;

    @Autowired
    private IAidRoleVoiceBindingService voiceBindingService;

    @Autowired
    private IAidScenePlotService scenePlotService;

    @Autowired
    private IAidStoryboardService storyboardService;

    @Autowired
    private IMediaOssCleanupService mediaOssCleanupService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public int cleanExpired()
    {
        if (!properties.isEnabled())
        {
            log.debug("自动覆盖资产墓碑清理已关闭");
            return 0;
        }
        String lockToken = IdUtil.fastSimpleUUID();
        if (!redisCache.setCacheObjectIfAbsent(LOCK_KEY, lockToken, LOCK_TTL_HOURS, TimeUnit.HOURS))
        {
            log.debug("其它实例正在清理自动覆盖资产墓碑，本轮跳过");
            return 0;
        }
        try
        {
            return doCleanExpired();
        }
        finally
        {
            if (!redisCache.deleteObjectIfValueEquals(LOCK_KEY, lockToken))
            {
                log.warn("自动覆盖资产清理锁已过期或被重新获取，跳过释放: token={}", lockToken);
            }
        }
    }

    private int doCleanExpired()
    {
        Date cutoff = Date.from(Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS));
        List<AidRolePropScene> candidates = assetService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getAssetType,
                                AidRolePropScene::getDeletedAt, AidRolePropScene::getUpdateTime)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_DELETED)
                        .eq(AidRolePropScene::getDeleteReason, DELETE_REASON_AUTO_OVERWRITE)
                        .isNotNull(AidRolePropScene::getDeleteTaskId)
                        .lt(AidRolePropScene::getDeletedAt, cutoff)
                        .lt(AidRolePropScene::getUpdateTime, cutoff)
                        .orderByAsc(AidRolePropScene::getId)
                        .last("LIMIT " + properties.getBatchSize()));
        if (CollectionUtil.isEmpty(candidates))
        {
            return 0;
        }

        List<CleanupPlan> plans = new ArrayList<>();
        for (AidRolePropScene candidate : candidates)
        {
            try
            {
                CleanupPlan plan = loadCleanupPlan(candidate.getId(), cutoff);
                if (Objects.nonNull(plan))
                {
                    plans.add(plan);
                }
            }
            catch (Exception e)
            {
                log.error("自动覆盖资产墓碑清理计划加载失败，保留数据待下次重试: assetId={}",
                        candidate.getId(), e);
            }
        }

        Set<String> allFileUrls = new LinkedHashSet<>();
        plans.forEach(plan -> allFileUrls.addAll(plan.fileUrls()));
        Map<String, Boolean> fileResults = mediaOssCleanupService.cleanupFilesNowByFile(allFileUrls);

        int cleaned = 0;
        for (CleanupPlan plan : plans)
        {
            try
            {
                boolean fileFailed = plan.fileUrls().stream()
                        .anyMatch(url -> !Boolean.TRUE.equals(fileResults.get(url)));
                if (fileFailed)
                {
                    continue;
                }
                Boolean removed = transactionTemplate.execute(status -> removeTombstone(plan.assetId(), cutoff));
                if (Boolean.TRUE.equals(removed))
                {
                    cleaned++;
                }
            }
            catch (Exception e)
            {
                log.error("自动覆盖资产墓碑清理失败，保留数据待下次重试: assetId={}", plan.assetId(), e);
            }
        }
        log.info("自动覆盖资产墓碑清理完成: candidates={}, cleaned={}", candidates.size(), cleaned);
        return cleaned;
    }

    private CleanupPlan loadCleanupPlan(Long assetId, Date cutoff)
    {
        AidRolePropScene root = findEligibleRoot(assetId, cutoff, false);
        if (Objects.isNull(root) || hasProtectedAssociation(root))
        {
            return null;
        }
        List<AidRolePropSceneFormImage> images = formImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getImageUrl)
                        .eq(AidRolePropSceneFormImage::getAssetId, assetId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_DELETED));
        Set<String> urls = new LinkedHashSet<>();
        for (AidRolePropSceneFormImage image : images)
        {
            if (StrUtil.isNotBlank(image.getImageUrl()))
            {
                urls.add(image.getImageUrl().trim());
            }
        }
        return new CleanupPlan(assetId, urls);
    }

    private boolean removeTombstone(Long assetId, Date cutoff)
    {
        AidRolePropScene root = findEligibleRoot(assetId, cutoff, true);
        if (Objects.isNull(root))
        {
            return false;
        }
        if (hasProtectedAssociation(root))
        {
            log.warn("资产墓碑在物理删除前出现有效或人工关联，保留待核查: assetId={}", assetId);
            return false;
        }

        formImageService.remove(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getAssetId, assetId)
                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_DELETED));
        formService.remove(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getAssetId, assetId)
                .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_DELETED));
        voiceBindingService.remove(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getAssetId, assetId)
                .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_DELETED));
        if (Objects.equals(ASSET_TYPE_SCENE, root.getAssetType()))
        {
            scenePlotService.remove(Wrappers.<AidScenePlot>lambdaQuery()
                    .eq(AidScenePlot::getSceneId, assetId)
                    .eq(AidScenePlot::getDelFlag, DEL_FLAG_DELETED)
                    .eq(AidScenePlot::getCreateSource, CREATE_SOURCE_AUTO));
        }

        boolean removed = assetService.remove(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getId, assetId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_DELETED)
                .eq(AidRolePropScene::getDeleteReason, DELETE_REASON_AUTO_OVERWRITE)
                .isNotNull(AidRolePropScene::getDeleteTaskId)
                .lt(AidRolePropScene::getDeletedAt, cutoff)
                .lt(AidRolePropScene::getUpdateTime, cutoff));
        if (!removed)
        {
            throw new IllegalStateException("资产墓碑状态已变化");
        }
        return true;
    }

    private AidRolePropScene findEligibleRoot(Long assetId, Date cutoff, boolean forUpdate)
    {
        var query = Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId, AidRolePropScene::getAssetType)
                .eq(AidRolePropScene::getId, assetId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_DELETED)
                .eq(AidRolePropScene::getDeleteReason, DELETE_REASON_AUTO_OVERWRITE)
                .isNotNull(AidRolePropScene::getDeleteTaskId)
                .lt(AidRolePropScene::getDeletedAt, cutoff)
                .lt(AidRolePropScene::getUpdateTime, cutoff);
        if (forUpdate)
        {
            query.last("FOR UPDATE");
        }
        return assetService.getOne(query, false);
    }

    private boolean hasProtectedAssociation(AidRolePropScene root)
    {
        Long assetId = root.getId();
        if (formService.count(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getAssetId, assetId)
                .and(wrapper -> wrapper.isNull(AidRolePropSceneForm::getDelFlag)
                        .or().ne(AidRolePropSceneForm::getDelFlag, DEL_FLAG_DELETED))) > 0)
        {
            return true;
        }
        if (formImageService.count(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getAssetId, assetId)
                .and(wrapper -> wrapper.isNull(AidRolePropSceneFormImage::getDelFlag)
                        .or().ne(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_DELETED))) > 0)
        {
            return true;
        }
        if (voiceBindingService.count(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getAssetId, assetId)
                .and(wrapper -> wrapper.isNull(AidRoleVoiceBinding::getDelFlag)
                        .or().ne(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_DELETED))) > 0)
        {
            return true;
        }
        if (!Objects.equals(ASSET_TYPE_SCENE, root.getAssetType()))
        {
            return false;
        }
        // 有效分镜仍按 source_scene_id 追踪该历史根时保留根数据；分镜名称快照负责当前场景解析，
        // 但不应在分镜仍有效时提前破坏其辅助追踪链。
        if (storyboardService.count(Wrappers.<AidStoryboard>lambdaQuery()
                .eq(AidStoryboard::getSourceSceneId, assetId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)) > 0)
        {
            return true;
        }
        return scenePlotService.count(Wrappers.<AidScenePlot>lambdaQuery()
                .eq(AidScenePlot::getSceneId, assetId)
                .and(wrapper -> wrapper.isNull(AidScenePlot::getDelFlag)
                        .or().ne(AidScenePlot::getDelFlag, DEL_FLAG_DELETED)
                        .or().isNull(AidScenePlot::getCreateSource)
                        .or().ne(AidScenePlot::getCreateSource, CREATE_SOURCE_AUTO))) > 0;
    }

    private record CleanupPlan(Long assetId, Set<String> fileUrls)
    {
    }
}
