package com.aid.tokendance.provider.common;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MediaInternalOptionKeys;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.OpenAiCompatiblePayloadResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** TokenDance 各协议共用的配置检查和结构化取值工具。 */
public final class TokenDancePayloadSupport
{
    private TokenDancePayloadSupport()
    {
    }

    public static void requireModel(AiModelConfigVo modelConfig, Set<String> protocols)
    {
        if (modelConfig == null || !TokenDanceProtocols.isTokenDance(modelConfig.getProviderCode())
                || protocols == null || protocols.stream().noneMatch(
                        value -> TokenDanceProtocols.matches(value, modelConfig.getProtocol())))
        {
            throw new ServiceException("模型配置错误");
        }
        if (StrUtil.isBlank(modelConfig.getBaseUrl()) || StrUtil.isBlank(modelConfig.getApiKey()))
        {
            throw new ServiceException("模型配置不完整");
        }
    }

    public static String upstreamModel(AiModelConfigVo config, String requested)
    {
        if (StrUtil.isNotBlank(requested) && config != null
                && !requested.trim().equalsIgnoreCase(StrUtil.blankToDefault(config.getModelCode(), ""))
                && !requested.trim().equalsIgnoreCase(StrUtil.blankToDefault(config.getRealModelCode(), "")))
        {
            throw new ServiceException("请求模型不匹配");
        }
        String model = ModelCodeResolver.resolveUpstreamModel(config, requested);
        if (StrUtil.isBlank(model))
        {
            throw new ServiceException("模型配置错误");
        }
        return model;
    }

    public static Map<String, Object> mergedOptions(AiModelConfigVo config, Map<String, Object> requestOptions)
    {
        Map<String, Object> raw = OpenAiCompatiblePayloadResolver.mergeExtraBody(
                config == null ? null : config.getExtraBodyJson(),
                config == null ? null : config.getModelExtraBodyJson(), requestOptions);
        return raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
    }

    /**
     * Merge only explicitly safe provider/model defaults, then overlay the already
     * validated request snapshot. Structural, media, capability and billing fields
     * must never be introduced by extra_body after quote/pre-hold validation.
     */
    public static Map<String, Object> requestScopedOptions(AiModelConfigVo config,
            Map<String, Object> requestOptions, Set<String> safeConfigKeys)
    {
        Map<String, Object> configured = mergedOptions(config, null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (safeConfigKeys != null)
        {
            for (String key : safeConfigKeys)
            {
                Object value = configured.get(key);
                if (value != null)
                {
                    result.put(key, value);
                }
            }
        }
        if (requestOptions != null && !requestOptions.isEmpty())
        {
            result.putAll(requestOptions);
        }
        return result;
    }

    public static void copyAllowlisted(Map<String, Object> target, Map<String, Object> source,
            Set<String> allowed)
    {
        if (source == null || allowed == null)
        {
            return;
        }
        for (String key : allowed)
        {
            Object value = source.get(key);
            if (value != null)
            {
                target.put(key, value);
            }
        }
    }

    /** 请求 options 中未适配的非内部字段必须在预扣前拒绝，不能静默丢弃。 */
    public static void rejectUnknownRequestOptions(AiModelConfigVo config, Map<String, Object> options, Set<String> allowed) {
        Set<String> configured = new LinkedHashSet<>(allowed);
        if (config != null && config.getCapabilityCode() != null && config.getRequestMappings() != null) {
            for (var mapping : config.getRequestMappings()) {
                String source = mapping.getSource();
                if (source != null && source.startsWith("options.")) {
                    String field = source.substring("options.".length()).split("\\.")[0];
                    configured.add(field);
                }
            }
        }
        rejectUnknownRequestOptions(options, configured);
    }

    public static void rejectUnknownRequestOptions(Map<String, Object> options, Set<String> allowed)
    {
        if (options == null || options.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet())
        {
            String key = entry.getKey();
            if (entry.getValue() != null && (key == null
                    || !allowed.contains(key) && !MediaInternalOptionKeys.isInternal(key)
                            && !"payloadSnapshot".equals(key)))
            {
                throw new ServiceException("参数尚未适配");
            }
        }
    }

    /**
     * 搜索、文件检索、计算机等上游托管工具存在独立成本可能，当前目录未提供
     * 对应 SKU 覆盖；只允许由站内执行的 function/custom 工具进入文本协议。
     */
    public static void rejectUnpricedHostedTools(Object raw)
    {
        if (raw == null)
        {
            return;
        }
        if (!(raw instanceof Iterable<?> tools))
        {
            throw new ServiceException("工具参数无效");
        }
        for (Object value : tools)
        {
            if (!(value instanceof Map<?, ?> tool))
            {
                throw new ServiceException("工具参数无效");
            }
            Object type = tool.get("type");
            if (type == null)
            {
                // Anthropic 普通客户端工具不带 type，不是上游托管工具。
                continue;
            }
            if (!(type instanceof CharSequence sequence))
            {
                throw new ServiceException("工具参数无效");
            }
            String normalized = sequence.toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("function", "custom").contains(normalized))
            {
                throw new ServiceException("PRICING_COVERAGE_REQUIRED: 托管工具计费未覆盖");
            }
        }
    }

    public static String text(Map<String, Object> options, String... keys)
    {
        if (options == null)
        {
            return null;
        }
        for (String key : keys)
        {
            Object value = options.get(key);
            String text = value == null ? null : StrUtil.trimToNull(String.valueOf(value));
            if (text != null)
            {
                return text;
            }
        }
        return null;
    }

    public static Boolean bool(Map<String, Object> options, String... keys)
    {
        String value = text(options, keys);
        if (value == null)
        {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value))
        {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value))
        {
            return Boolean.FALSE;
        }
        throw new ServiceException("布尔参数错误");
    }

    public static List<String> urls(Object raw)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        appendUrls(values, raw);
        return new ArrayList<>(values);
    }

    private static void appendUrls(Set<String> target, Object raw)
    {
        if (raw instanceof Iterable<?> iterable)
        {
            for (Object value : iterable)
            {
                appendUrls(target, value);
            }
            return;
        }
        if (raw instanceof Map<?, ?> map)
        {
            for (String key : List.of("url", "sampleUrl", "imageUrl", "videoUrl", "audioUrl"))
            {
                if (map.get(key) != null)
                {
                    appendUrls(target, map.get(key));
                    return;
                }
            }
            for (String key : List.of("image_url", "video_url", "audio_url"))
            {
                if (map.get(key) != null)
                {
                    appendUrls(target, map.get(key));
                    return;
                }
            }
            throw new com.aid.common.exception.ServiceException("素材地址错误");
        }
        if (raw instanceof CharSequence sequence)
        {
            String value = StrUtil.trimToNull(sequence.toString());
            if (value != null)
            {
                target.add(value);
            }
            return;
        }
        if (raw != null)
        {
            throw new com.aid.common.exception.ServiceException("素材地址错误");
        }
    }

    public static List<String> concatUrls(String first, Object... groups)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(first))
        {
            result.add(first.trim());
        }
        if (groups != null)
        {
            for (Object group : groups)
            {
                appendUrls(result, group);
            }
        }
        return new ArrayList<>(result);
    }

    public static void rejectUnknownProtocol(String protocol)
    {
        throw new ServiceException("协议暂不支持");
    }

    public static boolean sameProtocol(String left, String right)
    {
        return Objects.nonNull(left) && Objects.nonNull(right) && left.equalsIgnoreCase(right.trim());
    }
}
