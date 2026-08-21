package com.aid.common.utils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 供应商相对端点校验与 URL 拼接工具。
 *
 * @author 视觉AID
 */
@Slf4j
public final class ProviderEndpointUtils
{
    public static final int MAX_ENDPOINT_LENGTH = 500;

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern ENCODED_PATH_SEPARATOR = Pattern.compile("(?i)%2f|%5c");
    private static final Pattern ENCODED_DOT = Pattern.compile("(?i)%2e");
    private static final Pattern ENCODED_CONTROL = Pattern.compile("(?i)%0a|%0d|%09");
    private static final String MODEL_PLACEHOLDER = "{model}";

    private ProviderEndpointUtils()
    {
    }

    /**
     * 规范化模型提交相对路径。
     *
     * @param endpoint 相对路径
     * @return 规范化路径
     */
    public static String normalizeSubmitPath(String endpoint)
    {
        return normalizeRelativePathWithAudit(endpoint, false);
    }

    /**
     * 规范化异步任务查询模板。
     *
     * @param endpoint 查询模板
     * @return 规范化模板
     */
    public static String normalizeTaskQueryTemplate(String endpoint)
    {
        return normalizeRelativePathWithAudit(endpoint, true);
    }

    /**
     * 拼接基础网关与提交路径。
     *
     * @param baseUrl 基础网关
     * @param endpoint 提交路径
     * @return 完整 URL
     */
    public static String buildSubmitUrl(String baseUrl, String endpoint)
    {
        return normalizeBaseUrl(baseUrl) + normalizeSubmitPath(endpoint);
    }

    /**
     * 拼接基础网关与任务查询模板。
     *
     * @param baseUrl 基础网关
     * @param endpoint 查询模板
     * @param taskId 上游任务号
     * @return 完整 URL
     */
    public static String buildTaskQueryUrl(String baseUrl, String endpoint, String taskId)
    {
        if (StrUtil.isBlank(taskId))
        {
            log.warn("供应商端点校验失败, type=taskId, reason=blank");
            throw new IllegalArgumentException("任务编号不能为空");
        }
        String template = normalizeTaskQueryTemplate(endpoint);
        String encodedTaskId = URLEncoder.encode(taskId.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return normalizeBaseUrl(baseUrl) + template.replace("%s", encodedTaskId);
    }

    /**
     * 拼接包含受控模型占位符的提交路径。
     *
     * @param baseUrl 基础网关
     * @param endpointTemplate 包含唯一 {model} 的路径
     * @param model 上游模型名
     * @return 完整 URL
     */
    public static String buildModelSubmitUrl(String baseUrl, String endpointTemplate, String model)
    {
        String template = normalizeSubmitPath(endpointTemplate);
        if (StrUtil.isBlank(model) || countOccurrences(template, MODEL_PLACEHOLDER) != 1)
        {
            log.warn("供应商端点校验失败, type=model, reason=template");
            throw new IllegalArgumentException("模型路径模板无效");
        }
        String encodedModel = URLEncoder.encode(model.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return normalizeBaseUrl(baseUrl) + template.replace(MODEL_PLACEHOLDER, encodedModel);
    }

    private static String normalizeRelativePath(String endpoint, boolean taskTemplate)
    {
        if (StrUtil.isBlank(endpoint))
        {
            throw new IllegalArgumentException(taskTemplate ? "查询路径不能为空" : "接口路径不能为空");
        }
        String normalized = endpoint.trim();
        if (normalized.length() > MAX_ENDPOINT_LENGTH)
        {
            throw new IllegalArgumentException("接口路径过长");
        }
        if (!normalized.startsWith("/") || normalized.startsWith("//"))
        {
            throw new IllegalArgumentException("必须填写相对路径");
        }
        if (normalized.indexOf('\\') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\t') >= 0)
        {
            throw new IllegalArgumentException("接口路径含非法字符");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || normalized.indexOf('#') >= 0
                || ENCODED_PATH_SEPARATOR.matcher(normalized).find()
                || ENCODED_DOT.matcher(normalized).find()
                || ENCODED_CONTROL.matcher(normalized).find())
        {
            throw new IllegalArgumentException("接口路径不安全");
        }
        String rawPath = normalized.split("\\?", 2)[0];
        if (rawPath.length() > 1 && rawPath.contains("//"))
        {
            throw new IllegalArgumentException("接口路径不安全");
        }
        for (String segment : rawPath.split("/", -1))
        {
            if (Objects.equals(".", segment) || Objects.equals("..", segment))
            {
                throw new IllegalArgumentException("接口路径不安全");
            }
        }
        validatePercentTokens(normalized, taskTemplate);
        return normalized;
    }

    private static String normalizeRelativePathWithAudit(String endpoint, boolean taskTemplate)
    {
        try
        {
            return normalizeRelativePath(endpoint, taskTemplate);
        }
        catch (IllegalArgumentException exception)
        {
            log.warn("供应商端点校验失败, type={}, length={}, reason={}",
                    taskTemplate ? "query" : "submit",
                    endpoint == null ? 0 : endpoint.length(), exception.getMessage());
            throw exception;
        }
    }

    private static void validatePercentTokens(String endpoint, boolean taskTemplate)
    {
        int placeholderCount = 0;
        for (int index = 0; index < endpoint.length(); index++)
        {
            if (endpoint.charAt(index) != '%')
            {
                continue;
            }
            if (endpoint.startsWith("%s", index))
            {
                placeholderCount++;
                index++;
                continue;
            }
            Matcher matcher = PERCENT_ESCAPE.matcher(endpoint);
            matcher.region(index, endpoint.length());
            if (!matcher.lookingAt())
            {
                throw new IllegalArgumentException("接口路径含非法占位符");
            }
            index += 2;
        }
        if (taskTemplate && placeholderCount != 1)
        {
            throw new IllegalArgumentException("查询路径必须包含一个%s");
        }
        if (!taskTemplate && placeholderCount > 0)
        {
            throw new IllegalArgumentException("提交路径不能包含%s");
        }
    }

    private static int countOccurrences(String value, String token)
    {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0)
        {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String normalizeBaseUrl(String baseUrl)
    {
        if (!GatewayUrlUtils.isBaseGatewayUrl(baseUrl))
        {
            log.warn("供应商端点校验失败, type=baseUrl, length={}",
                    baseUrl == null ? 0 : baseUrl.length());
            throw new IllegalArgumentException("基础网关无效");
        }
        return GatewayUrlUtils.normalizeBaseGatewayUrl(baseUrl);
    }
}
