package com.aid.config.mps.controller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.config.mps.dto.MpsConfigSaveRequest;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS 视频合成配置读写（后台）。
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

    /** 通用配置读取服务 */
    private final ConfigService configService;

    /** 通用配置写入服务（写 aid_config） */
    private final IAidConfigService aidConfigService;

    /**
     * 读取当前 MPS 配置（密钥脱敏）。
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
        return AjaxResult.success(result);
    }

    /**
     * 整组保存 MPS 配置到 aid_config。
     *
     * 字段为 null 的不更新；密钥提交脱敏串（含 ****）视为未修改，保留原值。
     *
     * @param request 配置保存请求
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "视频合成配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    public AjaxResult saveConfig(@RequestBody MpsConfigSaveRequest request) {
        if (Objects.isNull(request)) {
            return AjaxResult.error("参数不能为空");
        }
        try {
            saveBoolean("enabled", request.getEnabled());
            // 密钥脱敏回写保护
            saveSecret("secretId", request.getSecretId());
            saveSecret("secretKey", request.getSecretKey());
            saveString("region", request.getRegion());
            saveString("outputBucket", request.getOutputBucket());
            saveString("outputRegion", request.getOutputRegion());
            saveString("outputDir", request.getOutputDir());
            saveString("callbackUrl", request.getCallbackUrl());
            saveString("outputResolution", request.getOutputResolution());
            saveString("codec", request.getCodec());
            saveString("pricingTiers", request.getPricingTiers());
            saveInteger("creditRate", request.getCreditRate());
            saveDecimal("profitMultiplier", request.getProfitMultiplier());
            return AjaxResult.success("保存成功");
        } catch (Exception e) {
            // 写库异常前打日志再抛友好提示
            log.error("保存视频合成配置失败, error={}", e.getMessage(), e);
            return AjaxResult.error("保存失败，请重试");
        }
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
