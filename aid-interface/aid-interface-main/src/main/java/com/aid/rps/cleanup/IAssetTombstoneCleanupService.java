package com.aid.rps.cleanup;

/**
 * 自动覆盖资产墓碑清理服务。
 *
 * @author 视觉AID
 */
public interface IAssetTombstoneCleanupService
{
    /**
     * 清理达到保留期的自动覆盖墓碑。
     *
     * @return 已物理清理的主资产数量
     */
    int cleanExpired();
}
