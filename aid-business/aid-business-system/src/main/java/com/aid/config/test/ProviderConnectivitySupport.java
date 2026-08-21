package com.aid.config.test;

import java.util.Objects;

import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.impl.ProbeHttpResponse;
import com.aid.model.probe.impl.ProbeHttpSupport;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider 连通性退化探活工具。
 */
@Slf4j
final class ProviderConnectivitySupport {

    private ProviderConnectivitySupport() {
    }

    /**
     * 校验网关地址连通性（退化探活）。
     *
     * @param baseUrl     网关地址
     * @param providerTag 服务商标识（仅用于日志，不含密钥）
     * @return 探活结果
     */
    static ProbeResult checkBaseUrl(String baseUrl, String providerTag) {
        if (StrUtil.isBlank(baseUrl)) {
            log.error("退化探活失败: baseUrl 为空, provider={}", providerTag);
            return ProbeResult.fail("未配置网关地址", "baseUrl 为空");
        }
        try (HttpResponse response = HttpRequest.get(baseUrl.trim())
                .setConnectionTimeout(ProbeHttpSupport.CONNECT_TIMEOUT_MS)
                .setReadTimeout(ProbeHttpSupport.READ_TIMEOUT_MS)
                .execute()) {
            ProbeHttpResponse snapshot = new ProbeHttpResponse(
                    response.getStatus(), response.body(), response.header("Content-Type"));
            if (ProbeHttpSupport.isHttpSuccess(snapshot)) {
                ProbeResult result = ProbeResult.ok("仅网关可达");
                result.setDetail("未验证密钥或模型");
                return result;
            }
            ProbeResult commonFailure = ProbeHttpSupport.classifyCommonFailure(snapshot);
            if (Objects.nonNull(commonFailure)) {
                log.error("退化探活响应异常, provider={}, status={}", providerTag, response.getStatus());
                return commonFailure;
            }
            if (ProbeHttpSupport.isReadOnlyRouteUnavailable(snapshot)) {
                log.info("网关根路径未开放探测接口, provider={}, status={}", providerTag, response.getStatus());
                return ProbeHttpSupport.gatewayOnlyForUnavailableReadOnlyRoute();
            }
            log.error("退化探活响应异常, provider={}, status={}", providerTag, response.getStatus());
            return ProbeHttpSupport.unexpected(snapshot);
        } catch (Exception e) {
            log.error("退化探活网关不可达, provider={}, err={}", providerTag, e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
