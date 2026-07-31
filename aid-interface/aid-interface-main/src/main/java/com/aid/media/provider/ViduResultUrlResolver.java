package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.ViduConstants;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Vidu 生成结果 URL 解析器。
 *
 * @author 视觉AID
 */
public final class ViduResultUrlResolver {

    private ViduResultUrlResolver() {
    }

    /**
     * 只解析 Vidu creations 结果字段，忽略输入素材和回调地址。
     *
     * @param root Vidu 响应体
     * @return 有效生成结果 URL，未解析到返回 null
     */
    public static String resolve(JsonNode root) {
        String resultUrl = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_CREATIONS_0_URL,
            ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL);
        return ViduCallbackSupport.isValidCallbackBaseUrl(resultUrl) ? resultUrl : null;
    }

    /**
     * 成功状态必须同时具备有效产物 URL，否则继续等待上游产物就绪。
     *
     * @param normalizedStatus 归一化任务状态
     * @param resultUrl 产物 URL
     * @return 可供平台任务状态机使用的状态
     */
    public static String resolveReadyStatus(String normalizedStatus, String resultUrl) {
        if (ViduConstants.TASK_STATUS_SUCCEEDED.equals(normalizedStatus) && StrUtil.isBlank(resultUrl)) {
            return ViduConstants.TASK_STATUS_PROCESSING;
        }
        return normalizedStatus;
    }
}
