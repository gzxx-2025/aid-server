package com.aid.model.probe.impl;

import java.util.Objects;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;

import cn.hutool.core.util.StrUtil;

/** 可配置异步媒体协议的模型元数据探测模板。 */
abstract class AbstractConfigurableAsyncMediaProbe extends AbstractReadOnlyProbe {

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        String derived = ProbeHttpSupport.deriveSiblingPath(
                model == null ? null : model.getApiSuffix(), "/v1/models", knownSubmitEndings());
        return StrUtil.blankToDefault(derived, "/v1/models");
    }

    protected abstract String[] knownSubmitEndings();

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider,
                                    ProbeHttpResponse response) {
        if (!ProbeHttpSupport.isHttpSuccess(response)) {
            return ProbeHttpSupport.unexpected(response);
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        JSONArray data = root == null ? null : root.getJSONArray("data");
        if (data == null) {
            return ProbeResult.fail("模型列表响应无效", ProbeHttpSupport.detail(response));
        }
        String expected = ProbeHttpSupport.resolveModelCode(model);
        if (StrUtil.isBlank(expected)) {
            return ProbeResult.ok("鉴权查询正常");
        }
        for (Object item : data) {
            if (item instanceof JSONObject object
                    && Objects.equals(expected, StrUtil.trim(object.getString("id")))) {
                return ProbeResult.ok("模型鉴权正常");
            }
        }
        return ProbeResult.fail("模型不存在", "模型列表未返回配置的实际模型");
    }
}
