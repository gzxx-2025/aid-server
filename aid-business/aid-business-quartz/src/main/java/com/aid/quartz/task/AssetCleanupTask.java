package com.aid.quartz.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.rps.cleanup.IAssetTombstoneCleanupService;

import lombok.extern.slf4j.Slf4j;

/**
 * 自动覆盖资产墓碑清理任务。
 *
 * @author 视觉AID
 */
@Slf4j
@Component("assetCleanupTask")
public class AssetCleanupTask
{
    @Autowired
    private IAssetTombstoneCleanupService cleanupService;

    /** 清理达到保留期的自动覆盖资产墓碑。 */
    public void cleanExpired()
    {
        try
        {
            cleanupService.cleanExpired();
        }
        catch (Exception e)
        {
            log.error("自动覆盖资产墓碑清理任务异常", e);
        }
    }
}
