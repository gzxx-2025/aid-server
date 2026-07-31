package com.aid.common.aid.oss.serializer;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.utils.spring.SpringUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 媒体URL字段 Jackson 反序列化器（与 {@link MediaUrlSerializer} 对称）。
 *
 * @author 视觉AID
 */
public class MediaUrlDeserializer extends JsonDeserializer<String>
{
    /**
     * 缓存 OSS 配置管理器
     */
    private volatile OssConfigManager cachedManager;

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        String value = p.getValueAsString();
        if (StrUtil.isBlank(value))
        {
            return value;
        }
        OssConfigManager manager = resolveManager();
        if (Objects.isNull(manager))
        {
            // Spring 未就绪，原样保留
            return value;
        }
        OssProperties properties = manager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return value;
        }
        // 优先剥离 CDN 域名
        String cdnDomain = stripTrailingSlash(properties.getCdnDomain());
        if (StrUtil.isNotBlank(cdnDomain) && value.startsWith(cdnDomain))
        {
            String rest = value.substring(cdnDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        // 再剥离 localDomain
        String localDomain = stripTrailingSlash(properties.getLocalDomain());
        if (StrUtil.isNotBlank(localDomain) && value.startsWith(localDomain))
        {
            String rest = value.substring(localDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        // 未匹配任何配置域名，原样保留（外部链接 / 历史完整 URL）
        return value;
    }

    /**
     * 延迟拿 OssConfigManager bean
     */
    private OssConfigManager resolveManager()
    {
        if (Objects.nonNull(cachedManager))
        {
            return cachedManager;
        }
        try
        {
            cachedManager = SpringUtils.getBean(OssConfigManager.class);
        }
        catch (Exception ignore)
        {
            // ignore
        }
        return cachedManager;
    }

    private String stripTrailingSlash(String domain)
    {
        if (StrUtil.isBlank(domain))
        {
            return domain;
        }
        return domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
    }
}
