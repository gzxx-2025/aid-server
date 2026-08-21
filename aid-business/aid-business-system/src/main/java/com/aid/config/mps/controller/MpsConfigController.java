package com.aid.config.mps.controller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.config.mps.dto.MpsConfigSaveRequest;
import com.aid.compose.config.FfmpegRuntimeValidator;
import com.aid.compose.config.FfmpegRuntimeValidator.FontValidationException;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS、阿里云 IMS 与本地 FFmpeg 媒体处理配置读写（后台）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/mps")
@RequiredArgsConstructor
public class MpsConfigController extends BaseController {

    /** 配置分类 */
    private static final String CATEGORY = "mps";

    /** 脱敏串标记：包含该串视为未修改，保留原密钥 */
    private static final String MASK_FLAG = "****";
    private static final Set<String> PROCESS_MODES = Set.of("tencent-mps", "aliyun-ims", "local-ffmpeg");
    private static final Set<String> ALIYUN_IMS_REGIONS = Set.of(
            "cn-shanghai", "cn-beijing", "cn-shenzhen", "cn-hangzhou",
            "ap-southeast-1", "us-west-1");

    /** 通用配置读取服务 */
    private final ConfigService configService;

    /** 通用配置写入服务（写 aid_config） */
    private final IAidConfigService aidConfigService;

    /** 保存后立即刷新运行时缓存，避免新任务在短暂缓存窗口内继续读取旧处理方式。 */
    private final MpsConfigManager mpsConfigManager;

    /** 自定义 FFmpeg 绝对路径能力校验。 */
    private final FfmpegRuntimeValidator ffmpegRuntimeValidator;

