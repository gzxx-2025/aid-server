package com.aid.tokendance.provider.common;

import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.ProviderEndpointUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** TokenDance 官方生成路径及安全任务号替换。 */
public final class TokenDanceEndpoints
{
    public static final String CHAT_COMPLETIONS = "/gateway/v1/chat/completions";
    public static final String RESPONSES = "/gateway/v1/responses";
    public static final String MESSAGES = "/gateway/v1/messages";
    public static final String OPENAI_IMAGES = "/gateway/v1/images/generations";
    public static final String ARK_IMAGES = "/gateway/ark/v3/images/generations";
    public static final String SEEDANCE_SUBMIT = "/gateway/ark/v3/generations/tasks";
    public static final String SEEDANCE_QUERY = "/gateway/ark/v3/generations/tasks/%s";
    public static final String KLING_TEXT_SUBMIT = "/gateway/kling/v1/text2video";
    public static final String KLING_TEXT_QUERY = "/gateway/kling/v1/text2video/%s";
    public static final String KLING_IMAGE_SUBMIT = "/gateway/kling/v1/image2video";
    public static final String KLING_IMAGE_QUERY = "/gateway/kling/v1/image2video/%s";
    public static final String KLING_OMNI_SUBMIT = "/gateway/kling/v1/omni-video";
    public static final String KLING_OMNI_QUERY = "/gateway/kling/v1/omni-video/%s";
    public static final String WAN3_SUBMIT = "/gateway/alibaba/wan3/v1/video-synthesis";
    public static final String WAN3_QUERY = "/gateway/alibaba/wan3/v1/tasks/%s";
    public static final String HAPPYHORSE_SUBMIT = "/gateway/alibaba/happyhorse/v1/video-synthesis";
    public static final String HAPPYHORSE_QUERY = "/gateway/alibaba/happyhorse/v1/tasks/%s";
    public static final String MINIMAX_VIDEO_SUBMIT = "/gateway/minimax/v2/video_generation";
    public static final String MINIMAX_VIDEO_QUERY = "/gateway/minimax/v2/query/video_generation/%s";

    private TokenDanceEndpoints()
    {
    }

    public static String submitPath(com.aid.domain.vo.AiModelConfigVo config, String fallback) {
        return config.getCapabilityCode() != null && StrUtil.isNotBlank(config.getApiSuffix())
                ? ProviderEndpointUtils.normalizeSubmitPath(config.getApiSuffix()) : fallback;
    }

    public static String queryPath(com.aid.domain.vo.AiModelConfigVo config, String fallback, String taskId) {
        return queryPath(config.getCapabilityCode() != null && StrUtil.isNotBlank(config.getTaskQuerySuffix())
                ? config.getTaskQuerySuffix() : fallback, taskId);
    }

    public static String queryPath(String template, String taskId)
    {
        if (StrUtil.isBlank(taskId))
        {
            throw new IllegalArgumentException("任务编号不能为空");
        }
        String normalized = ProviderEndpointUtils.normalizeTaskQueryTemplate(template);
        String encoded = URLEncoder.encode(taskId.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        return normalized.replace("%s", encoded);
    }
}
