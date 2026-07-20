package com.aid.media.cleanup.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidComicAssetService;
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

    /** 媒体 URL 解析器。 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

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
        for (String url : targets)
        {
            try
            {
                // 仍被受保护资产引用的文件不做物理删除。
                if (isReferencedByProtectedAsset(url))
                {
                    log.warn("文件仍被受保护资产(风格图/官方素材/官方音频)引用，跳过删除, url={}", url);
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
     * 判断文件是否仍被受保护资产引用。
     *
     * @param url 文件 URL 或相对路径
     * @return 是否仍被受保护资产引用
     */
    private boolean isReferencedByProtectedAsset(String url)
    {
        // 同时匹配原值、相对路径和完整 URL，兼容不同表的存储格式。
        Set<String> candidates = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(url))
        {
            candidates.add(url.trim());
        }
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
        if (candidates.isEmpty())
        {
            return false;
        }

        LambdaQueryWrapper<AidUserComicAsset> userWrapper = Wrappers.lambdaQuery();
        userWrapper.in(AidUserComicAsset::getImageUrl, candidates);
        if (aidUserComicAssetService.count(userWrapper) > 0)
        {
            return true;
        }

        LambdaQueryWrapper<AidComicAsset> officialWrapper = Wrappers.lambdaQuery();
        officialWrapper.eq(AidComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        officialWrapper.in(AidComicAsset::getImageUrl, candidates);
        if (aidComicAssetService.count(officialWrapper) > 0)
        {
            return true;
        }

        LambdaQueryWrapper<AidAiVoiceLibrary> voiceWrapper = Wrappers.lambdaQuery();
        voiceWrapper.eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL);
        voiceWrapper.in(AidAiVoiceLibrary::getSampleUrl, candidates);
        return aidAiVoiceLibraryService.count(voiceWrapper) > 0;
    }
}
