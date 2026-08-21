package com.aid.model.probe.impl;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;

/**
 * MiniMax 视频任务列表与音色元数据只读探测。
 */
@Component
public class MinimaxProbe extends AbstractReadOnlyProbe {

    private static final String PROVIDER_CODE = "minimax";
    private static final String VOICE_TYPE_SYSTEM = "system";
    private static final int BUSINESS_OK = 0;

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        if (isTts(model)) {
            if (StrUtil.isBlank(model.getApiSuffix())) {
                return "/";
            }
            String derived = ProbeHttpSupport.deriveSiblingPath(
                    model.getApiSuffix(), "/get_voice", "/t2a_v2", "/t2a_async_v2");
            return StrUtil.blankToDefault(derived, "/");
        }
        String template = ProviderEndpointUtils.normalizeTaskQueryTemplate(provider.getTaskQuerySuffix());
        String listPath = template.replaceAll("/%s(?:\\?.*)?$", "");
        if (Objects.equals(listPath, template)) {
            throw new IllegalArgumentException("查询路径无法推导列表");
        }
        return ProbeHttpSupport.addQuery(listPath, "page_num", "1") + "&page_size=1";
    }

    @Override
    protected HttpRequest buildRequest(String url, AidAiModel model, AidAiProvider provider) {
        if (!isTts(model) || Objects.equals("/", resolvePath(model, provider))) {
            return HttpRequest.get(url);
        }
        MinimaxVoiceProbeRequest body = new MinimaxVoiceProbeRequest();
        body.setVoiceType(VOICE_TYPE_SYSTEM);
        return HttpRequest.post(url).body(JSON.toJSONString(body));
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (!ProbeHttpSupport.isHttpSuccess(response)) {
            return ProbeHttpSupport.unexpected(response);
        }
        if (isTts(model) && Objects.equals("/", resolvePath(model, provider))) {
            ProbeResult result = ProbeResult.ok("仅网关可达");
            result.setDetail("未验证密钥或模型");
            return result;
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        if (Objects.isNull(root)) {
            return ProbeHttpSupport.unexpected(response);
        }
        if (isTts(model)) {
            JSONObject baseResp = root.getJSONObject("base_resp");
            if (Objects.nonNull(baseResp) && Objects.equals(BUSINESS_OK, baseResp.getInteger("status_code"))) {
                return ProbeResult.ok("鉴权查询正常");
            }
            return ProbeHttpSupport.unexpected(response);
        }
        JSONArray items = root.getJSONArray("items");
        if (Objects.nonNull(items)) {
            return ProbeResult.ok("鉴权查询正常");
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private boolean isTts(AidAiModel model) {
        return Objects.nonNull(model) && Objects.equals(MinimaxTtsConstants.PROTOCOL_TTS, model.getProtocol());
    }
}
