package com.aid.upgrade.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import com.aid.upgrade.dto.OfficialAssetsStatusVo;
import com.aid.upgrade.service.IOfficialAssetsService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 官方资源包安全校验与原子安装实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class OfficialAssetsServiceImpl implements IOfficialAssetsService {

    /** 官方资源目录名。 */
    private static final String ASSET_DIRECTORY = "aid";

    /** 推荐资源包文件名。 */
    private static final String RECOMMENDED_ARCHIVE = "aid-official-assets_1.0.0-beta.2.tar.gz";

    /** 兼容发布方提供的 tar 与 tar.gz 文件。 */
    private static final Pattern ARCHIVE_NAME_PATTERN = Pattern.compile(
            "^aid-official-assets_[0-9A-Za-z._-]+\\.tar(?:\\.gz)?$");

    /** 压缩包最大 1 GiB。 */
    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L;

    /** 解压后最大 2 GiB，防止压缩炸弹。 */
    private static final long MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L;

    /** 最大归档条目数。 */
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;

    /** 校验文件最大 2 MiB。 */
    private static final int MAX_CHECKSUM_BYTES = 2 * 1024 * 1024;

    /** SHA-256 格式。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** 同一实例只允许执行一个初始化任务。 */
    private final ReentrantLock installLock = new ReentrantLock();

    @Override
    public OfficialAssetsStatusVo getStatus() {
        // 状态查询不创建目录，避免 GET 请求产生文件系统副作用。
        Path targetDirectory = resolveConfiguredProfilePath().resolve(ASSET_DIRECTORY).normalize();
        DirectoryStats stats = readDirectoryStats(targetDirectory);
        return buildStatus(targetDirectory, stats.fileCount(), stats.totalBytes(),
                stats.fileCount() > 0 ? "官方资源已初始化" : "尚未初始化官方资源");
    }

    @Override
    public OfficialAssetsStatusVo install(MultipartFile file) {
        validateArchive(file);
        if (!installLock.tryLock()) {
            log.info("官方资源包初始化被拒绝：已有任务执行中");
            throw new ServiceException("正在初始化");
        }

        Path stagingDirectory = null;
        try {
            Path profileDirectory = resolveProfileDirectory();
            stagingDirectory = Files.createTempDirectory(profileDirectory, ".official-assets-");
            Path stagedAssets = stagingDirectory.resolve(ASSET_DIRECTORY);
            Files.createDirectories(stagedAssets);

            ExtractionStats stats = extractAndVerify(file, stagedAssets);
            deployAtomically(stagedAssets, resolveTargetDirectory());
            log.info("官方资源包初始化成功: fileCount={}, totalBytes={}", stats.fileCount(), stats.totalBytes());
            return buildStatus(resolveTargetDirectory(), stats.fileCount(), stats.totalBytes(), "官方资源初始化成功");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("官方资源包初始化失败", e);
            throw new ServiceException("初始化失败");
        } finally {
            deleteRecursivelyQuietly(stagingDirectory);
            installLock.unlock();
        }
    }

    /** 校验上传文件名称和体积。 */
    private void validateArchive(MultipartFile file) {
        if (Objects.isNull(file) || file.isEmpty()) {
            log.info("官方资源包初始化失败：文件为空");
            throw new ServiceException("请选择资源包");
        }
        String originalName = StrUtil.trimToEmpty(file.getOriginalFilename());
        if (originalName.contains("/") || originalName.contains("\\")
                || !ARCHIVE_NAME_PATTERN.matcher(originalName).matches()) {
            log.info("官方资源包初始化失败：文件名不符合规范, fileName={}", originalName);
            throw new ServiceException("资源包格式错误");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_ARCHIVE_BYTES) {
            log.info("官方资源包初始化失败：文件过大, size={}", file.getSize());
            throw new ServiceException("资源包过大");
        }
    }

    /** 解压 files/aid 内容并逐文件校验 SHA-256。 */
    private ExtractionStats extractAndVerify(MultipartFile file, Path stagedAssets) throws IOException {
        Map<String, String> expectedChecksums = new HashMap<>();
        Map<String, String> actualChecksums = new HashMap<>();
        long totalBytes = 0;
        int entryCount = 0;

        try (InputStream uploadStream = file.getInputStream();
                InputStream archiveStream = wrapCompression(uploadStream, file.getOriginalFilename());
                TarArchiveInputStream tarStream = new TarArchiveInputStream(archiveStream)) {
            TarArchiveEntry entry;
            while ((entry = tarStream.getNextTarEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw invalidArchive("归档条目过多");
                }
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    throw invalidArchive("归档包含特殊文件");
                }

                String entryName = normalizeEntryName(entry.getName());
                if (entryName.endsWith("/asset-checksums.txt") || Objects.equals(entryName, "asset-checksums.txt")) {
                    expectedChecksums.putAll(parseChecksums(readSmallEntry(tarStream, entry)));
                    continue;
                }

                String relativeAssetPath = extractAssetPath(entryName);
                if (Objects.isNull(relativeAssetPath)) {
                    continue;
                }
                Path target = resolveSafeTarget(stagedAssets, relativeAssetPath);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!entry.isFile() || entry.getSize() < 0) {
                    throw invalidArchive("资源条目类型错误");
                }
                if (entry.getSize() > MAX_UNCOMPRESSED_BYTES - totalBytes) {
                    throw invalidArchive("解压内容过大");
                }

                String checksumKey = "files/aid/" + relativeAssetPath;
                if (actualChecksums.containsKey(checksumKey)) {
                    throw invalidArchive("资源路径重复");
                }
                Files.createDirectories(target.getParent());
                MessageDigest digest = newSha256();
                long copied = copyEntry(tarStream, target, digest);
                if (copied != entry.getSize()) {
                    throw invalidArchive("资源大小不一致");
                }
                totalBytes += copied;
                actualChecksums.put(checksumKey, HexFormat.of().formatHex(digest.digest()));
            }
        }

        if (expectedChecksums.isEmpty() || actualChecksums.isEmpty()
                || !expectedChecksums.equals(actualChecksums)) {
            log.error("官方资源包校验失败: expectedCount={}, actualCount={}",
                    expectedChecksums.size(), actualChecksums.size());
            throw new ServiceException("资源包校验失败");
        }
        return new ExtractionStats(actualChecksums.size(), totalBytes);
    }

    /** 根据扩展名包装 gzip 流。 */
    private InputStream wrapCompression(InputStream inputStream, String fileName) throws IOException {
        String normalizedName = StrUtil.trimToEmpty(fileName).toLowerCase();
        return normalizedName.endsWith(".tar.gz")
                ? new GzipCompressorInputStream(inputStream)
                : inputStream;
    }

    /** 读取体积受限的文本条目。 */
    private byte[] readSmallEntry(TarArchiveInputStream tarStream, TarArchiveEntry entry) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > MAX_CHECKSUM_BYTES) {
            throw invalidArchive("校验文件过大");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) entry.getSize());
        byte[] buffer = new byte[8192];
        int read;
        while ((read = tarStream.read(buffer)) != -1) {
            if (outputStream.size() + read > MAX_CHECKSUM_BYTES) {
                throw invalidArchive("校验文件过大");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    /** 解析 asset-checksums.txt。 */
    private Map<String, String> parseChecksums(byte[] bytes) {
        Map<String, String> checksums = new HashMap<>();
        String content = new String(bytes, StandardCharsets.UTF_8);
        for (String line : content.replace("\r", "").split("\n")) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            int separator = line.indexOf("  ");
            if (separator <= 0) {
                throw invalidArchive("校验文件格式错误");
            }
            String checksum = line.substring(0, separator).trim().toLowerCase();
            String objectPath = normalizeEntryName(line.substring(separator + 2).trim());
            if (!SHA256_PATTERN.matcher(checksum).matches() || !objectPath.startsWith("files/aid/")) {
                throw invalidArchive("校验文件格式错误");
            }
            String previous = checksums.put(objectPath, checksum);
            if (Objects.nonNull(previous)) {
                throw invalidArchive("校验路径重复");
            }
        }
        return checksums;
    }

    /** 仅接受 files/aid 或单层发布目录/files/aid 下的资源。 */
    private String extractAssetPath(String entryName) {
        String[] segments = entryName.split("/");
        int markerIndex = -1;
        for (int i = 0; i < segments.length - 1; i++) {
            if (Objects.equals("files", segments[i]) && Objects.equals("aid", segments[i + 1])) {
                markerIndex = i;
                break;
            }
        }
        if (markerIndex < 0 || markerIndex > 1 || markerIndex + 2 >= segments.length) {
            return null;
        }
        StringBuilder relative = new StringBuilder();
        for (int i = markerIndex + 2; i < segments.length; i++) {
            if (StrUtil.isBlank(segments[i]) || Objects.equals(".", segments[i]) || Objects.equals("..", segments[i])) {
                throw invalidArchive("资源路径非法");
            }
            if (relative.length() > 0) {
                relative.append('/');
            }
            relative.append(segments[i]);
        }
        return relative.toString();
    }

    /** 标准化 tar 路径并拒绝绝对路径和反斜杠。 */
    private String normalizeEntryName(String entryName) {
        String normalized = StrUtil.trimToEmpty(entryName);
        if (StrUtil.isBlank(normalized) || normalized.startsWith("/") || normalized.contains("\\")) {
            throw invalidArchive("归档路径非法");
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /** 把相对资源路径约束在临时目录内。 */
    private Path resolveSafeTarget(Path stagedAssets, String relativeAssetPath) {
        Path target = stagedAssets.resolve(relativeAssetPath).normalize();
        if (!target.startsWith(stagedAssets)) {
            throw invalidArchive("资源路径非法");
        }
        return target;
    }

    /** 流式复制当前 tar 条目并计算摘要。 */
    private long copyEntry(TarArchiveInputStream tarStream, Path target, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        try (java.io.OutputStream outputStream = Files.newOutputStream(target)) {
            int read;
            while ((read = tarStream.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_UNCOMPRESSED_BYTES) {
                    throw invalidArchive("资源文件过大");
                }
                digest.update(buffer, 0, read);
                outputStream.write(buffer, 0, read);
            }
        }
        return copied;
    }

    /** 原子替换资源目录，失败时恢复旧目录。 */
    private void deployAtomically(Path stagedAssets, Path targetDirectory) throws IOException {
        if (Files.isSymbolicLink(targetDirectory)) {
            throw invalidArchive("目标目录非法");
        }
        Path backupDirectory = targetDirectory.resolveSibling(".aid-backup-" + UUID.randomUUID());
        boolean oldMoved = false;
        try {
            if (Files.exists(targetDirectory)) {
                moveDirectory(targetDirectory, backupDirectory);
                oldMoved = true;
            }
            moveDirectory(stagedAssets, targetDirectory);
            deleteRecursivelyQuietly(backupDirectory);
        } catch (IOException | RuntimeException e) {
            if (oldMoved) {
                deleteRecursivelyQuietly(targetDirectory);
            }
            if (oldMoved && Files.exists(backupDirectory)) {
                moveDirectory(backupDirectory, targetDirectory);
            }
            throw e;
        }
    }

    /** 优先使用同文件系统原子移动。 */
    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /** 查询已安装目录的文件数量与体积。 */
    private DirectoryStats readDirectoryStats(Path directory) {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            return new DirectoryStats(0, 0);
        }
        long[] stats = new long[2];
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        stats[0]++;
                        stats[1] += attributes.size();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return new DirectoryStats(stats[0], stats[1]);
        } catch (IOException e) {
            log.warn("读取官方资源目录状态失败: path={}", directory);
            return new DirectoryStats(0, 0);
        }
    }

    /** 构造管理端状态。 */
    private OfficialAssetsStatusVo buildStatus(
            Path targetDirectory, long fileCount, long totalBytes, String message) {
        OfficialAssetsStatusVo status = new OfficialAssetsStatusVo();
        status.setInitialized(fileCount > 0);
        status.setFileCount(fileCount);
        status.setTotalBytes(totalBytes);
        status.setTargetDirectory(targetDirectory.toString());
        status.setRecommendedArchiveName(RECOMMENDED_ARCHIVE);
        status.setMaxUploadBytes(MAX_ARCHIVE_BYTES);
        status.setManualCommand(buildManualCommand());
        status.setMessage(message);
        return status;
    }

    /** 返回默认 Linux 部署的手工兜底命令。 */
    private String buildManualCommand() {
        return "sudo mkdir -p /data/aid/uploadPath && "
                + "sudo tar -xf /data/aid/aid-official-assets_1.0.0-beta.2.tar.gz "
                + "-C /data/aid/uploadPath --strip-components=2 --wildcards '*/files/aid/*'";
    }

    private Path resolveProfileDirectory() {
        Path directory = resolveConfiguredProfilePath();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            log.error("创建资源根目录失败: path={}", directory, e);
            throw new ServiceException("存储目录不可写");
        }
        return directory;
    }

    /** 解析配置的存储根目录，不创建文件。 */
    private Path resolveConfiguredProfilePath() {
        String profile = AidAppConfig.getProfile();
        if (StrUtil.isBlank(profile)) {
            log.error("官方资源包初始化失败：aid.profile 未配置");
            throw new ServiceException("存储目录未配置");
        }
        return Path.of(profile).toAbsolutePath().normalize();
    }

    private Path resolveTargetDirectory() {
        return resolveProfileDirectory().resolve(ASSET_DIRECTORY).normalize();
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ServiceException invalidArchive(String reason) {
        log.error("官方资源包无效: {}", reason);
        return new ServiceException("资源包无效");
    }

    /** 安静清理临时目录或旧备份。 */
    private void deleteRecursivelyQuietly(Path directory) {
        if (Objects.isNull(directory) || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    if (Objects.nonNull(exception)) {
                        throw exception;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("清理官方资源临时目录失败: path={}", directory);
        }
    }

    private record ExtractionStats(long fileCount, long totalBytes) {
    }

    private record DirectoryStats(long fileCount, long totalBytes) {
    }
}
