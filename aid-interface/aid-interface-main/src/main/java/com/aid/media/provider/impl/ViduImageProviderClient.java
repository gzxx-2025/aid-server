package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import org.apache.commons.lang3.StringUtils;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.billing.util.ResolutionUtil;
import com.aid.common.constant.HttpConstants;
import com.aid.media.constants.ViduConstants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ModelIoDump; // 【测试日志·上线必删】入参/出参落盘工具
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ViduStatusMapper;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vidu 图片生成适配器：
 * - 提交接口：POST /ent/v2/reference2image
 * - 查询接口：优先 /ent/v2/tasks/{taskId}/creations，再回退 /ent/v2/tasks/{taskId}
 */
@Slf4j
@Component
public class ViduImageProviderClient implements ImageProviderClient {

    /** Vidu 参考生图官方上限（viduq2：0～7 张），超出则截断，避免整单被厂商拒绝。 */
    private static final int VIDU_IMAGE_REFERENCE_MAX = 7;

    @Override
    public String protocol() {
        // 返回图片协议名称。
        return ViduConstants.PROTOCOL_IMAGE;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // Vidu 图片：按 provider_code 精确归属
        return providerCode != null
                && ViduConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);
        String submitUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> body = buildSubmitBody(modelConfig, request);
        String bodyJson = JSONUtil.toJsonStr(body);
        ModelIoDump.req(submitUrl, bodyJson); // 【测试日志·上线必删】记录下发上游入参
        String raw = doPost(submitUrl, modelConfig.getApiKey(), bodyJson);
        ModelIoDump.resp(submitUrl, raw); // 【测试日志·上线必删】记录上游出参
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskId = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_TASK_ID, ViduConstants.JSON_PATH_DATA_TASK_ID, ViduConstants.JSON_ID, ViduConstants.JSON_PATH_DATA_ID);
        String directUrl = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL, ViduConstants.JSON_PATH_CREATIONS_0_URL,
            ViduConstants.JSON_IMAGE_URL, ViduConstants.JSON_PATH_DATA_IMAGE_URL, ViduConstants.JSON_PATH_DATA_URL);
        if (StrUtil.isBlank(directUrl)) {
            directUrl = ProviderResponseHelper.findFirstUrl(root);
        }
        return ProviderSubmitResult.builder()
            .providerTaskId(taskId)
            .directUrl(directUrl)
            .rawResponse(raw)
            .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        String queryUrl = buildTaskUrl(modelConfig.getBaseUrl(), modelConfig.getTaskQuerySuffix(), providerTaskId);
        String raw = doGet(queryUrl, modelConfig.getApiKey());
        ModelIoDump.resp(queryUrl, raw); // 【测试日志·上线必删】记录上游出参（轮询查询）
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String status = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_STATE, ViduConstants.JSON_STATUS,
            ViduConstants.JSON_PATH_DATA_STATE, ViduConstants.JSON_PATH_DATA_STATUS, ViduConstants.JSON_TASK_STATUS);
        String normalized = ViduStatusMapper.normalizeStatus(status);
        String url = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL, ViduConstants.JSON_PATH_CREATIONS_0_URL,
            ViduConstants.JSON_PATH_OUTPUT_RESULTS_0_URL, ViduConstants.JSON_PATH_DATA_URL, ViduConstants.JSON_URL);
        if (StrUtil.isBlank(url)) {
            url = ProviderResponseHelper.findFirstUrl(root);
        }
        String error = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_ERROR_MESSAGE, ViduConstants.JSON_PATH_ERROR_MSG, ViduConstants.JSON_ERROR,
            ViduConstants.JSON_MESSAGE, ViduConstants.JSON_PATH_DATA_ERROR, ViduConstants.JSON_PATH_DATA_MESSAGE);
        return ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(url)
            .errorMessage(error)
            .rawResponse(raw)
            .build();
    }

    private Map<String, Object> buildSubmitBody(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String modelName = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(
                modelConfig, request.getModelName());
        if (StrUtil.isBlank(modelName) || ViduConstants.PROTOCOL_IMAGE.equalsIgnoreCase(modelName)) {
            modelName = ViduConstants.DEFAULT_IMAGE_MODEL_CODE;
        }
        if (StrUtil.isNotBlank(modelName)) {
            body.put(ViduConstants.JSON_MODEL, modelName);
        }
        if (StrUtil.isNotBlank(request.getPrompt())) {
            body.put(ViduConstants.JSON_PROMPT, request.getPrompt());
        }
        List<String> images = resolveImages(request, modelConfig);
        if (!images.isEmpty()) {
            // Base64 传图开关：官方 images 支持 data URI（data:image/xxx;base64,...），启用时下载转内联下发
            if (com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
                images = com.aid.media.provider.ReferenceImageBase64Support.toDataUris(images);
                log.info("Vidu 参考图按 base64 内联下发, count={}", images.size());
            }
            body.put(ViduConstants.JSON_IMAGES, images);
        }
        String ratio = inferAspectRatioBySize(request.getSize());
        if (StrUtil.isNotBlank(ratio)) {
            body.put(ViduConstants.JSON_ASPECT_RATIO, ratio);
        }
        // 分辨率档位下发：size 本身是档位串（"1080p"/"2K"/"4K"）时映射为官方 resolution 字段，
        // 保证与计费侧取值同源；options.resolution 显式传入时在 mergeOptions 中覆盖本值。
        String sizeTier = ResolutionUtil.parseTier(request.getSize());
        if (StrUtil.isNotBlank(sizeTier)) {
            body.put(ViduConstants.JSON_RESOLUTION, sizeTier);
        }
        mergeOptions(body, request.getOptions());
        applyCallbackUrl(body, modelConfig);
        return body;
    }

    /**
     * 注入回调地址（开关）：读取供应商/模型 schedule_strategy_json.callbackBaseUrl，非空则下发 callback_url。
     * 未配置一律降级为「不下发」，回调仅作加速，轮询始终兜底。
     */
    private void applyCallbackUrl(Map<String, Object> body, AiModelConfigVo modelConfig) {
        String callbackUrl = com.aid.media.provider.ViduCallbackSupport.resolveCallbackBaseUrl(modelConfig);
        if (StrUtil.isNotBlank(callbackUrl)) {
            body.put(ViduConstants.JSON_CALLBACK_URL, callbackUrl);
        }
    }

    /**
     * 解析 Vidu reference2image 的 images 数组：与 Seedream 侧约定一致，优先显式 images，其次业务 referenceImages，最后单图 URL。
     */
    private List<String> resolveImages(MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        List<String> images = new ArrayList<>();
        Map<String, Object> options = request.getOptions();
        // 业务含义：调用方在 options.images 中直接传官方字段所需的 URL/Base64 列表时优先采用。
        if (options != null && options.get(ViduConstants.OPTIONS_KEY_IMAGES) instanceof List<?> rawImages) {
            for (Object item : rawImages) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    images.add(String.valueOf(item));
                }
            }
        }
        // 业务含义：分镜/业务层将多资产参考图放在 referenceImages，与视频侧 OPTIONS_REFERENCE_IMAGES 同名，避免只传首张图。
        if (images.isEmpty() && options != null
                && options.get(VolcengineConstants.OPTIONS_REFERENCE_IMAGES) instanceof List<?> rawRefs) {
            for (Object item : rawRefs) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    images.add(String.valueOf(item));
                }
            }
        }
        // 业务含义：仅配置单张参考图时走 DTO 顶层字段，兼容历史请求。
        if (images.isEmpty() && StrUtil.isNotBlank(request.getReferenceImageUrl())) {
            images.add(request.getReferenceImageUrl());
        }
        // 统一上限：读 capability_json.maxReferenceImages，缺省回退 Vidu 官方默认 7 张；超限截断 + warn
        return ReferenceImageLimiter.limit(images, modelConfig, VIDU_IMAGE_REFERENCE_MAX, "Vidu");
    }

    private String inferAspectRatioBySize(String size) {
        if (StrUtil.isBlank(size) || !size.contains("*")) {
            return null;
        }
        String[] parts = size.split(ViduConstants.SIZE_DIMENSION_SPLIT_REGEX);
        if (parts.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            int g = gcd(w, h);
            return (w / g) + ":" + (h / g);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int gcd(int a, int b) {
        // 欧几里得算法计算最大公约数。
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    private void mergeOptions(Map<String, Object> body, Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // 平台内部上下文/计费键（sbzImageGenCtx / referenceManifest / force_single 等）禁止透传官方接口
            if (com.aid.media.constants.MediaInternalOptionKeys.isInternal(key)) {
                continue;
            }
            // 比例由本 Provider 依据 size 自算并写入官方字段，options 里的平台键不再重复下发
            if (ViduConstants.JSON_ASPECT_RATIO.equals(key) || "aspectRatio".equals(key)) {
                continue;
            }
            if (ViduConstants.OPTIONS_KEY_IMAGES.equals(key)
                    || VolcengineConstants.OPTIONS_REFERENCE_IMAGES.equals(key)) {
                continue;
            }
            //    Vidu 官方不识别，blind putAll 会造成非法字段被拒绝。
            if ("force_single".equals(key) || "n".equals(key) || "expectedImageCount".equals(key)) {
                continue;
            }
            //    防止回调被重定向到任意地址。
            if (ViduConstants.JSON_CALLBACK_URL.equals(key)) {
                continue;
            }
            body.put(key, value);
        }
    }

    /**
     * 构建 API 请求 URL：base_url + api_suffix（路径全部来自数据库配置）
     */
    private String buildApiUrl(String baseUrl, String apiSuffix) {
        if (StrUtil.isBlank(baseUrl)) {
            log.error("vidu image model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(apiSuffix)) {
            log.error("vidu image model apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        return trimSlash(baseUrl.trim()) + apiSuffix;
    }

    /**
     * 构建任务查询 URL：base_url + task_query_suffix（路径全部来自数据库配置，纯数据库驱动）
     */
    private String buildTaskUrl(String baseUrl, String taskQuerySuffix, String providerTaskId) {
        if (StrUtil.isBlank(baseUrl)) {
            log.error("vidu image model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(taskQuerySuffix)) {
            log.error("vidu image model taskQuerySuffix 为空，请在 aid_ai_provider 表配置 task_query_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StrUtil.isBlank(providerTaskId)) {
            return trimSlash(baseUrl);
        }
        return trimSlash(baseUrl.trim()) + String.format(taskQuerySuffix, providerTaskId);
    }

    private String doPost(String url, String apiKey, String json) {
        // Vidu 官方鉴权：Authorization: Token {apiKey}。
        // 仅用标准 Authorization 头，避免 API Key 在多处出现被中间代理重复记录。
        try (HttpResponse response = HttpRequest.post(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, ViduConstants.AUTH_TOKEN_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .body(json)
            .timeout(ViduConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            return response.body();
        }
    }

    private String doGet(String url, String apiKey) {
        // 查询同样采用 Token 鉴权。
        try (HttpResponse response = HttpRequest.get(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, ViduConstants.AUTH_TOKEN_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .timeout(ViduConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            return response.body();
        }
    }

    private String trimSlash(String value) {
        // 清理末尾斜杠，避免路径出现双斜杠。
        if (value != null && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
