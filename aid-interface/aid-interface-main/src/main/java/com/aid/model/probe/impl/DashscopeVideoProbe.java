package com.aid.model.probe.impl;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;

import cn.hutool.core.util.StrUtil;

/**
 * DashScope 视频任务只读查询探测。
 */
@Component
public class DashscopeVideoProbe extends AbstractReadOnlyProbe {

    private static final Set<String> KNOWN_TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED");

    @Override
    public String protocol() {
        return "dashscope-video";
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        return ProviderEndpointUtils.normalizeTaskQueryTemplate(provider.getTaskQuerySuffix())
                .replace("%s", ProbeHttpSupport.randomProbeId());
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeBusinessResponseSupport.isKnownTaskMissing(response.body())) {
            return ProbeResult.ok("鉴权查询正常");
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && isKnownTaskStatus(resolveTaskStatus(root))) {
            return ProbeResult.ok("鉴权查询正常");
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private String resolveTaskStatus(JSONObject root) {
        JSONObject output = root.getJSONObject("output");
        if (Objects.nonNull(output) && StrUtil.isNotBlank(output.getString("task_status"))) {
            return output.getString("task_status");
        }
        if (StrUtil.isNotBlank(root.getString("task_status"))) {
            return root.getString("task_status");
        }
        JSONObject data = root.getJSONObject("data");
        return Objects.isNull(data) ? null : data.getString("task_status");
    }

    private boolean isKnownTaskStatus(String status) {
        return StrUtil.isNotBlank(status)
                && KNOWN_TASK_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }
}
