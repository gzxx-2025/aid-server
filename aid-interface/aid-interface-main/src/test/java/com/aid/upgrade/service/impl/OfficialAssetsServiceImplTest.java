package com.aid.upgrade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import com.aid.upgrade.dto.OfficialAssetsStatusVo;

class OfficialAssetsServiceImplTest {

    private static final String ARCHIVE_NAME = "aid-official-assets_1.0.0-beta.2.tar.gz";

    @TempDir
    Path tempDirectory;

    private String originalProfile;
    private OfficialAssetsServiceImpl service;

    @BeforeEach
    void setUp() {
        originalProfile = AidAppConfig.getProfile();
        new AidAppConfig().setProfile(tempDirectory.toString());
        service = new OfficialAssetsServiceImpl();
    }

    @AfterEach
    void tearDown() {
        new AidAppConfig().setProfile(originalProfile);
    }

    @Test
    void shouldVerifyAndInstallWrappedAssets() throws Exception {
        byte[] content = "official-library".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile archive = archive(content, sha256(content));

        OfficialAssetsStatusVo result = service.install(archive);

        assertTrue(result.isInitialized());
        assertEquals(1, result.getFileCount());
        assertEquals(content.length, result.getTotalBytes());
        assertEquals("official-library", Files.readString(tempDirectory.resolve("aid/lib/test.txt")));
    }

    @Test
    void shouldKeepExistingAssetsWhenChecksumFails() throws Exception {
        Path existing = tempDirectory.resolve("aid/lib/existing.txt");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "keep-me");
        byte[] content = "tampered".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile archive = archive(content, "0".repeat(64));

        assertThrows(ServiceException.class, () -> service.install(archive));

        assertEquals("keep-me", Files.readString(existing));
        assertTrue(Files.notExists(tempDirectory.resolve("aid/lib/test.txt")));
    }

    private MockMultipartFile archive(byte[] content, String checksum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            addEntry(tar, "aid-official-assets_1.0.0-beta.2/files/aid/lib/test.txt", content);
            String manifest = checksum + "  files/aid/lib/test.txt\n";
            addEntry(tar, "aid-official-assets_1.0.0-beta.2/asset-checksums.txt",
                    manifest.getBytes(StandardCharsets.UTF_8));
        }
        return new MockMultipartFile("file", ARCHIVE_NAME, "application/gzip", bytes.toByteArray());
    }

    private void addEntry(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
