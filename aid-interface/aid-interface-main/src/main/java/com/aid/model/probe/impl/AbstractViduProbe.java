package com.aid.model.probe.impl;

import java.util.Objects;

import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.ViduConstants;
import com.aid.media.provider.ViduStatusMapper;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;

import cn.hutool.core.util.StrUtil;

/**
 * Vidu 不存在任务只读查询探测模板。
 */
public abstract class AbstractViduProbe extends AbstractReadOnlyProbe {

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        return ProviderEndpointUtils.normalizeTaskQueryTemplate(provider.getTaskQuerySuffix())
                .replace("%s", ProbeHttpSupport.randomProbeId());
    }

    @Override
    protected void applyAuth(cn.hutool.http.HttpRequest request, AidAiProvider provider) {
        ProbeHttpSupport.applyAuth(request, provider, "Authorization", ViduConstants.AUTH_TOKEN_PREFIX);
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeBusinessResponseSupport.isKnownTaskMissing(response.body())) {
            return ProbeResult.ok("鉴权查询正常");
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && ViduStatusMapper.isKnownState(resolveStatus(root))) {
            return ProbeResult.ok("鉴权查询正常");
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private String resolveStatus(JSONObject root) {
        String status = root.getString("status");
        if (StrUtil.isBlank(status)) {
            status = root.getString("state");
        }
        if (StrUtil.isBlank(status)) {
            status = root.getString("task_status");
        }
        JSONObject data = root.getJSONObject("data");
        if (StrUtil.isBlank(status) && Objects.nonNull(data)) {
            status = data.getString("status");
        }
        if (StrUtil.isBlank(status) && Objects.nonNull(data)) {
            status = data.getString("state");
        }
        return status;
    }
}