    /**
     * 读取当前媒体处理配置（密钥脱敏）。
     *
     * @return 脱敏后的配置 Map，放在 data 字段返回
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/config")
    public AjaxResult getConfig() {
        Map<String, String> config = configService.getConfigValues(CATEGORY);
        Map<String, String> result = CollectionUtil.isEmpty(config) ? new HashMap<>() : new HashMap<>(config);
        // 密钥脱敏：避免 SecretId/SecretKey 明文回传到后台 UI
        maskInPlace(result, "secretId");
        maskInPlace(result, "secretKey");
        maskInPlace(result, "tencentSecretId");
        maskInPlace(result, "tencentSecretKey");
        maskInPlace(result, "aliyunAccessKeyId");
        maskInPlace(result, "aliyunAccessKeySecret");
        result.putIfAbsent("processMode", "tencent-mps");
        if (StrUtil.isBlank(result.get("ffmpegFontFile"))) {
            result.put("ffmpegFontFile", MpsProperties.DEFAULT_CJK_FONT_PATH);
        }
        return AjaxResult.success(result);
    }

    /**
     * 整组保存媒体处理配置到 aid_config。
     *
     * 字段为 null 的不更新；密钥提交脱敏串（含 ****）视为未修改，保留原值。
     *
     * @param request 配置保存请求
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "视频合成配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult saveConfig(@RequestBody MpsConfigSaveRequest request) {
        if (Objects.isNull(request)) {
            return AjaxResult.error("参数不能为空");
        }
        try {
            validateRequest(request);
            saveBoolean("enabled", request.getEnabled());
            saveString("processMode", request.getProcessMode());
            // 新字段按厂商隔离；旧字段只读兼容，不再写入。
            saveSecret("tencentSecretId", firstNonNull(request.getTencentSecretId(), request.getSecretId()));
            saveSecret("tencentSecretKey", firstNonNull(request.getTencentSecretKey(), request.getSecretKey()));
            saveString("tencentRegion", firstNonNull(request.getTencentRegion(), request.getRegion()));
            saveString("tencentCallbackUrl", request.getTencentCallbackUrl());
            saveInteger("tencentMaxConcurrency", request.getTencentMaxConcurrency());
            saveSecret("aliyunAccessKeyId", request.getAliyunAccessKeyId());
            saveSecret("aliyunAccessKeySecret", request.getAliyunAccessKeySecret());
            saveString("aliyunRegion", request.getAliyunRegion());
            saveString("aliyunCallbackUrl", request.getAliyunCallbackUrl());
            saveInteger("aliyunMaxConcurrency", request.getAliyunMaxConcurrency());
            saveString("ffmpegPath", request.getFfmpegPath());
            saveString("ffprobePath", request.getFfprobePath());
            saveString("ffmpegTempDir", request.getFfmpegTempDir());
            saveInteger("ffmpegTimeoutSeconds", request.getFfmpegTimeoutSeconds());
            saveInteger("ffmpegMaxConcurrency", request.getFfmpegMaxConcurrency());
            saveInteger("ffmpegThreads", request.getFfmpegThreads());
            saveString("ffmpegFontFile", request.getFfmpegFontFile());
            saveString("outputDir", request.getOutputDir());
            saveString("outputResolution", request.getOutputResolution());
            saveString("codec", request.getCodec());
            saveDecimal("tencentPriceSd", request.getTencentPriceSd());
            saveDecimal("tencentPriceHd", request.getTencentPriceHd());
            saveDecimal("tencentPriceFhd", request.getTencentPriceFhd());
            saveDecimal("tencentPrice2k", request.getTencentPrice2k());
            saveDecimal("tencentPrice4k", request.getTencentPrice4k());
            saveDecimal("aliyunPriceSd", request.getAliyunPriceSd());
            saveDecimal("aliyunPriceHd", request.getAliyunPriceHd());
            saveDecimal("aliyunPriceFhd", request.getAliyunPriceFhd());
            saveDecimal("aliyunPrice2k", request.getAliyunPrice2k());
            saveDecimal("aliyunPrice4k", request.getAliyunPrice4k());
            saveDecimal("localUnitPrice", request.getLocalUnitPrice());
            saveInteger("creditRate", request.getCreditRate());
            saveDecimal("profitMultiplier", request.getProfitMultiplier());
            refreshAfterCommit();
            return AjaxResult.success("保存成功");
        } catch (IllegalArgumentException e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            // 写库异常前打日志再抛友好提示
            log.error("保存视频合成配置失败, error={}", e.getMessage(), e);
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error("保存失败");
        }
    }

    private void validateRequest(MpsConfigSaveRequest request) {
        String mode = StrUtil.blankToDefault(request.getProcessMode(), "tencent-mps").trim().toLowerCase();
        if (!PROCESS_MODES.contains(mode)) {
            log.error("保存媒体处理配置失败: 不支持的处理方式 mode={}", mode);
            throw new IllegalArgumentException("方式错误");
        }
        request.setProcessMode(mode);
        validateCommonFields(request, mode);
        Map<String, String> storage = configService.getConfigValues("oss");
        if (storage == null)
        {
            storage = Map.of();
        }
        String storageMode = StrUtil.blankToDefault(storage.get("uploadMode"), "local").trim().toLowerCase();
        if ("tencent-mps".equals(mode)) {
            validatePositive(request.getTencentMaxConcurrency(), "腾讯并发");
            if (!"cos".equals(storageMode)) {
                log.error("腾讯MPS仅允许COS存储, storageMode={}", storageMode);
                throw new IllegalArgumentException("存储不匹配");
            }
            String region = StrUtil.blankToDefault(firstNonNull(request.getTencentRegion(), request.getRegion()), "");
            region = region.trim().toLowerCase();
            if (!region.matches("[a-z0-9-]{3,64}")) {
                log.error("腾讯MPS地域格式非法, region={}", region);
                throw new IllegalArgumentException("地域格式错误");
            }
            request.setTencentRegion(region);
            if (!region.equalsIgnoreCase(StrUtil.blankToDefault(storage.get("cosRegion"), ""))) {
                log.error("腾讯MPS与COS地域不一致, mpsRegion={}, cosRegion={}", region, storage.get("cosRegion"));
                throw new IllegalArgumentException("地域不一致");
            }
            validateEnabledSecrets(request.getEnabled(),
                    firstNonNull(request.getTencentSecretId(), request.getSecretId()),
                    firstNonNull(request.getTencentSecretKey(), request.getSecretKey()),
                    firstNotBlank(configService.getConfigValue(CATEGORY, "tencentSecretId"),
                            configService.getConfigValue(CATEGORY, "secretId")),
                    firstNotBlank(configService.getConfigValue(CATEGORY, "tencentSecretKey"),
                            configService.getConfigValue(CATEGORY, "secretKey")), "腾讯密钥");
            validateCallback(request.getTencentCallbackUrl());
        } else if ("aliyun-ims".equals(mode)) {
            validatePositive(request.getAliyunMaxConcurrency(), "阿里并发");
            if (!"oss".equals(storageMode)) {
                log.error("阿里IMS仅允许OSS存储, storageMode={}", storageMode);
                throw new IllegalArgumentException("存储不匹配");
            }
            String imsRegion = StrUtil.blankToDefault(request.getAliyunRegion(), "").trim().toLowerCase();
            if (!ALIYUN_IMS_REGIONS.contains(imsRegion)) {
                log.error("阿里IMS地域不支持云剪辑, imsRegion={}", imsRegion);
                throw new IllegalArgumentException("地域不支持");
            }
            request.setAliyunRegion(imsRegion);
            String ossRegion = parseOssRegion(storage.get("endpoint"));
            if (StrUtil.isBlank(ossRegion) || !imsRegion.equalsIgnoreCase(ossRegion)) {
                log.error("阿里IMS与OSS地域不一致, imsRegion={}, ossRegion={}, endpoint={}",
                        imsRegion, ossRegion, storage.get("endpoint"));
                throw new IllegalArgumentException("地域不一致");
            }
            validateEnabledSecrets(request.getEnabled(), request.getAliyunAccessKeyId(),
                    request.getAliyunAccessKeySecret(),
                    configService.getConfigValue(CATEGORY, "aliyunAccessKeyId"),
                    configService.getConfigValue(CATEGORY, "aliyunAccessKeySecret"), "阿里密钥");
            validateCallback(request.getAliyunCallbackUrl());
        } else {
            validatePositive(request.getFfmpegMaxConcurrency(), "本地并发");
            validateFfmpegRuntime(request);
            if (request.getFfmpegTimeoutSeconds() == null || request.getFfmpegTimeoutSeconds() < 60
                    || request.getFfmpegTimeoutSeconds() > 21600
                    || request.getFfmpegThreads() == null || request.getFfmpegThreads() < 0
                    || request.getFfmpegThreads() > 256) {
                log.error("本地FFmpeg运行参数非法, timeout={}, threads={}",
                        request.getFfmpegTimeoutSeconds(), request.getFfmpegThreads());
                throw new IllegalArgumentException("运行参数错误");
            }
        }
        validateNonNegative(request.getTencentPriceSd(), request.getTencentPriceHd(), request.getTencentPriceFhd(),
                request.getTencentPrice2k(), request.getTencentPrice4k(), request.getAliyunPriceSd(),
                request.getAliyunPriceHd(), request.getAliyunPriceFhd(), request.getAliyunPrice2k(),
                request.getAliyunPrice4k(), request.getLocalUnitPrice());
    }

    private void validateFfmpegRuntime(MpsConfigSaveRequest request) {
        String ffmpegPath = StrUtil.blankToDefault(request.getFfmpegPath(), "").trim();
        String ffprobePath = StrUtil.blankToDefault(request.getFfprobePath(), "").trim();
        boolean defaultRuntime = (StrUtil.isBlank(ffmpegPath) && StrUtil.isBlank(ffprobePath))
                || ("ffmpeg".equals(ffmpegPath) && "ffprobe".equals(ffprobePath))
                || (MpsProperties.DEFAULT_FFMPEG_PATH.equals(ffmpegPath)
                    && MpsProperties.DEFAULT_FFPROBE_PATH.equals(ffprobePath));
        if (defaultRuntime) {
            request.setFfmpegPath(MpsProperties.DEFAULT_FFMPEG_PATH);
            request.setFfprobePath(MpsProperties.DEFAULT_FFPROBE_PATH);
            ffmpegPath = MpsProperties.DEFAULT_FFMPEG_PATH;
            ffprobePath = MpsProperties.DEFAULT_FFPROBE_PATH;
        } else if (StrUtil.hasBlank(ffmpegPath, ffprobePath)) {
            log.error("自定义FFmpeg与FFprobe路径必须同时填写");
            throw new IllegalArgumentException("路径未配置");
        } else {
            try {
                ffmpegRuntimeValidator.validate(ffmpegPath, ffprobePath);
                request.setFfmpegPath(ffmpegPath);
                request.setFfprobePath(ffprobePath);
            } catch (Exception e) {
                log.error("自定义FFmpeg运行时校验失败, ffmpegPath={}, ffprobePath={}, error={}",
                        ffmpegPath, ffprobePath, e.getMessage(), e);
                throw new IllegalArgumentException("FFmpeg不可用");
            }
        }

        String fontPath = StrUtil.blankToDefault(request.getFfmpegFontFile(), "").trim();
        if (StrUtil.isBlank(fontPath)) {
            log.error("FFmpeg字幕字体路径为空");
            throw new IllegalArgumentException("字体路径无效");
        }
        try {
            ffmpegRuntimeValidator.validateFont(ffmpegPath, ffprobePath, fontPath);
            request.setFfmpegFontFile(fontPath);
        } catch (FontValidationException e) {
            log.error("FFmpeg字幕字体校验失败, fontPath={}, error={}", fontPath, e.getMessage(), e);
            throw new IllegalArgumentException(e.getUserMessage());
        }
    }

    private void validateCommonFields(MpsConfigSaveRequest request, String mode) {
        String resolution = StrUtil.blankToDefault(request.getOutputResolution(), "").trim().toUpperCase();
        if (!Set.of("SD", "HD", "FHD", "2K", "4K").contains(resolution)) {
            log.error("媒体处理分辨率档非法, resolution={}", resolution);
            throw new IllegalArgumentException("分辨率错误");
        }
        request.setOutputResolution(resolution);
        String codec = StrUtil.blankToDefault(request.getCodec(), "").trim().toUpperCase();
        Set<String> codecs = "tencent-mps".equals(mode) ? Set.of("H.264")
                : "aliyun-ims".equals(mode) ? Set.of("H.264", "H.265")
                : Set.of("H.264", "H.265", "AV1");
        if (!codecs.contains(codec)) {
            log.error("媒体处理编码不支持, mode={}, codec={}", mode, codec);
            throw new IllegalArgumentException("编码不支持");
        }
        request.setCodec(codec);
        String outputDir = StrUtil.blankToDefault(request.getOutputDir(), "").trim().replace('\\', '/');
        if (StrUtil.isBlank(outputDir) || outputDir.contains("..") || outputDir.contains("?")
                || outputDir.contains("#") || outputDir.contains(":") || outputDir.startsWith("//")
                || outputDir.chars().anyMatch(Character::isISOControl)) {
            log.error("媒体处理输出目录非法, outputDir={}", outputDir);
            throw new IllegalArgumentException("目录错误");
        }
        if (!outputDir.startsWith("/")) {
            outputDir = "/" + outputDir;
        }
        if (!outputDir.endsWith("/")) {
            outputDir = outputDir + "/";
        }
        request.setOutputDir(outputDir);
        if (request.getCreditRate() == null || request.getCreditRate() < 1
                || request.getCreditRate() > 100000 || request.getProfitMultiplier() == null
                || request.getProfitMultiplier().signum() < 0
                || request.getProfitMultiplier().compareTo(new BigDecimal("100")) > 0) {
            log.error("媒体处理结算参数非法, creditRate={}, profitMultiplier={}",
                    request.getCreditRate(), request.getProfitMultiplier());
            throw new IllegalArgumentException("结算参数错误");
        }
    }

    private void validateCallback(String callbackUrl) {
        if (StrUtil.isBlank(callbackUrl)) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(callbackUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || StrUtil.isBlank(uri.getHost())
                    || StrUtil.isNotBlank(uri.getUserInfo()) || StrUtil.isNotBlank(uri.getFragment())) {
                throw new IllegalArgumentException("回调地址错误");
            }
        } catch (Exception e) {
            log.error("媒体处理回调地址非法, callbackUrl={}", callbackUrl);
            throw new IllegalArgumentException("回调地址错误");
        }
    }

    private void validatePositive(Integer value, String label) {
        if (value == null || value < 1 || value > 1000) {
            log.error("媒体处理并发配置非法, label={}, value={}", label, value);
            throw new IllegalArgumentException("并发错误");
        }
    }

    private void validateEnabledSecrets(Boolean enabled, String submittedId, String submittedSecret,
                                        String storedId, String storedSecret, String label) {
        if (Boolean.TRUE.equals(enabled)
                && (!hasSubmittedOrStoredSecret(submittedId, storedId)
                || !hasSubmittedOrStoredSecret(submittedSecret, storedSecret))) {
            log.error("启用媒体处理时密钥未配置, label={}", label);
            throw new IllegalArgumentException("密钥未配置");
        }
    }

    private boolean hasSubmittedOrStoredSecret(String submitted, String stored) {
        return StrUtil.isNotBlank(submitted) && !submitted.contains(MASK_FLAG) || StrUtil.isNotBlank(stored);
    }

    private void validateNonNegative(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() < 0) {
                log.error("媒体处理单价不能为负数, value={}", value);
                throw new IllegalArgumentException("单价错误");
            }
        }
    }

    private String parseOssRegion(String endpoint) {
        String value = StrUtil.blankToDefault(endpoint, "").trim().toLowerCase();
        value = value.replaceFirst("^https?://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int dot = value.indexOf('.');
        String host = dot >= 0 ? value.substring(0, dot) : value;
        host = host.replace("-internal", "").replace("-intranet", "");
        return host.startsWith("oss-") ? host.substring(4) : "";
    }

    private String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private String firstNotBlank(String preferred, String fallback) {
        return StrUtil.isNotBlank(preferred) ? preferred : fallback;
    }

    /** 配置缓存只能在数据库事务提交成功后刷新。 */
    private void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mpsConfigManager.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mpsConfigManager.refresh();
            }
        });
    }

    /**
     * 保存字符串配置项（null 跳过）。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveString(String configName, String value) {
        if (Objects.isNull(value)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, value.trim());
    }

    /**
     * 保存布尔配置项（null 跳过）。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveBoolean(String configName, Boolean value) {
        if (Objects.isNull(value)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, String.valueOf(value));
    }

    /**
     * 保存整型配置项（null 跳过）。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveInteger(String configName, Integer value) {
        if (Objects.isNull(value)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, String.valueOf(value));
    }

    /**
     * 保存高精度数值配置项（null 跳过）。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveDecimal(String configName, BigDecimal value) {
        if (Objects.isNull(value)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, value.toPlainString());
    }

    /**
     * 保存密钥配置项：空或脱敏串（含 ****）视为未修改，跳过保存。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveSecret(String configName, String value) {
        if (StrUtil.isBlank(value) || value.contains(MASK_FLAG)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, value.trim());
    }

    /**
     * 对指定键的值做脱敏（前4+****+后4，长度不足则整体打码）。
     *
     * @param map 配置 Map
     * @param key 需脱敏的键
     */
    private void maskInPlace(Map<String, String> map, String key) {
        String value = map.get(key);
        if (StrUtil.isBlank(value)) {
            return;
        }
        if (value.length() > 8) {
            map.put(key, value.substring(0, 4) + MASK_FLAG + value.substring(value.length() - 4));
        } else {
            map.put(key, MASK_FLAG);
        }
    }
}
