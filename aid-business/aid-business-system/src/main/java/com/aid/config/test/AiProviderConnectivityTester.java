package com.aid.config.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** providerCode → Probe 映射（protocol 取不到时按厂商回退，如即梦 SigV4 / 火山 Ark） */
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
            //    否则取该服务商下任一「协议有专用探活」的模型走 protocol 探活；都没有则退化连通性。
            ProbeResult probeResult;
            ProviderProbe providerProbe = providerProbeMap.get(providerCode);
            if (providerProbe != null) {
                // providerCode 级探活不依赖具体模型（自建签名/固定端点），无需取模型
                probeResult = providerProbe.probe(null, provider);
            } else {
                AidAiModel probeModel = pickEnabledModelForProbe(providerId);
                if (probeModel != null && probeMap.containsKey(probeModel.getProtocol())) {
                    probeResult = probeMap.get(probeModel.getProtocol()).probe(probeModel, provider);
                } else {
                    // 退化：仅校验网关连通 + 密钥非空（前面已校验密钥）
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
     * 取该服务商下任一启用模型；优先返回协议已注册专用探活的模型，否则返回任意启用模型。
     */
    private AidAiModel pickEnabledModelForProbe(Long providerId) {
        AidAiModel query = new AidAiModel();
        query.setProviderId(providerId);
        query.setStatus(STATUS_ENABLED);
        List<AidAiModel> models = modelService.selectAidAiModelList(query);
        if (models == null || models.isEmpty()) {
            return null;
        }
        // 优先挑协议有专用探活的模型（如文本模型可真探活）
        for (AidAiModel model : models) {
            if (StrUtil.isNotBlank(model.getProtocol()) && probeMap.containsKey(model.getProtocol())) {
                return model;
            }
        }
        // 没有专用探活协议则返回首个启用模型（仅用于退化连通性）
        return models.get(0);
    }

    /**
     * 把探活结果映射为统一测试结果。
     */
    private ConfigTestResult toTestResult(ProbeResult probeResult, String providerCode) {
        if (probeResult != null && probeResult.isOk()) {
            return ConfigTestResult.ok(probeResult.getMessage(), providerCode);
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
