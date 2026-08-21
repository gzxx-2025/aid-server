package com.aid.media.cleanup.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.media.cleanup.IMediaOssCleanupService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体文件 OSS 清理服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class MediaOssCleanupServiceImpl implements IMediaOssCleanupService
{
    /** 正常删除标志。 */
    private static final String DEL_FLAG_NORMAL = "0";
    private static final int REFERENCE_QUERY_BATCH_SIZE = 500;

    /** OSS 操作模板。 */
    @Autowired
    private OssTemplate ossTemplate;

    /** 后台清理线程池。 */
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** 用户参考资产服务。 */
    @Autowired
    private IAidUserComicAssetService aidUserComicAssetService;

    /** 官方素材资产服务。 */
    @Autowired
    private IAidComicAssetService aidComicAssetService;

    /** 官方 AI 音色库服务。 */
    @Autowired
    private IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    /** 生成记录服务：共享文件仍有业务引用时阻止误删。 */
    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /** 配音记录服务：共享音频/视频仍有业务引用时阻止误删。 */
    @Autowired
    private IAidAudioRecordService aidAudioRecordService;

    /** 媒体 URL 解析器。 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /** 形态图片服务：保护其它有效资产仍展示的同一对象。 */
    @Autowired
    private IAidRolePropSceneFormImageService formImageService;

    /** 角色音色绑定服务：保护有效绑定仍展示或复用的媒体。 */
    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    /**
     * 清理媒体文件。
     *
     * @param fileUrls 文件 URL 或相对路径集合
     */
    @Override
    public void cleanupFiles(Collection<String> fileUrls)
    {
        if (Objects.isNull(fileUrls) || fileUrls.isEmpty())
        {
            return;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String url : fileUrls)
        {
            if (StrUtil.isNotBlank(url))
            {
                targets.add(url.trim());
            }
        }
        if (targets.isEmpty())
        {
            return;
        }
        // 文件清理延迟到事务提交后执行，避免回滚后误删仍被业务引用的文件。
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    submitCleanup(targets);
                }
            });
            return;
        }
        submitCleanup(targets);
    }

    @Override
    public boolean cleanupFilesNow(Collection<String> fileUrls)
    {
        return !cleanupFilesNowByFile(fileUrls).containsValue(Boolean.FALSE);
    }

    @Override
    public Map<String, Boolean> cleanupFilesNowByFile(Collection<String> fileUrls)
    {
        Set<String> targets = normalizeTargets(fileUrls);
        Map<String, Boolean> results = new LinkedHashMap<>();
        Set<String> referencedTargets;
        try
        {
            referencedTargets = findStillReferencedTargets(targets);
        }
        catch (Exception e)
        {
            // 引用查询失败时无法证明文件无人使用，整批标记失败并让墓碑留待下次重试。
            targets.forEach(url -> results.put(url, Boolean.FALSE));
            log.error("媒体文件引用检查失败，保留全部数据库墓碑: count={}", targets.size(), e);
            return results;
        }
        for (String url : targets)
        {
            try
            {
                if (referencedTargets.contains(url))
                {
                    log.info("文件仍被业务数据引用，保留对象存储文件, url={}", url);
                    results.put(url, Boolean.TRUE);
                    continue;
                }
                if (!ossTemplate.deleteByUrl(url))
                {
                    results.put(url, Boolean.FALSE);
                    log.error("文件删除失败，保留数据库墓碑待重试, url={}", url);
                }
                else
                {
                    results.put(url, Boolean.TRUE);
                }
            }
            catch (Exception e)
            {
                results.put(url, Boolean.FALSE);
                log.error("文件删除异常，保留数据库墓碑待重试, url={}", url, e);
            }
        }
        return results;
    }

    /**
     * 提交后台清理任务。
     *
     * @param targets 文件集合
     */
    private void submitCleanup(Set<String> targets)
    {
        try
        {
            threadPoolTaskExecutor.execute(() -> doCleanup(targets));
        }
        catch (Exception rejectEx)
        {
            // 线程池不可用时降级同步执行，清理失败仍不影响主流程。
            log.warn("文件清理提交线程池被拒绝，降级同步执行, count={}", targets.size(), rejectEx);
            doCleanup(targets);
        }
    }

    /**
     * 删除媒体文件。
     *
     * @param targets 文件集合
     */
    private void doCleanup(Set<String> targets)
    {
        Set<String> referencedTargets;
        try
        {
            referencedTargets = findStillReferencedTargets(targets);
        }
        catch (Exception e)
        {
            // 引用状态不可确认时宁可保留对象，避免把仍在展示或复用的共享文件误删。
            log.error("媒体文件引用检查失败，本批次跳过物理删除: count={}", targets.size(), e);
            return;
        }
        for (String url : targets)
        {
            try
            {
                // 仍被任何业务记录或受保护资产引用的文件不做物理删除。
                if (referencedTargets.contains(url))
                {
                    log.warn("文件仍被业务数据引用，跳过删除, url={}", url);
                    continue;
                }
                boolean ok = ossTemplate.deleteByUrl(url);
                if (!ok)
                {
                    String where = ossTemplate.isLocalFile(url) ? "本地" : "远程OSS/COS";
                    log.error("{}文件删除失败（不影响业务，请关注残留文件）, url={}", where, url);
                }
            }
            catch (Exception e)
            {
                log.error("文件删除异常（不影响业务，请关注残留文件）, url={}", url, e);
            }
        }
    }

    /**
     * 判断文件是否仍被有效业务数据引用。
     *
     * @param targets 文件 URL 或相对路径集合
     * @return 仍被引用的目标集合
     */
    private Set<String> findStillReferencedTargets(Collection<String> targets)
    {
        Set<String> normalizedTargets = normalizeTargets(targets);
        if (normalizedTargets.size() == 1)
        {
            String target = normalizedTargets.iterator().next();
            return isStillReferenced(target) ? Set.of(target) : Set.of();
        }
        Map<String, Set<String>> candidatesByTarget = new LinkedHashMap<>();
        Set<String> allCandidates = new LinkedHashSet<>();
        for (String target : normalizedTargets)
        {
            Set<String> candidates = buildCandidates(target);
            candidatesByTarget.put(target, candidates);
            allCandidates.addAll(candidates);
        }
        Set<String> referencedCandidates = loadReferencedCandidates(allCandidates);
        Set<String> referencedTargets = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : candidatesByTarget.entrySet())
        {
            if (entry.getValue().stream().anyMatch(referencedCandidates::contains))
            {
                referencedTargets.add(entry.getKey());
            }
        }
        return referencedTargets;
    }

    /**
     * 单文件沿用短路式精确引用检查；批量墓碑清理走下方 IN 聚合查询，避免 URL×表数量的 N+1。
     */
    private boolean isStillReferenced(String target)
    {
        List<String> candidates = new ArrayList<>(buildCandidates(target));
        if (aidGenRecordService.count(Wrappers.<AidGenRecord>lambdaQuery()
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .in(AidGenRecord::getFileUrl, candidates)) > 0)
        {
            return true;
        }
        if (aidAudioRecordService.count(Wrappers.<AidAudioRecord>lambdaQuery()
                .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                .and(wrapper -> wrapper.in(AidAudioRecord::getAudioUrl, candidates)
                        .or().in(AidAudioRecord::getSyncVideoUrl, candidates))) > 0)
        {
            return true;
        }
        if (aidUserComicAssetService.count(Wrappers.<AidUserComicAsset>lambdaQuery()
                .in(AidUserComicAsset::getImageUrl, candidates)) > 0)
        {
            return true;
        }
        if (aidComicAssetService.count(Wrappers.<AidComicAsset>lambdaQuery()
                .eq(AidComicAsset::getDelFlag, DEL_FLAG_NORMAL)
                .in(AidComicAsset::getImageUrl, candidates)) > 0)
        {
            return true;
        }
        if (aidAiVoiceLibraryService.count(Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                .eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL)
                .in(AidAiVoiceLibrary::getSampleUrl, candidates)) > 0)
        {
            return true;
        }
        if (Objects.nonNull(formImageService)
                && formImageService.count(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidRolePropSceneFormImage::getImageUrl, candidates)) > 0)
        {
            return true;
        }
        return Objects.nonNull(roleVoiceBindingService)
                && roleVoiceBindingService.count(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL)
                        .and(wrapper -> wrapper.in(AidRoleVoiceBinding::getAvatarUrl, candidates)
                                .or().in(AidRoleVoiceBinding::getSampleUrl, candidates)
                                .or().in(AidRoleVoiceBinding::getReferenceAudioUrl, candidates))) > 0;
    }

    private Set<String> buildCandidates(String url)
    {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(url);
        String relative = mediaUrlResolver.toRelativePath(url);
        if (StrUtil.isNotBlank(relative))
        {
            candidates.add(relative.trim());
        }
        String full = mediaUrlResolver.toFullUrl(url);
        if (StrUtil.isNotBlank(full))
        {
            candidates.add(full.trim());
        }
        addUriPathCandidate(candidates, url);
        addUriPathCandidate(candidates, full);
        candidates.removeIf(candidate -> StrUtil.isBlank(candidate) || candidate.length() < 2);
        return candidates;
    }

    private Set<String> loadReferencedCandidates(Set<String> allCandidates)
    {
        Set<String> referenced = new LinkedHashSet<>();
        List<String> values = new ArrayList<>(allCandidates);
        for (int start = 0; start < values.size(); start += REFERENCE_QUERY_BATCH_SIZE)
        {
            List<String> batch = values.subList(start,
                    Math.min(start + REFERENCE_QUERY_BATCH_SIZE, values.size()));
            collectReferencedBatch(batch, referenced);
        }
        return referenced;
    }

    private void collectReferencedBatch(List<String> candidates, Set<String> referenced)
    {
        List<AidGenRecord> genRecords = aidGenRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getFileUrl)
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .in(AidGenRecord::getFileUrl, candidates));
        genRecords.forEach(row -> addReferenced(referenced, row.getFileUrl()));

        List<AidAudioRecord> audioRecords = aidAudioRecordService.list(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getAudioUrl, AidAudioRecord::getSyncVideoUrl)
                .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                .and(wrapper -> wrapper.in(AidAudioRecord::getAudioUrl, candidates)
                        .or().in(AidAudioRecord::getSyncVideoUrl, candidates)));
        audioRecords.forEach(row -> {
            addReferenced(referenced, row.getAudioUrl());
            addReferenced(referenced, row.getSyncVideoUrl());
        });

        List<AidUserComicAsset> userAssets = aidUserComicAssetService.list(
                Wrappers.<AidUserComicAsset>lambdaQuery()
                        .select(AidUserComicAsset::getImageUrl)
                        .in(AidUserComicAsset::getImageUrl, candidates));
        userAssets.forEach(row -> addReferenced(referenced, row.getImageUrl()));

        List<AidComicAsset> officialAssets = aidComicAssetService.list(
                Wrappers.<AidComicAsset>lambdaQuery()
                        .select(AidComicAsset::getImageUrl)
                        .eq(AidComicAsset::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidComicAsset::getImageUrl, candidates));
        officialAssets.forEach(row -> addReferenced(referenced, row.getImageUrl()));

        List<AidAiVoiceLibrary> voices = aidAiVoiceLibraryService.list(
                Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                        .select(AidAiVoiceLibrary::getSampleUrl)
                        .eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidAiVoiceLibrary::getSampleUrl, candidates));
        voices.forEach(row -> addReferenced(referenced, row.getSampleUrl()));

        // 扩展引用源在对应服务可用时参与精确检查，保持通用清理能力的模块边界。
        if (Objects.nonNull(formImageService))
        {
            List<AidRolePropSceneFormImage> formImages = formImageService.list(
                    Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                            .select(AidRolePropSceneFormImage::getImageUrl)
                            .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                            .in(AidRolePropSceneFormImage::getImageUrl, candidates));
            formImages.forEach(row -> addReferenced(referenced, row.getImageUrl()));
        }

        if (Objects.nonNull(roleVoiceBindingService))
        {
            List<AidRoleVoiceBinding> bindings = roleVoiceBindingService.list(
                    Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                            .select(AidRoleVoiceBinding::getAvatarUrl, AidRoleVoiceBinding::getSampleUrl,
                                    AidRoleVoiceBinding::getReferenceAudioUrl)
                            .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL)
                            .and(wrapper -> wrapper.in(AidRoleVoiceBinding::getAvatarUrl, candidates)
                                    .or().in(AidRoleVoiceBinding::getSampleUrl, candidates)
                                    .or().in(AidRoleVoiceBinding::getReferenceAudioUrl, candidates)));
            bindings.forEach(row -> {
                addReferenced(referenced, row.getAvatarUrl());
                addReferenced(referenced, row.getSampleUrl());
                addReferenced(referenced, row.getReferenceAudioUrl());
            });
        }
    }

    private void addReferenced(Set<String> referenced, String value)
    {
        if (StrUtil.isNotBlank(value))
        {
            referenced.add(value.trim());
        }
    }

    private Set<String> normalizeTargets(Collection<String> fileUrls)
    {
        Set<String> targets = new LinkedHashSet<>();
        if (Objects.isNull(fileUrls))
        {
            return targets;
        }
        for (String url : fileUrls)
        {
            if (StrUtil.isNotBlank(url))
            {
                targets.add(url.trim());
            }
        }
        return targets;
    }

    private void addUriPathCandidate(Set<String> candidates, String value)
    {
        if (StrUtil.isBlank(value))
        {
            return;
        }
        try
        {
            URI uri = URI.create(value.trim());
            String path = uri.getPath();
            if (StrUtil.isNotBlank(path))
            {
                candidates.add(path);
                candidates.add(path.startsWith("/") ? path.substring(1) : "/" + path);
            }
        }
        catch (IllegalArgumentException ignored)
        {
            log.debug("媒体地址不是标准URI，按原值继续引用探测: value={}", value);
        }
    }
}
