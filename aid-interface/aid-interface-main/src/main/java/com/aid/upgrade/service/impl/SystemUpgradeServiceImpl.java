package com.aid.upgrade.service.impl;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.upgrade.client.UpdaterClient;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.DocLinksVo;
import com.aid.upgrade.dto.DeploymentConfigSaveDto;
import com.aid.upgrade.dto.DeploymentConfigTestDto;
import com.aid.upgrade.dto.DeploymentConfigVo;
import com.aid.upgrade.dto.HttpsCertificateUploadDto;
import com.aid.upgrade.dto.OfficialApiStatusVo;
import com.aid.upgrade.dto.OfficialGatewaySaveDto;
import com.aid.upgrade.dto.OfficialGatewaySettingVo;
import com.aid.upgrade.dto.RollbackRequestDto;
import com.aid.upgrade.dto.UpdaterLogVo;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.aid.upgrade.dto.UpgradeManifest;
import com.aid.upgrade.dto.UpgradeHostResourceVo;
import com.aid.upgrade.dto.UpgradeSourceSaveDto;
import com.aid.upgrade.dto.UpgradeSourceSettingVo;
import com.aid.upgrade.dto.UpgradeStatusVo;
import com.aid.upgrade.gateway.OfficialGatewayConfig;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;
import com.aid.upgrade.service.ISystemUpgradeService;
import com.aid.upgrade.util.ManifestSignatureVerifier;
import com.aid.upgrade.util.VersionCompareUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统升级Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemUpgradeServiceImpl implements ISystemUpgradeService {

    /** 被动状态缓存TTL（毫秒）：版本发布为天/周级，被动回源一天一次即可（等价于每日自动拉取）；「检查更新」按钮强制回源不受缓存限制 */
    private static final long MANIFEST_CACHE_TTL_MS = 24 * 60 * 60_000L;

    /** 拉取失败结果的缓存TTL（毫秒）：失败态只短缓存，网络恢复后尽快自动恢复版本展示，又不至于每次进后台都卡在回源超时上 */
    private static final long MANIFEST_ERROR_TTL_MS = 5 * 60_000L;

    /** 更新源访问超时（毫秒） */
    private static final int FETCH_TIMEOUT_MS = 5_000;

    /** CPU不超过4核时，源码在线构建存在资源耗尽风险 */
    private static final int ONLINE_UPGRADE_WARNING_CPU_CORES = 4;

    /** 内存不超过4GiB时，源码在线构建存在OOM或系统失去响应风险 */
    private static final long ONLINE_UPGRADE_WARNING_MEMORY_BYTES = 4L * 1024 * 1024 * 1024;

    /** 更新清单体积上限（字节），防止误配大文件拖垮内存 */
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private final IAidConfigService aidConfigService;
    private final ConfigService configService;
    private final UpdaterClient updaterClient;
    private final OfficialGatewayConfigProvider officialGatewayConfigProvider;
    private final RedisCache redisCache;

    /** 当前AID产品版本（发布构建时注入，与框架版本 aid.version 区分） */
    @Value("${aid.upgrade.current-version:1.0.0}")
    private String currentVersion;

    /** 发布构建注入的升级清单 Ed25519 公钥（Base64 原始公钥）。 */
    @Value("${aid.upgrade.manifest-public-key:}")
    private String manifestPublicKey;

    /** 更新清单快照缓存 */
    private final AtomicReference<ManifestSnapshot> manifestCache = new AtomicReference<>();

    @Override
    public UpgradeStatusVo getStatus(boolean forceRefresh) {
        ManifestSnapshot snapshot = loadManifestSnapshot(forceRefresh);
        Map<String, String> upgradeConfig = readUpgradeConfig();

        UpgradeStatusVo vo = new UpgradeStatusVo();
        vo.setCurrentVersion(currentVersion);
        vo.setCheckedAt(snapshot.checkedAt);
        vo.setCheckError(snapshot.error);
        vo.setManifestUrl(resolveManifestUrl());

        String updaterDownloadUrl = StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL));
        if (Objects.isNull(updaterDownloadUrl)) {
            updaterDownloadUrl = UpgradeConfigKeys.DEFAULT_UPDATER_DOWNLOAD_URL;
        }
        UpgradeManifest manifest = snapshot.manifest;
        if (manifest != null) {
            vo.setLatestVersion(manifest.getProductVersion());
            // 最新版本所属渠道（stable/beta），供页面区分正式版与测试版
            vo.setLatestChannel(StrUtil.blankToDefault(manifest.getChannel(), UpgradeConfigKeys.CHANNEL_STABLE));
            vo.setHasUpdate(VersionCompareUtil.isNewer(manifest.getProductVersion(), currentVersion));
            // 最低直升版本透出给页面：低于该版本时引导用户先升中间版本而不是直接点一键升级
            String minimumVersion = StrUtil.trimToNull(manifest.getMinimumVersion());
            vo.setMinimumVersion(minimumVersion);
            vo.setBelowMinimumVersion(StrUtil.isNotBlank(minimumVersion)
                    && VersionCompareUtil.isNewer(minimumVersion, currentVersion));
            vo.setReleaseNotes(manifest.getReleaseNotes());
            vo.setPublishedAt(manifest.getPublishedAt());
            vo.setRollbackReleases(manifest.getRollbackReleases());
            vo.setDocsUrl(StrUtil.trimToNull(manifest.getDocsUrl()));
            vo.setPromptDocsUrl(StrUtil.trimToNull(manifest.getPromptDocsUrl()));
            if (manifest.getReleasePages() != null) {
                vo.setGiteeReleaseUrl(manifest.getReleasePages().getGitee());
                vo.setGithubReleaseUrl(manifest.getReleasePages().getGithub());
            }
            // 升级器下载地址优先取清单里的最新值，清单未提供时回退本地配置
            if (manifest.getUpdater() != null && StrUtil.isNotBlank(manifest.getUpdater().getDownloadUrl())) {
                updaterDownloadUrl = manifest.getUpdater().getDownloadUrl().trim();
            }
        }
        vo.setUpdaterDownloadUrl(updaterDownloadUrl);
        vo.setUpdater(buildUpdaterStatus(manifest));
        vo.setHostResources(detectHostResources());
        vo.setOfficialApi(buildOfficialApiStatus(manifest));
        return vo;
    }

    /**
     * 获取操作系统可见CPU和物理内存，用于页面在源码升级前做高风险提醒。
     * Docker未设置资源限制时这里返回宿主机容量；设置限制时Java 17返回容器可用容量。
     */
    private UpgradeHostResourceVo detectHostResources() {
        UpgradeHostResourceVo vo = new UpgradeHostResourceVo();
        vo.setWarningCpuCores(ONLINE_UPGRADE_WARNING_CPU_CORES);
        vo.setWarningMemoryBytes(ONLINE_UPGRADE_WARNING_MEMORY_BYTES);
        try {
            java.lang.management.OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
            int cpuCores = operatingSystem.getAvailableProcessors();
            long totalMemoryBytes = 0L;
            if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extendedOperatingSystem) {
                totalMemoryBytes = extendedOperatingSystem.getTotalMemorySize();
            }
            vo.setCpuCores(cpuCores);
            vo.setTotalMemoryBytes(totalMemoryBytes);
            vo.setDetected(cpuCores > 0 && totalMemoryBytes > 0L);
            vo.setOnlineUpgradeRisk(isOnlineUpgradeResourceRisk(cpuCores, totalMemoryBytes));
        } catch (Exception e) {
            log.error("检测在线升级服务器资源失败", e);
            vo.setDetected(false);
            vo.setOnlineUpgradeRisk(false);
        }
        return vo;
    }

    /**
     * 4核4GiB及以下均提示风险；任一资源达到风险线即提醒，避免单项不足导致宕机。
     */
    static boolean isOnlineUpgradeResourceRisk(int cpuCores, long totalMemoryBytes) {
        boolean cpuRisk = cpuCores > 0 && cpuCores <= ONLINE_UPGRADE_WARNING_CPU_CORES;
        boolean memoryRisk = totalMemoryBytes > 0L && totalMemoryBytes <= ONLINE_UPGRADE_WARNING_MEMORY_BYTES;
        return cpuRisk || memoryRisk;
    }

    @Override
    public DocLinksVo getDocLinks() {
        // 只读缓存：教程地址由清单拉取时静默刷新，进后台不额外回源
        DocLinksVo cached = readDocLinksCache();
        if (Objects.nonNull(cached)) {
            return withDocLinkDefaults(cached);
        }
        // 缓存为空（首次启动或Redis被清）时尝试用内存清单快照补一次，仍无则返回内置默认地址
        ManifestSnapshot snapshot = manifestCache.get();
        if (Objects.nonNull(snapshot) && Objects.nonNull(snapshot.manifest)) {
            return withDocLinkDefaults(cacheDocLinks(snapshot.manifest));
        }
        return withDocLinkDefaults(new DocLinksVo());
    }

    /**
     * 读取教程地址缓存；Redis 异常时按缓存未命中处理
     */
    private DocLinksVo readDocLinksCache() {
        try {
            return redisCache.getCacheObject(CacheConstants.UPGRADE_DOC_LINKS_KEY);
        } catch (Exception e) {
            log.error("读取教程地址缓存失败", e);
            return null;
        }
    }

    /**
     * 将清单中的教程地址静默写入缓存（地址变化时自动覆盖）
     *
     * @param manifest 更新清单
     * @return 写入的教程地址集合
     */
    private DocLinksVo cacheDocLinks(UpgradeManifest manifest) {
        DocLinksVo links = new DocLinksVo();
        links.setDocsUrl(StrUtil.trimToNull(manifest.getDocsUrl()));
        links.setPromptDocsUrl(StrUtil.trimToNull(manifest.getPromptDocsUrl()));
        links.setRefreshedAt(DateUtils.getTime());
        try {
            redisCache.setCacheObject(CacheConstants.UPGRADE_DOC_LINKS_KEY, links);
        } catch (Exception e) {
            // 缓存写失败不影响清单主流程，下次拉取会再次刷新
            log.error("刷新教程地址缓存失败", e);
        }
        return links;
    }

    /**
     * 教程地址缺失时回填内置默认地址，保证入口始终可用
     */
    private DocLinksVo withDocLinkDefaults(DocLinksVo links) {
        if (StrUtil.isBlank(links.getDocsUrl())) {
            links.setDocsUrl(UpgradeConfigKeys.DEFAULT_DOCS_URL);
        }
        if (StrUtil.isBlank(links.getPromptDocsUrl())) {
            links.setPromptDocsUrl(UpgradeConfigKeys.DEFAULT_PROMPT_DOCS_URL);
        }
        return links;
    }

    @Override
    public UpdaterLogVo getUpdaterLogs() {
        return updaterClient.readRecentLogs();
    }

    @Override
    public DeploymentConfigVo getDeploymentConfig() {
        UpdaterStatusVo updater = updaterClient.detect();
        if (Objects.isNull(updater.getDeploymentConfig())) {
            log.error("读取部署配置失败, 升级器状态={}, protocol={}", updater.getStatus(), updater.getProtocolVersion());
            throw new ServiceException("部署配置不可用");
        }
        return updater.getDeploymentConfig();
    }

    @Override
    public String validateDeploymentConfig(DeploymentConfigSaveDto saveDto) {
        return submitDeploymentConfigTask("CONFIG_VALIDATE", saveDto);
    }

    @Override
    public String applyDeploymentConfig(DeploymentConfigSaveDto saveDto) {
        return submitDeploymentConfigTask("CONFIG_APPLY", saveDto);
    }

    @Override
    public String rollbackDeploymentConfig() {
        requireConfigCapableUpdater();
        JSONObject task = buildTask("CONFIG_ROLLBACK", currentVersion, currentVersion);
        updaterClient.submitTask(task);
        log.info("已受理部署配置恢复任务");
        return "配置恢复任务已受理";
    }

    @Override
    public String testDeploymentConfig(DeploymentConfigTestDto testDto) {
        requireConfigCapableUpdater();
        if (Objects.isNull(testDto) || CollectionUtil.isEmpty(testDto.getTargets())) {
            log.error("提交配置诊断失败, 诊断项为空");
            throw new ServiceException("请选择检测项");
        }
        Set<String> allowedTargets = Set.of("config", "dns", "certificate", "https", "mysql", "redis", "rocketmq");
        List<String> targets = testDto.getTargets().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());
        if (targets.isEmpty() || !allowedTargets.containsAll(targets)) {
            log.error("提交配置诊断失败, 诊断项非法");
            throw new ServiceException("检测项目错误");
        }
        JSONObject task = buildTask("CONFIG_TEST", currentVersion, currentVersion);
        if (Objects.nonNull(testDto.getConfigPath())) {
            task.put("configPath", testDto.getConfigPath().trim());
        }
        task.put("configValues", buildDeploymentConfigValues(testDto));
        task.put("testTargets", targets);
        updaterClient.submitTask(task);
        log.info("已受理部署配置诊断任务, targets={}", targets);
        return "配置诊断任务已受理";
    }

    @Override
    public String installHttpsCertificate(HttpsCertificateUploadDto uploadDto) {
        requireConfigCapableUpdater();
        if (Objects.isNull(uploadDto) || Objects.isNull(uploadDto.getCertificate())
                || Objects.isNull(uploadDto.getPrivateKey())) {
            log.error("上传 HTTPS 证书失败, 文件不完整");
            throw new ServiceException("证书文件不完整");
        }
        if (StrUtil.isBlank(uploadDto.getHttpsPublicDomain()) || StrUtil.isBlank(uploadDto.getHttpsAdminDomain())) {
            log.error("上传 HTTPS 证书失败, 用户域名或管理域名为空");
            throw new ServiceException("请填写HTTPS域名");
        }
        if (uploadDto.getHttpsPublicDomain().trim().equalsIgnoreCase(uploadDto.getHttpsAdminDomain().trim())) {
            log.error("上传 HTTPS 证书失败, 用户域名与管理域名相同");
            throw new ServiceException("HTTPS域名不能相同");
        }
        JSONObject task = buildTask("CERT_INSTALL", currentVersion, currentVersion);
        if (StrUtil.isNotBlank(uploadDto.getConfigPath())) {
            task.put("configPath", uploadDto.getConfigPath().trim());
        }
        JSONObject values = new JSONObject();
        putDeploymentValue(values, "HTTPS_PUBLIC_DOMAIN", uploadDto.getHttpsPublicDomain(), false);
        putDeploymentValue(values, "HTTPS_ADMIN_DOMAIN", uploadDto.getHttpsAdminDomain(), false);
        task.put("configValues", values);
        updaterClient.submitCertificateTask(task, uploadDto.getCertificate(), uploadDto.getPrivateKey());
        log.info("已受理 HTTPS 证书安装任务");
        return "证书安装任务已受理";
    }

    /**
     * 组装升级器状态：本地探测结果叠加清单中的升级器最新版本比对
     */
    private UpdaterStatusVo buildUpdaterStatus(UpgradeManifest manifest) {
        UpdaterStatusVo updaterStatus = updaterClient.detect();
        if (manifest == null || manifest.getUpdater() == null) {
            return updaterStatus;
        }
        String latestUpdaterVersion = StrUtil.trimToNull(manifest.getUpdater().getVersion());
        if (StrUtil.isBlank(latestUpdaterVersion)) {
            return updaterStatus;
        }
        updaterStatus.setLatestVersion(latestUpdaterVersion);
        // 本地已安装且能读到版本时才比对，未安装场景不提示升级器更新
        if (StrUtil.isNotBlank(updaterStatus.getVersion())) {
            updaterStatus.setHasUpdate(VersionCompareUtil.isNewer(latestUpdaterVersion, updaterStatus.getVersion()));
        }
        // 协议不兼容时即使发布方强制覆盖了同版本升级器，也必须允许先执行升级器自更新。
        if (Objects.equals(updaterStatus.getStatus(), UpdaterClient.STATUS_INCOMPATIBLE)) {
            updaterStatus.setHasUpdate(true);
        }
        return updaterStatus;
    }

    @Override
    public String startUpgrade() {
        UpdaterStatusVo updater = updaterClient.detect();
        if (Objects.equals(updater.getStatus(), UpdaterClient.STATUS_INCOMPATIBLE)) {
            log.error("一键升级被拒绝, 升级器协议版本过低, protocol={}", updater.getProtocolVersion());
            throw new ServiceException("请先升级升级器");
        }
        if (!updater.isReady()) {
            log.error("一键升级被拒绝, 升级器状态={}", updater.getStatus());
            throw new ServiceException("升级器不可用");
        }
        ManifestSnapshot snapshot = loadManifestSnapshot(true);
        UpgradeManifest manifest = snapshot.manifest;
        if (manifest == null || StrUtil.isBlank(manifest.getProductVersion())) {
            log.error("一键升级被拒绝, 更新清单不可用, error={}", snapshot.error);
            throw new ServiceException("更新源不可用");
        }
        if (!VersionCompareUtil.isNewer(manifest.getProductVersion(), currentVersion)) {
            log.info("一键升级被拒绝, 已是最新版本, current={}, remote={}", currentVersion, manifest.getProductVersion());
            throw new ServiceException("已是最新版本");
        }
        String latestUpdaterVersion = manifest.getUpdater() == null
                ? null : StrUtil.trimToNull(manifest.getUpdater().getVersion());
        boolean updaterVersionBehind = StrUtil.isNotBlank(latestUpdaterVersion)
                && StrUtil.isNotBlank(updater.getVersion())
                && VersionCompareUtil.isNewer(latestUpdaterVersion, updater.getVersion());
        if (updaterVersionBehind || Objects.equals(updater.getStatus(), UpdaterClient.STATUS_INCOMPATIBLE)) {
            log.error("系统升级被拒绝, 必须先升级升级器, localUpdater={}, latestUpdater={}, status={}",
                    updater.getVersion(), latestUpdaterVersion, updater.getStatus());
            throw new ServiceException("请先升级升级器");
        }
        // 跨版本保护：升级包只携带自 minimumVersion 起的增量 SQL，低于该版本直升会缺中间脚本
        String minimumVersion = StrUtil.trimToNull(manifest.getMinimumVersion());
        if (StrUtil.isNotBlank(minimumVersion) && VersionCompareUtil.isNewer(minimumVersion, currentVersion)) {
            log.error("一键升级被拒绝, 当前版本低于允许直升的最低版本, current={}, minimum={}, target={}",
                    currentVersion, minimumVersion, manifest.getProductVersion());
            throw new ServiceException("版本过低需逐级升级");
        }
        // 主程序不再下载预构建大包：升级器校验签名清单中的目标版本后，
        // 从 GitHub/Gitee 同一版本标签拉取三端公开源码并在服务器本地构建。
        JSONObject task = buildTask("UPGRADE", currentVersion, manifest.getProductVersion());
        task.put("manifestUrl", resolveManifestUrl());
        task.put("buildFromSource", true);
        task.put("keepBackups", resolveKeepBackups());
        updaterClient.submitTask(task);
        log.info("已受理一键升级任务, current={}, target={}", currentVersion, manifest.getProductVersion());
        return StrUtil.format("升级任务已受理：{} → {}", currentVersion, manifest.getProductVersion());
    }

    @Override
    public String startUpdaterUpgrade() {
        UpdaterStatusVo updater = updaterClient.detect();
        boolean incompatible = Objects.equals(updater.getStatus(), UpdaterClient.STATUS_INCOMPATIBLE);
        if (!updater.isReady() && !incompatible) {
            log.error("升级器在线升级被拒绝, 升级器状态={}", updater.getStatus());
            throw new ServiceException("升级器不可用");
        }
        ManifestSnapshot snapshot = loadManifestSnapshot(true);
        UpgradeManifest manifest = snapshot.manifest;
        String latestUpdaterVersion = null;
        if (manifest != null && manifest.getUpdater() != null) {
            latestUpdaterVersion = StrUtil.trimToNull(manifest.getUpdater().getVersion());
        }
        if (StrUtil.isBlank(latestUpdaterVersion)) {
            log.error("升级器在线升级被拒绝, 更新清单未包含升级器版本, error={}", snapshot.error);
            throw new ServiceException("更新源不可用");
        }
        if (StrUtil.isBlank(updater.getVersion())
                || (!incompatible && !VersionCompareUtil.isNewer(latestUpdaterVersion, updater.getVersion()))) {
            log.info("升级器在线升级被拒绝, 已是最新版本, local={}, remote={}", updater.getVersion(), latestUpdaterVersion);
            throw new ServiceException("升级器已最新");
        }
        // 升级器按自身平台从清单 packages 中选制品，后端仅校验清单已提供制品集合
        if (CollectionUtil.isEmpty(manifest.getUpdater().getPackages())) {
            log.error("升级器在线升级被拒绝, 清单未提供升级器制品集合");
            throw new ServiceException("升级包不可用");
        }
        JSONObject task = buildTask("UPDATER_UPGRADE", updater.getVersion(), latestUpdaterVersion);
        task.put("manifestUrl", resolveManifestUrl());
        task.put("downloadUrl", manifest.getUpdater().getDownloadUrl());
        updaterClient.submitTask(task);
        log.info("已受理升级器在线升级任务, local={}, target={}", updater.getVersion(), latestUpdaterVersion);
        return StrUtil.format("升级器升级任务已受理：{} → {}", updater.getVersion(), latestUpdaterVersion);
    }

    private String submitDeploymentConfigTask(String action, DeploymentConfigSaveDto saveDto) {
        requireConfigCapableUpdater();
        if (Objects.isNull(saveDto)) {
            log.error("提交部署配置任务失败, 参数为空");
            throw new ServiceException("参数不完整");
        }
        JSONObject values = buildDeploymentConfigValues(saveDto);
        JSONObject task = buildTask(action, currentVersion, currentVersion);
        if (Objects.nonNull(saveDto.getConfigPath())) {
            task.put("configPath", saveDto.getConfigPath().trim());
        }
        task.put("configValues", values);
        updaterClient.submitTask(task);
        log.info("已受理部署配置任务, action={}, keys={}", action, values.keySet());
        return Objects.equals(action, "CONFIG_VALIDATE") ? "配置校验任务已受理" : "配置应用任务已受理";
    }

    private void requireConfigCapableUpdater() {
        UpdaterStatusVo updater = updaterClient.detect();
        if (!updater.isReady() || Objects.isNull(updater.getDeploymentConfig())) {
            log.error("部署配置任务被拒绝, 升级器状态={}, protocol={}", updater.getStatus(), updater.getProtocolVersion());
            throw new ServiceException("请先升级升级器");
        }
    }

    private JSONObject buildDeploymentConfigValues(DeploymentConfigSaveDto dto) {
        JSONObject values = new JSONObject();
        putDeploymentValue(values, "HTTP_PORT", dto.getHttpPort(), false);
        putDeploymentValue(values, "ADMIN_PORT", dto.getAdminPort(), false);
        putDeploymentValue(values, "BACKEND_PORT", dto.getBackendPort(), false);
        putDeploymentValue(values, "DATA_ROOT", dto.getDataRoot(), false);
        putDeploymentValue(values, "MYSQL_ROOT_PASSWORD", dto.getMysqlRootPassword(), true);
        putDeploymentValue(values, "MYSQL_PORT", dto.getMysqlPort(), false);
        putDeploymentValue(values, "DB_HOST", dto.getDbHost(), false);
        putDeploymentValue(values, "DB_PORT", dto.getDbPort(), false);
        putDeploymentValue(values, "DB_NAME", dto.getDbName(), false);
        putDeploymentValue(values, "DB_USERNAME", dto.getDbUsername(), false);
        putDeploymentValue(values, "DB_PASSWORD", dto.getDbPassword(), true);
        putDeploymentValue(values, "REDIS_HOST", dto.getRedisHost(), false);
        putDeploymentValue(values, "REDIS_PORT", dto.getRedisPort(), false);
        putDeploymentValue(values, "REDIS_USERNAME", dto.getRedisUsername(), false);
        if (Boolean.TRUE.equals(dto.getClearRedisPassword())) {
            values.put("REDIS_PASSWORD", "");
        } else {
            putDeploymentValue(values, "REDIS_PASSWORD", dto.getRedisPassword(), true);
        }
        putDeploymentValue(values, "REDIS_DATABASE", dto.getRedisDatabase(), false);
        putDeploymentValue(values, "TOKEN_SECRET", dto.getTokenSecret(), true);
        putDeploymentValue(values, "JAVA_OPTS", dto.getJavaOpts(), false);
        putDeploymentValue(values, "DEPENDENCY_INSTALL_MODE", dto.getDependencyInstallMode(), false);
        putDeploymentValue(values, "DEPENDENCY_REGION", dto.getDependencyRegion(), false);
        putDeploymentValue(values, "DOCKER_MIRRORS", dto.getDockerMirrors(), false);
        putDeploymentValue(values, "COMPOSE_PROFILES", dto.getComposeProfiles(), false);
        putDeploymentValue(values, "ROCKETMQ_ENABLED", dto.getRocketmqEnabled(), false);
        putDeploymentValue(values, "ROCKETMQ_NAMESERVER", dto.getRocketmqNameserver(), false);
        putDeploymentValue(values, "ROCKETMQ_FLUSH_DISK_TYPE", dto.getRocketmqFlushDiskType(), false);
        if (Boolean.TRUE.equals(dto.getClearRocketmqCredentials())) {
            values.put("ROCKETMQ_ACCESS_KEY", "");
            values.put("ROCKETMQ_SECRET_KEY", "");
        } else {
            putDeploymentValue(values, "ROCKETMQ_ACCESS_KEY", dto.getRocketmqAccessKey(), true);
            putDeploymentValue(values, "ROCKETMQ_SECRET_KEY", dto.getRocketmqSecretKey(), true);
        }
        putDeploymentValue(values, "HTTPS_ENABLED", dto.getHttpsEnabled(), false);
        putDeploymentValue(values, "HTTPS_PORT", dto.getHttpsPort(), false);
        putDeploymentValue(values, "HTTPS_PUBLIC_DOMAIN", dto.getHttpsPublicDomain(), false);
        putDeploymentValue(values, "HTTPS_ADMIN_DOMAIN", dto.getHttpsAdminDomain(), false);
        putDeploymentValue(values, "HTTPS_CERT_PATH", dto.getHttpsCertPath(), false);
        putDeploymentValue(values, "HTTPS_KEY_PATH", dto.getHttpsKeyPath(), false);
        putDeploymentValue(values, "MYSQL_BUFFER_POOL", dto.getMysqlBufferPool(), false);
        putDeploymentValue(values, "MYSQL_MAX_CONNECTIONS", dto.getMysqlMaxConnections(), false);
        putDeploymentValue(values, "REDIS_MAXMEMORY", dto.getRedisMaxmemory(), false);
        putDeploymentValue(values, "REDIS_MAXMEMORY_POLICY", dto.getRedisMaxmemoryPolicy(), false);
        putDeploymentValue(values, "WEB_NODE_OPTIONS", dto.getWebNodeOptions(), false);
        putDeploymentValue(values, "MQ_NAMESRV_JAVA_OPTS", dto.getMqNamesrvJavaOpts(), false);
        putDeploymentValue(values, "MQ_BROKER_JAVA_OPTS", dto.getMqBrokerJavaOpts(), false);
        return values;
    }

    private void putDeploymentValue(JSONObject values, String key, String value, boolean secret) {
        if (Objects.isNull(value)) {
            return;
        }
        String normalized = value.trim();
        if (secret && StrUtil.isBlank(normalized)) {
            return;
        }
        values.put(key, normalized);
    }

    @Override
    public String rollback(RollbackRequestDto requestDto) {
        if (Objects.isNull(requestDto) || StrUtil.isBlank(requestDto.getTargetVersion())) {
            log.error("版本回退失败, 目标版本为空");
            throw new ServiceException("请选择回退版本");
        }
        UpdaterStatusVo updater = updaterClient.detect();
        if (!updater.isReady()) {
            log.error("版本回退被拒绝, 升级器状态={}", updater.getStatus());
            throw new ServiceException("升级器不可用");
        }
        ManifestSnapshot snapshot = loadManifestSnapshot(true);
        UpgradeManifest.RollbackRelease release = findRollbackRelease(snapshot.manifest,
                requestDto.getTargetVersion().trim());
        if (Objects.isNull(release)) {
            log.error("版本回退被拒绝, 目标版本不在清单中, target={}", requestDto.getTargetVersion());
            throw new ServiceException("回退版本不可用");
        }
        if (!VersionCompareUtil.isNewer(currentVersion, release.getVersion())) {
            log.error("版本回退被拒绝, 目标版本不低于当前版本, current={}, target={}", currentVersion, release.getVersion());
            throw new ServiceException("回退版本不可用");
        }
        if (!Boolean.TRUE.equals(release.getDatabaseCompatible())
                && StrUtil.isBlank(release.getDatabaseRollback())) {
            log.error("版本回退被拒绝, 数据库不兼容且无回退脚本, target={}", release.getVersion());
            throw new ServiceException("数据库不兼容");
        }
        if (!isHttpsUrl(StrUtil.trimToEmpty(release.getPackageUrl()))
                || StrUtil.isBlank(release.getSha256())
                || !release.getSha256().matches("(?i)^[0-9a-f]{64}$")) {
            log.error("版本回退被拒绝, 回退制品信息不完整, target={}", release.getVersion());
            throw new ServiceException("回退包不完整");
        }
        JSONObject task = buildTask("ROLLBACK", currentVersion, release.getVersion());
        task.put("manifestUrl", resolveManifestUrl());
        task.put("packageUrl", release.getPackageUrl());
        task.put("sha256", release.getSha256());
        task.put("databaseCompatible", release.getDatabaseCompatible());
        task.put("databaseRollback", release.getDatabaseRollback());
        task.put("backupRequired", true);
        task.put("keepBackups", resolveKeepBackups());
        updaterClient.submitTask(task);
        log.info("已受理版本回退任务, current={}, target={}", currentVersion, release.getVersion());
        return StrUtil.format("回退任务已受理：{} → {}", currentVersion, release.getVersion());
    }

    private UpgradeManifest.RollbackRelease findRollbackRelease(UpgradeManifest manifest, String targetVersion) {
        if (Objects.isNull(manifest) || manifest.getRollbackReleases() == null) {
            return null;
        }
        return manifest.getRollbackReleases().stream()
                .filter(item -> Objects.equals(targetVersion, item.getVersion()))
                .findFirst()
                .orElse(null);
    }

    private JSONObject buildTask(String action, String sourceVersion, String targetVersion) {
        JSONObject task = new JSONObject();
        task.put("schemaVersion", 1);
        task.put("taskId", UUID.randomUUID().toString());
        task.put("action", action);
        task.put("sourceVersion", sourceVersion);
        task.put("targetVersion", targetVersion);
        task.put("requestedAt", DateUtils.getTime());
        return task;
    }

    /**
     * 读取备份保留份数配置；缺失或越界时回退默认值
     */
    private int resolveKeepBackups() {
        String raw = StrUtil.trimToNull(readUpgradeConfig().get(UpgradeConfigKeys.KEY_KEEP_BACKUPS));
        if (Objects.isNull(raw)) {
            return UpgradeConfigKeys.DEFAULT_KEEP_BACKUPS;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < UpgradeConfigKeys.MIN_KEEP_BACKUPS || value > UpgradeConfigKeys.MAX_KEEP_BACKUPS) {
                return UpgradeConfigKeys.DEFAULT_KEEP_BACKUPS;
            }
            return value;
        } catch (NumberFormatException e) {
            // 配置被写坏时不阻断升级，按默认值执行
            return UpgradeConfigKeys.DEFAULT_KEEP_BACKUPS;
        }
    }

    @Override
    public UpgradeSourceSettingVo getUpgradeSource() {
        Map<String, String> upgradeConfig = readUpgradeConfig();
        UpgradeSourceSettingVo vo = new UpgradeSourceSettingVo();
        // 展示实际生效值：配置缺失时为内置官方默认地址
        vo.setManifestUrl(resolveManifestUrl());
        String updaterDownloadUrl = StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL));
        vo.setUpdaterDownloadUrl(Objects.isNull(updaterDownloadUrl)
                ? UpgradeConfigKeys.DEFAULT_UPDATER_DOWNLOAD_URL : updaterDownloadUrl);
        // 展示生效路径：配置缺失时为与部署脚本一致的内置默认路径
        String healthFile = StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE));
        vo.setUpdaterHealthFile(Objects.isNull(healthFile)
                ? UpgradeConfigKeys.DEFAULT_UPDATER_HEALTH_FILE : healthFile);
        String taskFile = StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_TASK_FILE));
        vo.setUpdaterTaskFile(Objects.isNull(taskFile)
                ? UpgradeConfigKeys.DEFAULT_UPDATER_TASK_FILE : taskFile);
        vo.setReleaseChannel(resolveReleaseChannel());
        vo.setKeepBackups(resolveKeepBackups());
        return vo;
    }

    @Override
    public void saveUpgradeSource(UpgradeSourceSaveDto saveDto) {
        if (Objects.isNull(saveDto)) {
            log.error("保存升级源失败, 参数为空");
            throw new ServiceException("参数不完整");
        }
        // 接收版本渠道：仅允许 stable / all
        String releaseChannel = StrUtil.trimToNull(saveDto.getReleaseChannel());
        if (Objects.nonNull(releaseChannel)
                && !Objects.equals(releaseChannel, UpgradeConfigKeys.CHANNEL_STABLE)
                && !Objects.equals(releaseChannel, UpgradeConfigKeys.CHANNEL_ALL)) {
            log.error("保存升级源失败, 渠道取值非法, releaseChannel={}", releaseChannel);
            throw new ServiceException("渠道取值错误");
        }
        // 备份保留份数：留空按默认值，越界拒绝保存
        int keepBackups = Objects.isNull(saveDto.getKeepBackups())
                ? UpgradeConfigKeys.DEFAULT_KEEP_BACKUPS : saveDto.getKeepBackups();
        if (keepBackups < UpgradeConfigKeys.MIN_KEEP_BACKUPS || keepBackups > UpgradeConfigKeys.MAX_KEEP_BACKUPS) {
            log.error("保存升级源失败, 备份保留份数越界, keepBackups={}", keepBackups);
            throw new ServiceException("备份份数需1-50");
        }
        // 地址/路径类配置为自动维护项（部署脚本与基线默认值负责），仅在显式传入时更新，
        // 页面常规保存不携带这些字段，不会误清高级用户在库中的自定义值
        if (Objects.nonNull(saveDto.getManifestUrl())) {
            String manifestUrl = saveDto.getManifestUrl().trim();
            if (StrUtil.isNotBlank(manifestUrl) && !isHttpsUrl(manifestUrl)) {
                log.error("保存升级源失败, 清单地址格式非法, manifestUrl={}", manifestUrl);
                throw new ServiceException("清单地址格式错误");
            }
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                    UpgradeConfigKeys.KEY_MANIFEST_URL, manifestUrl);
        }
        if (Objects.nonNull(saveDto.getUpdaterDownloadUrl())) {
            String updaterDownloadUrl = saveDto.getUpdaterDownloadUrl().trim();
            if (StrUtil.isNotBlank(updaterDownloadUrl) && !isHttpsUrl(updaterDownloadUrl)) {
                log.error("保存升级源失败, 下载地址格式非法, updaterDownloadUrl={}", updaterDownloadUrl);
                throw new ServiceException("下载地址格式错误");
            }
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                    UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL, updaterDownloadUrl);
        }
        if (Objects.nonNull(saveDto.getUpdaterHealthFile())) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                    UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE, saveDto.getUpdaterHealthFile().trim());
        }
        if (Objects.nonNull(saveDto.getUpdaterTaskFile())) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                    UpgradeConfigKeys.KEY_UPDATER_TASK_FILE, saveDto.getUpdaterTaskFile().trim());
        }
        if (Objects.nonNull(releaseChannel)) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                    UpgradeConfigKeys.KEY_RELEASE_CHANNEL, releaseChannel);
        }
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                UpgradeConfigKeys.KEY_KEEP_BACKUPS, String.valueOf(keepBackups));
        // 更新源变更后清空清单缓存，下一次状态查询立即使用新配置
        manifestCache.set(null);
    }

    @Override
    public OfficialGatewaySettingVo getOfficialGatewaySetting() {
        OfficialGatewayConfig config = officialGatewayConfigProvider.getConfig();
        OfficialGatewaySettingVo vo = new OfficialGatewaySettingVo();
        vo.setEnabled(config.isEnabled());
        vo.setBaseUrl(config.getBaseUrl());
        vo.setHasApiKey(StrUtil.isNotBlank(config.getApiKey()));
        vo.setApiKeyMasked(maskSecret(config.getApiKey()));
        vo.setExcludedModelIds(CollectionUtil.isEmpty(config.getExcludedModelIds())
                ? List.of()
                : config.getExcludedModelIds().stream().sorted().collect(Collectors.toList()));
        vo.setExcludedProviderIds(CollectionUtil.isEmpty(config.getExcludedProviderIds())
                ? List.of()
                : config.getExcludedProviderIds().stream().sorted().collect(Collectors.toList()));
        return vo;
    }

    @Override
    public void saveOfficialGateway(OfficialGatewaySaveDto saveDto) {
        if (Objects.isNull(saveDto)) {
            log.error("保存官方网关失败, 参数为空");
            throw new ServiceException("参数不完整");
        }
        boolean enabled = Boolean.TRUE.equals(saveDto.getEnabled());
        String baseUrl = StrUtil.trimToEmpty(saveDto.getBaseUrl());
        if (enabled && StrUtil.isBlank(baseUrl)) {
            log.error("保存官方网关失败, 启用时地址为空");
            throw new ServiceException("网关地址不能为空");
        }
        if (StrUtil.isNotBlank(baseUrl) && !isHttpUrl(baseUrl)) {
            log.error("保存官方网关失败, 地址格式非法, baseUrl={}", baseUrl);
            throw new ServiceException("地址格式错误");
        }
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                UpgradeConfigKeys.KEY_GATEWAY_ENABLED, String.valueOf(enabled));
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                UpgradeConfigKeys.KEY_GATEWAY_BASE_URL, baseUrl);
        // 密钥留空表示不修改，避免把脱敏串写回冲掉真实密钥
        if (StrUtil.isNotBlank(saveDto.getApiKey())) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                    UpgradeConfigKeys.KEY_GATEWAY_API_KEY, saveDto.getApiKey().trim());
        }
        // 例外模型：null表示不修改，空数组表示清空
        if (Objects.nonNull(saveDto.getExcludedModelIds())) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                    UpgradeConfigKeys.KEY_GATEWAY_EXCLUDED_MODEL_IDS, joinIds(saveDto.getExcludedModelIds()));
        }
        // 例外厂商：null表示不修改，空数组表示清空
        if (Objects.nonNull(saveDto.getExcludedProviderIds())) {
            aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                    UpgradeConfigKeys.KEY_GATEWAY_EXCLUDED_PROVIDER_IDS, joinIds(saveDto.getExcludedProviderIds()));
        }
        officialGatewayConfigProvider.refresh();
    }

    /**
     * 例外ID列表序列化为逗号分隔串（去空、去非正数、去重、升序）
     *
     * @param ids 例外ID列表
     * @return 逗号分隔的ID串
     */
    private String joinIds(List<Long> ids) {
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    @Override
    public OfficialApiStatusVo fetchOfficialApi() {
        ManifestSnapshot snapshot = loadManifestSnapshot(true);
        if (snapshot.manifest == null) {
            log.error("手动获取官方地址失败, error={}", snapshot.error);
            throw new ServiceException("更新源不可用");
        }
        return buildOfficialApiStatus(snapshot.manifest);
    }

    @Override
    public OfficialApiStatusVo applyOfficialApi() {
        OfficialApiStatusVo status = fetchOfficialApi();
        if (StrUtil.isBlank(status.getRemoteBaseUrl())) {
            log.error("应用官方地址失败, 更新清单未包含官方API地址");
            throw new ServiceException("暂无官方地址");
        }
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY,
                UpgradeConfigKeys.KEY_GATEWAY_BASE_URL, status.getRemoteBaseUrl());
        officialGatewayConfigProvider.refresh();
        status.setLocalBaseUrl(status.getRemoteBaseUrl());
        status.setChanged(false);
        return status;
    }

    /**
     * 组装官方API地址同步状态：远端地址与本地不一致才标记提醒
     */
    private OfficialApiStatusVo buildOfficialApiStatus(UpgradeManifest manifest) {
        OfficialApiStatusVo status = new OfficialApiStatusVo();
        String remote = null;
        if (manifest != null && manifest.getOfficialApi() != null) {
            remote = StrUtil.trimToNull(manifest.getOfficialApi().getBaseUrl());
            // 官网地址随清单透出，供管理端展示跳转入口
            status.setWebsiteUrl(StrUtil.trimToNull(manifest.getOfficialApi().getWebsiteUrl()));
        }
        String local = StrUtil.trimToNull(officialGatewayConfigProvider.getConfig().getBaseUrl());
        status.setRemoteBaseUrl(remote);
        status.setLocalBaseUrl(local);
        status.setChanged(StrUtil.isNotBlank(remote) && !Objects.equals(remote, local));
        return status;
    }

    /**
     * 读取或强制刷新更新清单快照；拉取失败不回落旧清单，页面如实提示"无法获取"而不是残留旧版本数据
     */
    private ManifestSnapshot loadManifestSnapshot(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        ManifestSnapshot cached = manifestCache.get();
        if (!forceRefresh && cached != null && (now - cached.fetchedAtMs) < resolveSnapshotTtl(cached)) {
            return cached;
        }
        ManifestSnapshot fresh = fetchManifest();
        manifestCache.set(fresh);
        return fresh;
    }

    /**
     * 快照缓存时长：成功快照按天缓存，失败快照短缓存以便网络恢复后尽快自动恢复展示
     */
    private long resolveSnapshotTtl(ManifestSnapshot snapshot) {
        return Objects.nonNull(snapshot.manifest) ? MANIFEST_CACHE_TTL_MS : MANIFEST_ERROR_TTL_MS;
    }

    /**
     * 拉取统一更新清单（latest.json）：顶层为正式版，可选 beta 字段为测试版；
     * 仅正式版时取顶层；同时接收测试版时取版本更高者（测试字段缺失时回退正式版）
     */
    private ManifestSnapshot fetchManifest() {
        ManifestSnapshot root = fetchManifestFrom(resolveManifestUrl());
        if (Objects.isNull(root.manifest)) {
            return finishSnapshot(root);
        }
        UpgradeManifest selected = selectByReleaseChannel(root.manifest);
        return finishSnapshot(new ManifestSnapshot(selected, root.error, root.checkedAt, root.fetchedAtMs));
    }

    /**
     * 按订阅渠道从统一清单中选出对外展示/升级用的发行信息
     */
    private UpgradeManifest selectByReleaseChannel(UpgradeManifest root) {
        UpgradeManifest stable = toStableRelease(root);
        if (!Objects.equals(resolveReleaseChannel(), UpgradeConfigKeys.CHANNEL_ALL)) {
            return stable;
        }
        UpgradeManifest beta = toBetaRelease(root);
        if (Objects.isNull(beta) || StrUtil.isBlank(beta.getProductVersion())) {
            return stable;
        }
        if (VersionCompareUtil.isNewer(beta.getProductVersion(), stable.getProductVersion())) {
            return beta;
        }
        return stable;
    }

    /**
     * 顶层正式版视图（去掉嵌套 beta，避免下游误用）
     */
    private UpgradeManifest toStableRelease(UpgradeManifest root) {
        UpgradeManifest stable = copyReleaseFields(root);
        if (Objects.isNull(stable.getChannel())) {
            stable.setChannel(UpgradeConfigKeys.CHANNEL_STABLE);
        }
        return stable;
    }

    /**
     * 嵌套测试版视图；继承顶层文档/网关地址（测试字段可省略共享信息）
     */
    private UpgradeManifest toBetaRelease(UpgradeManifest root) {
        if (Objects.isNull(root) || Objects.isNull(root.getBeta())) {
            return null;
        }
        UpgradeManifest beta = copyReleaseFields(root.getBeta());
        if (StrUtil.isBlank(beta.getProductVersion())) {
            return null;
        }
        if (StrUtil.isBlank(beta.getChannel())) {
            beta.setChannel("beta");
        }
        if (StrUtil.isBlank(beta.getDocsUrl())) {
            beta.setDocsUrl(root.getDocsUrl());
        }
        if (StrUtil.isBlank(beta.getPromptDocsUrl())) {
            beta.setPromptDocsUrl(root.getPromptDocsUrl());
        }
        if (Objects.isNull(beta.getOfficialApi()) && Objects.nonNull(root.getOfficialApi())) {
            beta.setOfficialApi(root.getOfficialApi());
        }
        if (StrUtil.isBlank(beta.getProduct())) {
            beta.setProduct(root.getProduct());
        }
        if (Objects.isNull(beta.getSchemaVersion())) {
            beta.setSchemaVersion(root.getSchemaVersion());
        }
        return beta;
    }

    /**
     * 浅拷贝发行字段，不带嵌套 beta
     */
    private UpgradeManifest copyReleaseFields(UpgradeManifest source) {
        UpgradeManifest copy = new UpgradeManifest();
        if (Objects.isNull(source)) {
            return copy;
        }
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setProduct(source.getProduct());
        copy.setProductVersion(source.getProductVersion());
        copy.setChannel(source.getChannel());
        copy.setPublishedAt(source.getPublishedAt());
        copy.setMinimumVersion(source.getMinimumVersion());
        copy.setReleaseNotes(source.getReleaseNotes());
        copy.setPackageUrl(source.getPackageUrl());
        copy.setPackageSha256(source.getPackageSha256());
        copy.setSourceBuild(source.getSourceBuild());
        copy.setReleasePages(source.getReleasePages());
        copy.setDocsUrl(source.getDocsUrl());
        copy.setPromptDocsUrl(source.getPromptDocsUrl());
        copy.setOfficialApi(source.getOfficialApi());
        copy.setUpdater(source.getUpdater());
        copy.setRollbackReleases(source.getRollbackReleases());
        return copy;
    }

    /**
     * 清单选定后的收尾：教程地址随选中清单静默刷新到缓存
     */
    private ManifestSnapshot finishSnapshot(ManifestSnapshot snapshot) {
        if (Objects.nonNull(snapshot.manifest)) {
            cacheDocLinks(snapshot.manifest);
        }
        return snapshot;
    }

    /**
     * 从指定地址拉取并解析一份更新清单
     */
    private ManifestSnapshot fetchManifestFrom(String manifestUrl) {
        long now = System.currentTimeMillis();
        String checkedAt = DateUtils.getTime();
        if (StrUtil.isBlank(manifestUrl)) {
            return new ManifestSnapshot(null, "更新地址未配置", checkedAt, now);
        }
        if (!isHttpsUrl(manifestUrl)) {
            log.error("更新地址格式非法, manifestUrl={}", manifestUrl);
            return new ManifestSnapshot(null, "更新地址格式错误", checkedAt, now);
        }
        try (HttpResponse response = HttpRequest.get(manifestUrl)
                .timeout(FETCH_TIMEOUT_MS)
                // Gitee raw 等发布源会 302 跳转到 CDN，需跟随重定向
                .setFollowRedirects(true)
                .header("Accept", "application/json")
                .execute()) {
            if (!response.isOk()) {
                log.error("更新源响应异常, url={}, status={}", manifestUrl, response.getStatus());
                return new ManifestSnapshot(null, "更新源响应异常(" + response.getStatus() + ")", checkedAt, now);
            }
            String body = response.body();
            if (StrUtil.isBlank(body) || body.length() > MAX_MANIFEST_BYTES) {
                log.error("更新清单内容非法, url={}, length={}", manifestUrl, body == null ? 0 : body.length());
                return new ManifestSnapshot(null, "更新清单内容非法", checkedAt, now);
            }
            if (!ManifestSignatureVerifier.verify(body, manifestPublicKey)) {
                log.error("更新清单签名校验失败, url={}", manifestUrl);
                return new ManifestSnapshot(null, "更新清单签名无效", checkedAt, now);
            }
            UpgradeManifest manifest = JSON.parseObject(body, UpgradeManifest.class);
            if (manifest == null || StrUtil.isBlank(manifest.getProductVersion())) {
                log.error("更新清单缺少版本号, url={}", manifestUrl);
                return new ManifestSnapshot(null, "更新清单缺少版本号", checkedAt, now);
            }
            return new ManifestSnapshot(manifest, null, checkedAt, now);
        } catch (Exception e) {
            log.error("访问更新源失败, url={}", manifestUrl, e);
            return new ManifestSnapshot(null, "更新源访问失败", checkedAt, now);
        }
    }

    /**
     * 读取接收版本渠道配置；缺失或非法时按仅正式版处理
     */
    private String resolveReleaseChannel() {
        String configured = StrUtil.trimToNull(readUpgradeConfig().get(UpgradeConfigKeys.KEY_RELEASE_CHANNEL));
        return Objects.equals(configured, UpgradeConfigKeys.CHANNEL_ALL)
                ? UpgradeConfigKeys.CHANNEL_ALL : UpgradeConfigKeys.CHANNEL_STABLE;
    }

    /**
     * 读取 system_upgrade 分类配置，分类未初始化时返回空Map
     */
    private Map<String, String> readUpgradeConfig() {
        try {
            return configService.getConfigValues(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE);
        } catch (Exception e) {
            // 分类不存在视为未初始化
            return Map.of();
        }
    }

    /**
     * 解析更新清单地址：配置缺失时回退官方默认地址，保证开箱即用无需手工配置
     */
    private String resolveManifestUrl() {
        String configured = StrUtil.trimToNull(readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
        return Objects.isNull(configured) ? UpgradeConfigKeys.DEFAULT_MANIFEST_URL : configured;
    }

    private boolean isHttpUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isHttpsUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return Objects.equals("https", uri.getScheme())
                    && StrUtil.isNotBlank(uri.getHost())
                    && Objects.isNull(uri.getUserInfo());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 密钥脱敏：长度大于8显示前4+****+后4，否则整体打码
     */
    private String maskSecret(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (value.length() > 8) {
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        }
        return "****";
    }

    /**
     * 更新清单快照（含拉取结果与错误信息）
     */
    private static final class ManifestSnapshot {

        private final UpgradeManifest manifest;
        private final String error;
        private final String checkedAt;
        private final long fetchedAtMs;

        private ManifestSnapshot(UpgradeManifest manifest, String error, String checkedAt, long fetchedAtMs) {
            this.manifest = manifest;
            this.error = error;
            this.checkedAt = checkedAt;
            this.fetchedAtMs = fetchedAtMs;
        }
    }
}
