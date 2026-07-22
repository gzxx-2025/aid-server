package com.aid.upgrade.service.impl;

import java.net.URI;
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
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.upgrade.client.UpdaterClient;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.DocLinksVo;
import com.aid.upgrade.dto.OfficialApiStatusVo;
import com.aid.upgrade.dto.OfficialGatewaySaveDto;
import com.aid.upgrade.dto.OfficialGatewaySettingVo;
import com.aid.upgrade.dto.RollbackRequestDto;
import com.aid.upgrade.dto.UpdaterLogVo;
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

    /** 被动状态缓存TTL（毫秒）：版本发布为天/周级，被动回源一天一次即可（等价于每日自动拉取）；「检查更新」按钮强制回源不受缓存限制 */
    private static final long MANIFEST_CACHE_TTL_MS = 24 * 60 * 60_000L;

    /** 拉取失败结果的缓存TTL（毫秒）：失败态只短缓存，网络恢复后尽快自动恢复版本展示，又不至于每次进后台都卡在回源超时上 */
    private static final long MANIFEST_ERROR_TTL_MS = 5 * 60_000L;

    /** 更新源访问超时（毫秒） */
    private static final int FETCH_TIMEOUT_MS = 5_000;

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
        vo.setOfficialApi(buildOfficialApiStatus(manifest));
        return vo;
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
        // 跨版本保护：升级包只携带自 minimumVersion 起的增量 SQL，低于该版本直升会缺中间脚本
        String minimumVersion = StrUtil.trimToNull(manifest.getMinimumVersion());
        if (StrUtil.isNotBlank(minimumVersion) && VersionCompareUtil.isNewer(minimumVersion, currentVersion)) {
            log.error("一键升级被拒绝, 当前版本低于允许直升的最低版本, current={}, minimum={}, target={}",
                    currentVersion, minimumVersion, manifest.getProductVersion());
            throw new ServiceException("版本过低需逐级升级");
        }
        // 升级包直链与校验值必须齐全，升级器据此下载并校验
        String packageUrl = StrUtil.trimToNull(manifest.getPackageUrl());
        String packageSha256 = StrUtil.trimToNull(manifest.getPackageSha256());
        if (StrUtil.isBlank(packageUrl) || !isHttpsUrl(packageUrl)
                || StrUtil.isBlank(packageSha256) || !packageSha256.matches("(?i)^[0-9a-f]{64}$")) {
            log.error("一键升级被拒绝, 清单缺少有效升级包信息, packageUrl={}, sha256={}", packageUrl, packageSha256);
            throw new ServiceException("升级包不可用");
        }
        JSONObject task = buildTask("UPGRADE", currentVersion, manifest.getProductVersion());
        task.put("manifestUrl", readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
        task.put("packageUrl", packageUrl);
        task.put("sha256", packageSha256);
        task.put("keepBackups", resolveKeepBackups());
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
        if (!isHttpsUrl(StrUtil.trimToEmpty(release.getPackageUrl()))
                || StrUtil.isBlank(release.getSha256())
                || !release.getSha256().matches("(?i)^[0-9a-f]{64}$")) {
            log.error("版本回退被拒绝, 回退制品信息不完整, target={}", release.getVersion());
            throw new ServiceException("回退包不完整");
        }
        JSONObject task = buildTask("ROLLBACK", currentVersion, release.getVersion());
		task.put("manifestUrl", readUpgradeConfig().get(UpgradeConfigKeys.KEY_MANIFEST_URL));
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
     * 按订阅渠道拉取更新清单：仅正式版拉主清单；同时接收测试版时再拉测试清单，
     * 取版本更高者作为最新版本（测试清单不可用时静默回退正式清单）
     */
    private ManifestSnapshot fetchManifest() {
        ManifestSnapshot stable = fetchManifestFrom(resolveManifestUrl());
        if (!Objects.equals(resolveReleaseChannel(), UpgradeConfigKeys.CHANNEL_ALL)) {
            return finishSnapshot(stable);
        }
        String betaUrl = resolveBetaManifestUrl(resolveManifestUrl());
        // 主清单不可用时如实报错（测试清单只是增强，不作为主链路兜底）
        if (StrUtil.isBlank(betaUrl) || Objects.isNull(stable.manifest)) {
            return finishSnapshot(stable);
        }
        ManifestSnapshot beta = fetchManifestFrom(betaUrl);
        if (Objects.nonNull(beta.manifest)
                && VersionCompareUtil.isNewer(beta.manifest.getProductVersion(), stable.manifest.getProductVersion())) {
            return finishSnapshot(beta);
        }
        return finishSnapshot(stable);
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
     * 由正式清单地址推导同目录的测试清单地址；地址不符合发布约定时返回null（跳过测试渠道）
     */
    private String resolveBetaManifestUrl(String manifestUrl) {
        if (StrUtil.isBlank(manifestUrl) || !manifestUrl.contains(UpgradeConfigKeys.STABLE_MANIFEST_FILE)) {
            return null;
        }
        return manifestUrl.replace(UpgradeConfigKeys.STABLE_MANIFEST_FILE, UpgradeConfigKeys.BETA_MANIFEST_FILE);
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
