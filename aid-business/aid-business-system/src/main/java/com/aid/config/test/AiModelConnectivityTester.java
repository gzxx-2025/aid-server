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
 * AI 模型连通性测试器（testKey = ai-model）。
 */
@Slf4j
@Component
public class AiModelConnectivityTester implements ConfigConnectivityTester {

    /** payload 中模型主键字段名 */
    private static final String PAYLOAD_MODEL_ID = "modelId";

    private final IAidAiModelService modelService;

    private final IAidAiProviderService providerService;

    /** protocol → Probe 映射（Spring 注入所有 Probe 实现构建） */
    private final Map<String, ProviderProbe> probeMap = new HashMap<>();

    /** providerCode → Probe 映射（protocol 取不到时按厂商回退，如即梦 SigV4 / 火山 Ark） */
    private final Map<String, ProviderProbe> providerProbeMap = new HashMap<>();

    /**
     * 构造器注入：模型 / 服务商服务 + 所有探活实现。
     *
     * @param modelService    模型服务
     * @param providerService 服务商服务
     * @param probeList       全部 Provider 探活实现（可能为空）
     */
    public AiModelConnectivityTester(IAidAiModelService modelService,
                                     IAidAiProviderService providerService,
                                     List<ProviderProbe> probeList) {
        this.modelService = modelService;
        this.providerService = providerService;
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
        return "ai-model";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        try {
            Long modelId = extractModelId(request);
            if (modelId == null) {
                return ConfigTestResult.fail("缺少模型参数");
            }
            AidAiModel model = modelService.selectAidAiModelById(modelId);
            if (model == null) {
                log.error("AI 模型探活失败: 模型不存在, modelId={}", modelId);
                return ConfigTestResult.fail("模型不存在");
            }
            if (model.getProviderId() == null) {
                log.error("AI 模型探活失败: 模型未绑定服务商, modelId={}", modelId);
                return ConfigTestResult.fail("模型未绑定服务商");
            }
            AidAiProvider provider = providerService.selectAidAiProviderById(model.getProviderId());
            if (provider == null) {
                log.error("AI 模型探活失败: 服务商不存在, providerId={}", model.getProviderId());
                return ConfigTestResult.fail("服务商不存在");
            }
            String providerCode = provider.getProviderCode();
            ProviderProbe probe = StrUtil.isNotBlank(model.getProtocol())
                    ? probeMap.get(model.getProtocol()) : null;
            if (probe == null) {
                probe = providerProbeMap.get(providerCode);
            }
            ProbeResult probeResult;
            if (probe != null) {
                // 命中专用探活：发空体/最小请求靠错误码判定，绝不真生成
                probeResult = probe.probe(model, provider);
            } else {
                // 退化：密钥非空 + 网关连通
                if (StrUtil.isBlank(provider.getApiKey())) {
                    log.error("AI 模型探活失败: 未配置密钥, providerCode={}", providerCode);
                    return ConfigTestResult.fail("未配置密钥");
                }
                probeResult = ProviderConnectivitySupport.checkBaseUrl(provider.getBaseUrl(), providerCode);
            }
            return toTestResult(probeResult, providerCode);
        } catch (Exception e) {
            // 兜底：禁止异常冒泡到前端
            log.error("AI 模型探活异常, testKey={}", testKey(), e);
            return buildFail("测试执行失败", e);
        }
    }

    /**
     * 从 payload 解析 modelId。
     */
    private Long extractModelId(ConfigTestRequest request) {
        if (request == null || request.getPayload() == null) {
            return null;
        }
        Object raw = request.getPayload().get(PAYLOAD_MODEL_ID);
        return Convert.toLong(raw, null);
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
