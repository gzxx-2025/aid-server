package com.aid.upgrade.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.upgrade.client.UpdaterClient;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.OfficialApiStatusVo;
import com.aid.upgrade.dto.OfficialGatewaySaveDto;
import com.aid.upgrade.dto.OfficialGatewaySettingVo;
import com.aid.upgrade.dto.RollbackRequestDto;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.aid.upgrade.dto.UpgradeManifest;
import com.aid.upgrade.dto.UpgradeSourceSaveDto;
import com.aid.upgrade.dto.UpgradeSourceSettingVo;
import com.aid.upgrade.dto.UpgradeStatusVo;
import com.aid.upgrade.gateway.OfficialGatewayConfig;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;
import com.aid.upgrade.service.ISystemUpgradeService;
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

    /** 被动状态缓存TTL（毫秒）：版本发布为天/周级，1小时时效足够，同时把对更新源的回源压到最低；手动检查更新不受缓存限制 */
    private static final long MANIFEST_CACHE_TTL_MS = 60 * 60_000L;

    /** 更新源访问超时（毫秒） */
    private static final int FETCH_TIMEOUT_MS = 5_000;

    /** 更新清单体积上限（字节），防止误配大文件拖垮内存 */
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private final IAidConfigService aidConfigService;
    private final ConfigService configService;
    private final UpdaterClient updaterClient;
    private final OfficialGatewayConfigProvider officialGatewayConfigProvider;

    /** 当前AID产品版本（发布构建时注入，与框架版本 aid.version 区分） */
    @Value("${aid.upgrade.current-version:1.0.0}")
    private String currentVersion;

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
        vo.setManifestUrl(StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_MANIFEST_URL)));

        String updaterDownloadUrl = StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL));
        UpgradeManifest manifest = snapshot.manifest;
        if (manifest != null) {
            vo.setLatestVersion(manifest.getProductVersion());
            vo.setHasUpdate(VersionCompareUtil.isNewer(manifest.getProductVersion(), currentVersion));
            vo.setReleaseNotes(manifest.getReleaseNotes());
            vo.setPublishedAt(manifest.getPublishedAt());
            vo.setRollbackReleases(manifest.getRollbackReleases());
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
        vo.setOfficialApi(buildOfficialApiStatus(manifest));
        return vo;
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
        return updaterStatus;
    }

    @Override
    public String startUpgrade() {
        UpdaterStatusVo updater = updaterClient.detect();
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
        // 升级包直链与校验值必须齐全，升级器据此下载并校验
        String packageUrl = StrUtil.trimToNull(manifest.getPackageUrl());
        String packageSha256 = StrUtil.trimToNull(manifest.getPackageSha256());
        if (StrUtil.isBlank(packageUrl) || !isHttpUrl(packageUrl)
                || StrUtil.isBlank(packageSha256) || !packageSha256.matches("(?i)^[0-9a-f]{64}$")) {
            log.error("一键升级被拒绝, 清单缺少有效升级包信息, packageUrl={}, sha256={}", packageUrl, packageSha256);
            throw new ServiceException("升级包不可用");
        }
        JSONObject task = buildTask("UPGRADE", currentVersion, manifest.getProductVersion());
        task.put("manifestUrl", readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
        task.put("packageUrl", packageUrl);
        task.put("sha256", packageSha256);
        updaterClient.submitTask(task);
        log.info("已受理一键升级任务, current={}, target={}", currentVersion, manifest.getProductVersion());
        return StrUtil.format("升级任务已受理：{} → {}", currentVersion, manifest.getProductVersion());
    }

    @Override
    public String startUpdaterUpgrade() {
        UpdaterStatusVo updater = updaterClient.detect();
        if (!updater.isReady()) {
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
                || !VersionCompareUtil.isNewer(latestUpdaterVersion, updater.getVersion())) {
            log.info("升级器在线升级被拒绝, 已是最新版本, local={}, remote={}", updater.getVersion(), latestUpdaterVersion);
            throw new ServiceException("升级器已最新");
        }
        // 升级器按自身平台从清单 packages 中选制品，后端仅校验清单已提供制品集合
        if (CollectionUtil.isEmpty(manifest.getUpdater().getPackages())) {
            log.error("升级器在线升级被拒绝, 清单未提供升级器制品集合");
            throw new ServiceException("升级包不可用");
        }
        JSONObject task = buildTask("UPDATER_UPGRADE", updater.getVersion(), latestUpdaterVersion);
        task.put("manifestUrl", readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
        task.put("downloadUrl", manifest.getUpdater().getDownloadUrl());
        updaterClient.submitTask(task);
        log.info("已受理升级器在线升级任务, local={}, target={}", updater.getVersion(), latestUpdaterVersion);
        return StrUtil.format("升级器升级任务已受理：{} → {}", updater.getVersion(), latestUpdaterVersion);
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
        if (!isHttpUrl(StrUtil.trimToEmpty(release.getPackageUrl()))
                || StrUtil.isBlank(release.getSha256())
                || !release.getSha256().matches("(?i)^[0-9a-f]{64}$")) {
            log.error("版本回退被拒绝, 回退制品信息不完整, target={}", release.getVersion());
            throw new ServiceException("回退包不完整");
        }
        JSONObject task = buildTask("ROLLBACK", currentVersion, release.getVersion());
        task.put("packageUrl", release.getPackageUrl());
        task.put("sha256", release.getSha256());
        task.put("databaseCompatible", release.getDatabaseCompatible());
        task.put("databaseRollback", release.getDatabaseRollback());
        task.put("backupRequired", true);
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

    @Override
    public UpgradeSourceSettingVo getUpgradeSource() {
        Map<String, String> upgradeConfig = readUpgradeConfig();
        UpgradeSourceSettingVo vo = new UpgradeSourceSettingVo();
        vo.setManifestUrl(StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_MANIFEST_URL)));
        vo.setUpdaterDownloadUrl(StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL)));
        vo.setUpdaterHealthFile(StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE)));
        vo.setUpdaterTaskFile(StrUtil.trimToNull(upgradeConfig.get(UpgradeConfigKeys.KEY_UPDATER_TASK_FILE)));
        return vo;
    }

    @Override
    public void saveUpgradeSource(UpgradeSourceSaveDto saveDto) {
        if (Objects.isNull(saveDto)) {
            log.error("保存升级源失败, 参数为空");
            throw new ServiceException("参数不完整");
        }
        String manifestUrl = StrUtil.trimToEmpty(saveDto.getManifestUrl());
        String updaterDownloadUrl = StrUtil.trimToEmpty(saveDto.getUpdaterDownloadUrl());
        String updaterHealthFile = StrUtil.trimToEmpty(saveDto.getUpdaterHealthFile());
        String updaterTaskFile = StrUtil.trimToEmpty(saveDto.getUpdaterTaskFile());
        if (StrUtil.isNotBlank(manifestUrl) && !isHttpUrl(manifestUrl)) {
            log.error("保存升级源失败, 清单地址格式非法, manifestUrl={}", manifestUrl);
            throw new ServiceException("清单地址格式错误");
        }
        if (StrUtil.isNotBlank(updaterDownloadUrl) && !isHttpUrl(updaterDownloadUrl)) {
            log.error("保存升级源失败, 下载地址格式非法, updaterDownloadUrl={}", updaterDownloadUrl);
            throw new ServiceException("下载地址格式错误");
        }
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                UpgradeConfigKeys.KEY_MANIFEST_URL, manifestUrl);
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                UpgradeConfigKeys.KEY_UPDATER_DOWNLOAD_URL, updaterDownloadUrl);
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE, updaterHealthFile);
        aidConfigService.upsertConfigValue(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE,
                UpgradeConfigKeys.KEY_UPDATER_TASK_FILE, updaterTaskFile);
        // 更新源变更后清空清单缓存，下一次状态查询立即使用新地址
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
     * 读取或强制刷新更新清单快照
     */
    private ManifestSnapshot loadManifestSnapshot(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        ManifestSnapshot cached = manifestCache.get();
        if (!forceRefresh && cached != null && (now - cached.fetchedAtMs) < MANIFEST_CACHE_TTL_MS) {
            return cached;
        }
        ManifestSnapshot fresh = fetchManifest();
        // 拉取失败时保留上一份成功清单，只更新错误信息，避免瞬时网络抖动清空版本展示
        if (fresh.manifest == null && cached != null && cached.manifest != null) {
            fresh = new ManifestSnapshot(cached.manifest, fresh.error, fresh.checkedAt, fresh.fetchedAtMs);
        }
        manifestCache.set(fresh);
        return fresh;
    }

    /**
     * 从配置的更新地址拉取并解析清单
     */
    private ManifestSnapshot fetchManifest() {
        long now = System.currentTimeMillis();
        String checkedAt = DateUtils.getTime();
        String manifestUrl = StrUtil.trimToNull(readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
        if (StrUtil.isBlank(manifestUrl)) {
            return new ManifestSnapshot(null, "更新地址未配置", checkedAt, now);
        }
        if (!isHttpUrl(manifestUrl)) {
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

    private boolean isHttpUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
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
