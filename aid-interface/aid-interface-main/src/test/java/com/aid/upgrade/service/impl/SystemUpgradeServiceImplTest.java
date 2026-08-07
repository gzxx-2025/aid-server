package com.aid.upgrade.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.upgrade.client.UpdaterClient;
import com.aid.upgrade.dto.DeploymentConfigSaveDto;
import com.aid.upgrade.dto.DeploymentConfigVo;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;

class SystemUpgradeServiceImplTest {

    private static final long ONE_GIB = 1024L * 1024 * 1024;

    @Test
    void getDeploymentConfigReturnsSnapshotWhenUpdaterHeartbeatIsStopped() {
        UpdaterClient updaterClient = mock(UpdaterClient.class);
        DeploymentConfigVo deploymentConfig = new DeploymentConfigVo();
        deploymentConfig.setMode("docker");
        UpdaterStatusVo updaterStatus = new UpdaterStatusVo();
        updaterStatus.setStatus(UpdaterClient.STATUS_STOPPED);
        updaterStatus.setReady(false);
        updaterStatus.setDeploymentConfig(deploymentConfig);
        when(updaterClient.detect()).thenReturn(updaterStatus);

        SystemUpgradeServiceImpl service = createService(updaterClient);

        assertSame(deploymentConfig, service.getDeploymentConfig());
        assertThrows(ServiceException.class,
                () -> service.validateDeploymentConfig(new DeploymentConfigSaveDto()));
    }

    @Test
    void onlineUpgradeResourceRiskIncludesFourCoreOrFourGibBoundary() {
        assertTrue(SystemUpgradeServiceImpl.isOnlineUpgradeResourceRisk(4, 8 * ONE_GIB));
        assertTrue(SystemUpgradeServiceImpl.isOnlineUpgradeResourceRisk(8, 4 * ONE_GIB));
        assertTrue(SystemUpgradeServiceImpl.isOnlineUpgradeResourceRisk(2, 16 * ONE_GIB));
        assertFalse(SystemUpgradeServiceImpl.isOnlineUpgradeResourceRisk(8, 8 * ONE_GIB));
    }

    private SystemUpgradeServiceImpl createService(UpdaterClient updaterClient) {
        return new SystemUpgradeServiceImpl(
                mock(IAidConfigService.class),
                mock(ConfigService.class),
                updaterClient,
                mock(OfficialGatewayConfigProvider.class),
                mock(RedisCache.class));
    }
}
