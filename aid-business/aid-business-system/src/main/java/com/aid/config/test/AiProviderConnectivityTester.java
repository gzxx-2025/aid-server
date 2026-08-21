package com.aid.config.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 服务商连通性测试器（testKey = ai-provider）。
 */
@Slf4j
@Component
public class AiProviderConnectivityTester implements ConfigConnectivityTester {

    /** 启用状态值（0正常 1停用） */
    private static final String STATUS_ENABLED = "0";

    /** payload 中服务商主键字段名 */
    private static final String PAYLOAD_PROVIDER_ID = "providerId";

    private final IAidAiProviderService providerService;

    private final IAidAiModelService modelService;

    /** protocol → Probe 映射（Spring 注入所有 Probe 实现构建） */
    private final Map<String, ProviderProbe> probeMap = new HashMap<>();

    /** providerCode → Probe 映射（厂商元数据探测优先于通用协议回退） */
    private final Map<String, ProviderProbe> providerProbeMap = new HashMap<>();

    /**
     * 构造器注入：服务商 / 模型服务 + 所有探活实现。
     *
     * @param providerService 服务商服务
     * @param modelService    模型服务
     * @param probeList       全部 Provider 探活实现（可能为空）
     */
    public AiProviderConnectivityTester(IAidAiProviderService providerService,
                                        IAidAiModelService modelService,
                                        List<ProviderProbe> probeList) {
        this.providerService = providerService;
        this.modelService = modelService;
        if (probeList != null) {
            for (ProviderProbe probe : probeList) {
                if (StrUtil.isNotBlank(probe.protocol())) {
                    this.probeMap.put(probe.protocol(), probe);
                }
                if (StrUtil.isNotBlank(probe.providerCode())) {
                    this.providerProbeMap.put(probe.providerCode(), probe);
                }
            }
        }
    }

    @Override
    public String testKey() {
        return "ai-provider";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        try {
            Long providerId = extractProviderId(request);
            if (providerId == null) {
                return ConfigTestResult.fail("缺少服务商参数");
            }
            AidAiProvider provider = providerService.selectAidAiProviderById(providerId);
            if (provider == null) {
                log.error("AI 服务商探活失败: 服务商不存在, providerId={}", providerId);
                return ConfigTestResult.fail("服务商不存在");
            }
            String providerCode = provider.getProviderCode();
            if (StrUtil.isBlank(provider.getApiKey())) {
                log.error("AI 服务商探活失败: 未配置密钥, providerCode={}", providerCode);
                return ConfigTestResult.fail("未配置密钥");
            }
            ProbeResult probeResult;
            ProviderProbe providerProbe = providerProbeMap.get(providerCode);
            AidAiModel probeModel = pickModelForProbe(providerId, providerProbe);
            if (providerProbe != null
                    && (probeModel != null || !providerProbe.requiresModel())) {
                // 即梦等查询必须使用可解析模型的真实 req_key；没有启用模型时可安全复用停用配置做只读查询。
                probeResult = providerProbe.probe(probeModel, provider);
            } else {
                AidAiModel protocolModel = providerProbe == null
                        ? probeModel : pickModelForProbe(providerId, null);
                if (protocolModel != null && probeMap.containsKey(protocolModel.getProtocol())) {
                    probeResult = probeMap.get(protocolModel.getProtocol()).probe(protocolModel, provider);
                } else {
                    // 退化结果只代表网关可达，不代表密钥或模型有效
                    probeResult = ProviderConnectivitySupport.checkBaseUrl(provider.getBaseUrl(), providerCode);
                }
            }
            return toTestResult(probeResult, providerCode);
        } catch (Exception e) {
            // 兜底：禁止异常冒泡到前端
            log.error("AI 服务商探活异常, testKey={}", testKey(), e);
            return buildFail("测试执行失败", e);
        }
    }

    /**
     * 从 payload 解析 providerId。
     */
    private Long extractProviderId(ConfigTestRequest request) {
        if (request == null || request.getPayload() == null) {
            return null;
        }
        Object raw = request.getPayload().get(PAYLOAD_PROVIDER_ID);
        return Convert.toLong(raw, null);
    }

    /**
     * 取该服务商下适合当前探测器的模型，优先启用配置。
     *
     * @param providerId   服务商主键
     * @param providerProbe 服务商专用探测器，可空
     * @return 可用于探测的模型；无匹配时返回 null
     */
    private AidAiModel pickModelForProbe(Long providerId, ProviderProbe providerProbe) {
        AidAiModel query = new AidAiModel();
        query.setProviderId(providerId);
        List<AidAiModel> models = modelService.selectAidAiModelList(query);
        if (models == null || models.isEmpty()) {
            return null;
        }
        for (AidAiModel model : models) {
            if (Objects.equals(STATUS_ENABLED, model.getStatus()) && supportsProbe(model, providerProbe)) {
                return model;
            }
        }
        for (AidAiModel model : models) {
            if (supportsProbe(model, providerProbe)) {
                return model;
            }
        }
        return null;
    }

    private boolean supportsProbe(AidAiModel model, ProviderProbe providerProbe) {
        if (providerProbe != null) {
            return providerProbe.supportsModel(model);
        }
        return StrUtil.isNotBlank(model.getProtocol()) && probeMap.containsKey(model.getProtocol());
    }

    /**
     * 把探活结果映射为统一测试结果。
     */
    private ConfigTestResult toTestResult(ProbeResult probeResult, String providerCode) {
        if (probeResult != null && probeResult.isOk()) {
            ConfigTestResult result = ConfigTestResult.ok(probeResult.getMessage(), providerCode);
            result.setDetails(probeResult.getDetail());
            return result;
        }
        ConfigTestResult result = ConfigTestResult.fail(
                probeResult == null ? "测试失败" : probeResult.getMessage());
        if (probeResult != null) {
            result.setDetails(probeResult.getDetail());
        }
        result.setProvider(providerCode);
        return result;
    }

    /**
     * 构造失败结果（堆栈进 details，无密钥明文）。
     */
    private ConfigTestResult buildFail(String message, Exception e) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(e.getClass().getSimpleName() + ": " + e.getMessage());
        return result;
    }
}
