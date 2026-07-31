package com.aid.compose.service.impl;

import java.net.URI;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.compose.service.ComposeUrlNormalizer;
import com.aid.compose.util.MaterialUrlGuard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 下发上游素材 URL 的校验 + 地址改写实现。
 * 职责：先对「传入完整地址」做结构校验 + 白名单 + 可达性校验（保证素材有效），
 * 再把地址交给公用改写器 {@link MediaUrlResolver#toProviderUrl(String)}——
 * COS 模式且命中本站 CDN 主域名时改用 COS 源站/内网 endpoint 下发，其余模式原样下发。
 * 错误文案携带素材类型标签（如「背景音乐异常」），便于用户定位是哪类素材出了问题。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComposeUrlNormalizerImpl implements ComposeUrlNormalizer {

    /** HEAD 探测超时（毫秒） */
    private static final int HEAD_TIMEOUT_MS = 5000;

    /** 可达性判定的 HTTP 状态码下界（含） */
    private static final int HTTP_OK_MIN = 200;

    /** 可达性判定的 HTTP 状态码上界（不含） */
    private static final int HTTP_OK_MAX = 400;

    /** 素材标签缺省值（错误文案前缀兜底） */
    private static final String DEFAULT_LABEL = "素材";

    /** OSS 配置管理器（复用 uploadMode/cdnDomain/cosCdnDomain/localDomain/imageUrlWhitelist） */
    private final OssConfigManager ossConfigManager;

    /** 公用地址改写器：负责 COS 模式下的源站 endpoint 改写 */
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public String normalizeAndValidate(String rawUrl, String materialLabel) {
        String label = StrUtil.blankToDefault(materialLabel, DEFAULT_LABEL);
        if (StrUtil.isBlank(rawUrl)) {
            // 入参为空属于调用方错误，先记录再抛出
            log.error("URL规范化失败: rawUrl为空, label={}", label);
            throw new RuntimeException(label + "为空");
        }
        String url = rawUrl.trim();
        // 结构校验先行（零 I/O）：blob:/data:/嵌套协议头等前端误传地址直接拒绝，不做无谓的 HEAD 探测
        if (MaterialUrlGuard.isForbidden(url)) {
            log.error("URL结构非法(blob/data/嵌套协议), label={}, url={}", label, url);
            throw new RuntimeException(label + "异常");
        }
        OssProperties oss = ossConfigManager.getOssProperties();
        if (!inWhitelist(url, oss)) {
            log.error("URL未通过白名单校验, label={}, url={}", label, url);
            throw new RuntimeException(label + "异常");
        }
        if (!isReachable(url)) {
            log.error("URL经HEAD探测不可达, label={}, url={}", label, url);
            throw new RuntimeException(label + "异常");
        }
        return mediaUrlResolver.toProviderUrl(url);
    }

    /**
     * 白名单校验：URL 主机名命中 cdnDomain / cosCdnDomain（源站）/ localDomain 之一，
     * 或命中 imageUrlWhitelist 任一前缀即通过。
     *
     * @param url 传入的完整 URL
     * @param oss OSS 配置
     * @return 是否在白名单内
     */
    private boolean inWhitelist(String url, OssProperties oss) {
        if (Objects.isNull(oss)) {
            return false;
        }
        String host = extractHost(url);
        if (StrUtil.isBlank(host)) {
            return false;
        }
        // 本站对外 CDN 域名 / COS 源站 endpoint 域名 / 本地域名（按 host 比对，兼容带或不带 scheme 的配置）
        if (hostEquals(host, oss.getCdnDomain())
                || hostEquals(host, oss.getCosCdnDomain())
                || hostEquals(host, oss.getLocalDomain())) {
            return true;
        }
        // 额外白名单（逗号分隔，按前缀匹配，保持原语义）
        String whitelist = oss.getImageUrlWhitelist();
        if (StrUtil.isNotBlank(whitelist)) {
            String lowerUrl = url.toLowerCase();
            for (String item : whitelist.split(",")) {
                String prefix = stripTrailingSlash(StrUtil.trim(item)).toLowerCase();
                if (StrUtil.isNotBlank(prefix) && lowerUrl.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * URL 主机名是否与给定域名配置的主机名一致（大小写不敏感）。
     *
     * @param host   已提取的 URL 主机名
     * @param domain 域名配置（可带或不带 scheme，可空）
     * @return 是否一致
     */
    private boolean hostEquals(String host, String domain) {
        if (StrUtil.isBlank(domain)) {
            return false;
        }
        String domainHost = extractHost(domain);
        return StrUtil.isNotBlank(domainHost) && domainHost.equalsIgnoreCase(host);
    }

    /**
     * HEAD 可达性探测：状态码落在 [200,400) 视为可达。
     * 抽成独立方法，便于单元测试覆写绕过真实网络。
     *
     * @param url 待探测 URL
     * @return 是否可达
     */
    protected boolean isReachable(String url) {
        try (HttpResponse response = HttpRequest.head(url).timeout(HEAD_TIMEOUT_MS).execute()) {
            int status = response.getStatus();
            return status >= HTTP_OK_MIN && status < HTTP_OK_MAX;
        } catch (Exception e) {
            log.error("HEAD探测异常, url={}", url, e);
            return false;
        }
    }

    /**
     * 提取 URL 的主机名（host），支持带或不带 scheme 的域名配置；解析失败返回空串。
     *
     * @param url URL 或域名
     * @return 主机名
     */
    private String extractHost(String url) {
        try {
            String fixed = url.contains("://") ? url : "https://" + url;
            return URI.create(fixed).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 去除尾部斜杠。
     *
     * @param value 原值
     * @return 去尾斜杠后的值
     */
    private String stripTrailingSlash(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
