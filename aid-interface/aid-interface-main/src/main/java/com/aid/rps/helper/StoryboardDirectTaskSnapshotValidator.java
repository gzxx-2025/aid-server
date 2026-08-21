package com.aid.rps.helper;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.aid.common.exception.ServiceException;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 最新剧本直驱任务的提交快照一致性校验。
 *
 * @author 视觉AID
 */
public final class StoryboardDirectTaskSnapshotValidator
{
    private StoryboardDirectTaskSnapshotValidator()
    {
    }

    /**
     * 校验当前剧本与任务提交快照一致。
     *
     * @param currentScript 当前剧本
     * @param expectedHash 提交快照摘要
     * @param currentHash 当前剧本摘要
     */
    public static void validateScript(String currentScript, String expectedHash, String currentHash)
    {
        if (StrUtil.isBlank(currentScript) || StrUtil.isBlank(expectedHash)
                || !Objects.equals(expectedHash, currentHash))
        {
            throw new ServiceException("剧本已变化，请重试");
        }
    }

    /**
     * 校验当前可用场景资产与任务提交快照一致。
     *
     * @param expectedIds 提交快照场景ID
     * @param actualIds 当前有效场景ID
     */
    public static void validateSceneIds(List<Long> expectedIds, List<Long> actualIds)
    {
        if (CollectionUtil.isEmpty(expectedIds) || CollectionUtil.isEmpty(actualIds))
        {
            throw new ServiceException("场景资产已变化");
        }
        Set<Long> expected = new HashSet<>(expectedIds);
        Set<Long> actual = new HashSet<>(actualIds);
        if (expected.size() != expectedIds.size() || !Objects.equals(expected, actual))
        {
            throw new ServiceException("场景资产已变化");
        }
    }

    /**
     * 解析任务快照的覆盖计划协议。
     *
     * @param snapshotVersion 任务快照版本，空值代表历史任务
     * @param currentVersion 当前服务支持的版本
     * @return true 表示使用语义覆盖计划，false 表示历史原文切片
     */
    public static boolean usesSemanticCoverage(String snapshotVersion, String currentVersion)
    {
        if (StrUtil.isBlank(snapshotVersion))
        {
            return false;
        }
        if (!Objects.equals(snapshotVersion, currentVersion))
        {
            throw new ServiceException("任务版本不兼容");
        }
        return true;
    }
}
