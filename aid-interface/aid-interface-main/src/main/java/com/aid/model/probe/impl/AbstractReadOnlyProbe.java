package com.aid.model.probe.impl;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.constant.HttpConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 只读 HTTP 探测模板。
 */
@Slf4j
public abstract class AbstractReadOnlyProbe implements ProviderProbe {

    /**
     * 解析只读查询路径。
     *
     * @param model    模型配置
     * @param provider 服务商配置
     * @return 相对路径
     */
    protected abstract String resolvePath(AidAiModel model, AidAiProvider provider);

    /**
     * 解析按顺序尝试的只读查询路径。
     *
     * @param model    模型配置
     * @param provider 服务商配置
     * @return 只读查询路径
     */
    protected List<String> resolvePaths(AidAiModel model, AidAiProvider provider) {
        return Collections.singletonList(resolvePath(model, provider));
    }

    /**
     * 构造查询请求。
     *
     * @param url      请求地址
     * @param model    模型配置
     * @param provider 服务商配置
     * @return HTTP 请求
     */
    protected HttpRequest buildRequest(String url, AidAiModel model, AidAiProvider provider) {
        return HttpRequest.get(url);
    }

    /**
     * 附加鉴权头。
     *
     * @param request  HTTP 请求
     * @param provider 服务商配置
     */
    protected void applyAuth(HttpRequest request, AidAiProvider provider) {
        ProbeHttpSupport.applyBearerAuth(request, provider);
    }

    /**
     * 解释通过公共失败分类后的供应商响应。
     *
     * @param model    模型配置
     * @param provider 服务商配置
     * @param response HTTP 响应
     * @return 探测结果
     */
    protected abstract ProbeResult interpret(AidAiModel model, AidAiProvider provider,
                                             ProbeHttpResponse response);

    /**
     * 解释指定候选路径的供应商响应。
     *
     * @param model    模型配置
     * @param provider 服务商配置
     * @param path     当前只读查询路径
     * @param response HTTP 响应
     * @return 探测结果
     */
    protected ProbeResult interpretPath(AidAiModel model, AidAiProvider provider,
                                        String path, ProbeHttpResponse response) {
        return interpret(model, provider, response);
    }

    @Override
    public ProbeResult probe(AidAiModel model, AidAiProvider provider) {
        ProbeResult invalid = ProbeHttpSupport.validateProvider(provider);
        if (Objects.nonNull(invalid)) {
            return invalid;
        }
        final List<String> paths;
        try {
            paths = resolvePaths(model, provider);
        } catch (IllegalArgumentException ex) {
            log.error("只读探测路径配置无效, providerCode={}, protocol={}, reason={}",
                    provider.getProviderCode(), protocol(), ex.getMessage());
            return ProbeResult.fail("探测路径无效", ex.getMessage());
        }
        if (paths == null || paths.isEmpty()) {
            return ProbeResult.fail("缺少探测路径", "只读查询路径为空");
        }
        for (String path : paths) {
            if (StrUtil.isBlank(path)) {
                return ProbeResult.fail("缺少探测路径", "只读查询路径为空");
            }
            String url = ProbeHttpSupport.buildUrl(provider.getBaseUrl(), path);
            try {
                HttpRequest request = buildRequest(url, model, provider)
                        .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON, true);
                applyAuth(request, provider);
                ProbeHttpResponse response = ProbeHttpSupport.execute(request);
                ProbeResult commonFailure = ProbeHttpSupport.classifyCommonFailure(response);
                if (Objects.nonNull(commonFailure)) {
                    log.error("只读探测失败, providerCode={}, protocol={}, status={}",
                            provider.getProviderCode(), protocol(), response.status());
                    return commonFailure;
                }
                ProbeResult interpreted = interpretPath(model, provider, path, response);
                if (Objects.nonNull(interpreted) && interpreted.isOk()) {
                    return interpreted;
                }
                if (!ProbeHttpSupport.isReadOnlyRouteUnavailable(response)) {
                    return interpreted;
                }
                log.info("代理未开放只读探测接口, providerCode={}, protocol={}, status={}",
                        provider.getProviderCode(), protocol(), response.status());
            } catch (Exception e) {
                log.error("只读探测网关不可达, providerCode={}, protocol={}, err={}",
                        provider.getProviderCode(), protocol(), e.getMessage());
                return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return ProbeHttpSupport.gatewayOnlyForUnavailableReadOnlyRoute();
    }
}
