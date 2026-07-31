package com.aid.config.imgmoderation.controller;

import java.util.Map;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.config.imgmoderation.dto.ImageModerationConfigSaveRequest;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片内容安全审查配置读写（后台）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/imgmoderation")
@RequiredArgsConstructor
public class ImageModerationConfigController extends BaseController {

    /**
     * 配置分类。
     */
    private static final String CATEGORY = "image_moderation";

    /**
     * 脱敏串标记：包含该串视为未修改，保留原密钥。
     */
    private static final String MASK_FLAG = "****";

    /**
     * 图片审查配置管理器（读取脱敏配置）。
     */
    private final ImageModerationConfigManager imageModerationConfigManager;

    /**
     * 通用配置服务（写 aid_config）。
     */
    private final IAidConfigService aidConfigService;

    /**
     * 读取当前图片审查配置（密钥脱敏）。
     *
     * @return 脱敏后的配置 Map，放在 data 字段返回
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/config")
    public AjaxResult getConfig() {
        // 配置管理器内部已对 tencentSecretKey 脱敏
        Map<String, String> config = imageModerationConfigManager.getCurrentConfig();
        return AjaxResult.success(config);
    }

    /**
     * 整组保存图片审查配置到 aid_config。
     *
     * 字段为 null 的不更新；密钥提交脱敏串（含 ****）视为未修改，保留原值。
     *
     * @param request 配置保存请求
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "图片审查配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    public AjaxResult saveConfig(@RequestBody ImageModerationConfigSaveRequest request) {
        if (Objects.isNull(request)) {
            return AjaxResult.error("参数不能为空");
        }
        try {
            // 逐项 upsert（为 null 的项跳过）
            saveBoolean("enabled", request.getEnabled());
            saveString("provider", request.getProvider());
            saveString("tencentRegion", request.getTencentRegion());
            saveString("tencentSecretId", request.getTencentSecretId());
            // 密钥脱敏回写保护
            saveSecret("tencentSecretKey", request.getTencentSecretKey());
            saveBoolean("prioritizeFileUrl", request.getPrioritizeFileUrl());
            saveString("moderationStage", request.getModerationStage());
            saveBoolean("blockOnSuggestionReview", request.getBlockOnSuggestionReview());
            saveBoolean("failOpenOnError", request.getFailOpenOnError());
            saveBoolean("logPassed", request.getLogPassed());
            saveInteger("logRetentionDays", request.getLogRetentionDays());
            return AjaxResult.success("保存成功");
        } catch (Exception e) {
            // 写库异常前打日志再抛友好提示
            log.error("保存图片审查配置失败, error={}", e.getMessage(), e);
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
     * 保存密钥配置项：脱敏回写保护——空或脱敏串（含 ****）视为未修改，跳过保存。
     *
     * @param configName 配置名
     * @param value      配置值
     */
    private void saveSecret(String configName, String value) {
        // 空或脱敏串视为未修改，避免把打码串当真密钥写回
        if (StrUtil.isBlank(value) || value.contains(MASK_FLAG)) {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, configName, value.trim());
    }
}
