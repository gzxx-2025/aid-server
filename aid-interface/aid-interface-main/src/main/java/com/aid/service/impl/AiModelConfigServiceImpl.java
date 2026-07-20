package com.aid.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidUserAiConfig;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidUserAiConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.common.satoken.utils.LoginHelper;
import com.aid.common.utils.StringUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.service.IAiModelConfigService;
import com.aid.upgrade.gateway.OfficialGatewayConfig;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI模型配置聚合Service实现。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiModelConfigServiceImpl implements IAiModelConfigService {

    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;
    private final IAidUserAiConfigService aidUserAiConfigService;
    private final OfficialGatewayConfigProvider officialGatewayConfigProvider;

    private static final String STATUS_NORMAL = "0";
    private static final String DEL_FLAG_NORMAL = "0";

    @Override
    public AiModelConfigVo selectByModelCode(String modelCode) {
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelCode, modelCode)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectByCategoryWithHighestPriority(String category) {
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelType, category)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AidAiModel::getPriority)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectFallbackByCategoryAndLessPriority(String category, Integer currentPriority) {
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelType, category)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .lt(AidAiModel::getPriority, currentPriority)
                .orderByDesc(AidAiModel::getPriority)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectByModelId(Long modelId) {
        AidAiModel model = aidAiModelService.getById(modelId);
        if (model == null || !STATUS_NORMAL.equals(model.getStatus()) || !DEL_FLAG_NORMAL.equals(model.getDelFlag())) {
            return null;
        }
        return buildConfigVo(model);
    }

    /**
     * 核心组装逻辑：模型 + 服务商 + 用户覆盖 → 最终生效配置
     */
    private AiModelConfigVo buildConfigVo(AidAiModel model) {
        if (model == null) {
            return null;
        }

        AidAiProvider provider = aidAiProviderService.getById(model.getProviderId());
        if (provider == null) {
            log.error("模型对应的服务商不存在, modelId={}, providerId={}", model.getId(), model.getProviderId());
            throw new ServiceException("模型配置异常");
        }
        if (!STATUS_NORMAL.equals(provider.getStatus())) {
            log.error("模型对应的服务商已停用, providerId={}", provider.getId());
            throw new ServiceException("模型已停用");
        }

        String effectiveBaseUrl = provider.getBaseUrl();
        String effectiveApiKey = provider.getApiKey();
        String effectiveApiSecret = provider.getApiSecret();

        // 官方统一网关开启后，全局厂商出站改走官方地址与官方密钥（协议仍遵循原厂商）；
        // 例外模型或例外厂商（官方网关暂不支持的）仍走自有厂商网关
        OfficialGatewayConfig officialGateway = officialGatewayConfigProvider.getConfig();
        if (officialGateway.isEnabled() && StrUtil.isNotBlank(officialGateway.getBaseUrl())
                && !officialGateway.isExcluded(model.getId(), provider.getId())) {
            effectiveBaseUrl = officialGateway.resolveBaseUrl(provider.getProviderCode());
            if (StrUtil.isNotBlank(officialGateway.getApiKey())) {
                effectiveApiKey = officialGateway.getApiKey();
            }
        }

        Long userId = getCurrentUserIdSafe();
        if (userId != null) {
            AidUserAiConfig userConfig = aidUserAiConfigService.getOne(
                Wrappers.<AidUserAiConfig>lambdaQuery()
                    .eq(AidUserAiConfig::getUserId, userId)
                    .eq(AidUserAiConfig::getProviderId, provider.getId())
                    .eq(AidUserAiConfig::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"),
                false
            );
            if (userConfig != null && STATUS_NORMAL.equals(userConfig.getIsEnable())) {
                if (StringUtils.isNotEmpty(userConfig.getCustomBaseUrl())) {
                    effectiveBaseUrl = userConfig.getCustomBaseUrl();
                }
                if (StringUtils.isNotEmpty(userConfig.getCustomApiKey())) {
                    effectiveApiKey = userConfig.getCustomApiKey();
                }
                if (StringUtils.isNotEmpty(userConfig.getCustomApiSecret())) {
                    effectiveApiSecret = userConfig.getCustomApiSecret();
                }
            }
        }

        AiModelConfigVo vo = new AiModelConfigVo();
        // 模型字段
        vo.setId(model.getId());
        vo.setProviderId(model.getProviderId());
        vo.setModelCode(model.getModelCode());
        // 真实上游模型名：优先 real_model_code，为空回退 model_code
        String effectiveRealModelCode = StrUtil.isNotBlank(model.getRealModelCode())
                ? model.getRealModelCode() : model.getModelCode();
        vo.setRealModelCode(effectiveRealModelCode);
        vo.setModelName(model.getModelName());
        vo.setModelType(model.getModelType());
        vo.setGenerateMode(model.getGenerateMode());
        vo.setCostCredits(model.getCostCredits());
        // 模型级计费倍率（默认 1.00，避免老数据 NULL 导致下游 NPE）
        vo.setBillingMultiplier(model.getBillingMultiplier());
        vo.setApiVersion(model.getApiVersion());
        vo.setApiSuffix(model.getApiSuffix());
        vo.setProtocol(model.getProtocol());
        vo.setPriority(model.getPriority());
        vo.setImageRefine(model.getImageRefine());
        // 计费扩展字段
        vo.setBillingMode(model.getBillingMode());
        vo.setBillingRuleJson(model.getBillingRuleJson());
        vo.setBillingVersion(model.getBillingVersion());
        // 服务商字段（已处理用户覆盖）
        vo.setBaseUrl(effectiveBaseUrl);
        vo.setApiKey(effectiveApiKey);
        vo.setApiSecret(effectiveApiSecret);
        vo.setTaskQuerySuffix(provider.getTaskQuerySuffix());
        vo.setProviderCode(provider.getProviderCode());
        vo.setProviderName(provider.getProviderName());
        // 调度策略字段
        vo.setScheduleStrategyJson(model.getScheduleStrategyJson());
        vo.setSupportsCallback(provider.getSupportsCallback());
        vo.setProviderScheduleStrategyJson(provider.getScheduleStrategyJson());
        // 模型能力扩展字段（图片/视频统一，供应给 provider/编排层）
        vo.setSupportsTextInput(model.getSupportsTextInput());
        vo.setSupportsSystemPrompt(model.getSupportsSystemPrompt());
        vo.setSupportsImageInput(model.getSupportsImageInput());
        vo.setSupportsMultiImageInput(model.getSupportsMultiImageInput());
        vo.setMaxOutputCount(model.getMaxOutputCount());
        vo.setDefaultOutputCount(model.getDefaultOutputCount());
        vo.setSupportsAspectRatio(model.getSupportsAspectRatio());
        vo.setSupportsSizePreset(model.getSupportsSizePreset());
        vo.setSupportsDuration(model.getSupportsDuration());
        vo.setSupportsFirstFrame(model.getSupportsFirstFrame());
        vo.setSupportsLastFrame(model.getSupportsLastFrame());
        vo.setDefaultSizeCode(model.getDefaultSizeCode());
        vo.setDefaultAspectRatio(model.getDefaultAspectRatio());
        vo.setDefaultDurationSeconds(model.getDefaultDurationSeconds());
        vo.setCapabilityJson(model.getCapabilityJson());
        vo.setParamMappingJson(model.getParamMappingJson());

        // LLM Provider 鉴权与请求扩展配置（厂商级）
        vo.setAuthHeader(provider.getAuthHeader());
        vo.setAuthPrefix(provider.getAuthPrefix());
        vo.setExtraHeadersJson(provider.getExtraHeaders());
        vo.setExtraBodyJson(provider.getExtraBody());
        vo.setExtraQueryJson(provider.getExtraQuery());
        // 模型级 extra_body（合并时覆盖厂商级同名 key）
        vo.setModelExtraBodyJson(model.getExtraBody());

        return vo;
    }

    private Long getCurrentUserIdSafe() {
        if (!LoginHelper.isLogin()) {
            return null;
        }
        return LoginHelper.getUserId();
    }
}
