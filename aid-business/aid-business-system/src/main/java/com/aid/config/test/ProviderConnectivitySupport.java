package com.aid.config.test;

import com.aid.model.probe.ProbeResult;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider 连通性退化探活工具。
 */
@Slf4j
final class ProviderConnectivitySupport {

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 8000;

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
        // GET 网关地址，仅探网络可达性，不关心业务状态码
        try (HttpResponse response = HttpRequest.get(baseUrl.trim())
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            // 能拿到任意响应即说明网络可达
            return ProbeResult.ok("网关可连通");
        } catch (Exception e) {
            // 连接被拒 / 超时 / DNS：网关不可达。异常前已 log，明细不含密钥
            log.error("退化探活网关不可达, provider={}, err={}", providerTag, e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
