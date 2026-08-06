package com.aid.upgrade.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.UpdaterStatusVo;

class UpdaterClientTest {

    @TempDir
    Path tempDir;

    @Test
    void detectUsesEpochHeartbeatWithoutTimezoneConversion() throws Exception {
        Path healthFile = tempDir.resolve("health.json");
        long heartbeatAt = System.currentTimeMillis();
        writeHealth(healthFile, "1970-01-01 00:00:00", heartbeatAt);

        UpdaterStatusVo status = createClient(healthFile).detect();

        assertEquals(UpdaterClient.STATUS_AVAILABLE, status.getStatus());
        assertTrue(status.isReady());
    }

    @Test
    void detectUsesFileModificationTimeForLegacyUpdater() throws Exception {
        Path healthFile = tempDir.resolve("health.json");
        writeHealth(healthFile, "2000-01-01 00:00:00", null);

        UpdaterStatusVo status = createClient(healthFile).detect();

        assertEquals(UpdaterClient.STATUS_AVAILABLE, status.getStatus());
        assertTrue(status.isReady());
    }

    @Test
    void detectPrefersEpochHeartbeatOverFileModificationTime() throws Exception {
        Path healthFile = tempDir.resolve("health.json");
        writeHealth(healthFile, "2000-01-01 00:00:00", System.currentTimeMillis() - 120_000L);

        UpdaterStatusVo status = createClient(healthFile).detect();

        assertEquals(UpdaterClient.STATUS_STOPPED, status.getStatus());
        assertFalse(status.isReady());
    }

    @Test
    void detectRejectsLegacyHealthFileWhenHeartbeatWritesStop() throws Exception {
        Path healthFile = tempDir.resolve("health.json");
        writeHealth(healthFile, "2000-01-01 00:00:00", null);
        Files.setLastModifiedTime(healthFile, FileTime.fromMillis(System.currentTimeMillis() - 120_000L));

        UpdaterStatusVo status = createClient(healthFile).detect();

        assertEquals(UpdaterClient.STATUS_STOPPED, status.getStatus());
        assertFalse(status.isReady());
    }

    private UpdaterClient createClient(Path healthFile) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfigValues(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE))
                .thenReturn(Map.of(UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE, healthFile.toString()));
        return new UpdaterClient(configService);
    }

    private void writeHealth(Path path, String updatedAt, Long updatedAtEpochMs) throws Exception {
        String epochField = updatedAtEpochMs == null ? "" : ",\"updatedAtEpochMs\":" + updatedAtEpochMs;
        String content = "{\"status\":\"RUNNING\",\"version\":\"1.0.0\",\"protocolVersion\":2,"
                + "\"serviceManager\":\"docker\",\"updatedAt\":\"" + updatedAt + "\"" + epochField
                + ",\"configuration\":{\"mode\":\"docker\",\"configPath\":\"/data/aid/config/docker.env\","
                + "\"defaultConfigPath\":\"/data/aid/installer/deploy/docker/.env\","
                + "\"allowedConfigRoot\":\"/data/aid/config\",\"values\":{},\"configuredSecrets\":[]}}";
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
