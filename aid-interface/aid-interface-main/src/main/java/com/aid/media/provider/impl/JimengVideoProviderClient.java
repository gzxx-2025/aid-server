package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.JimengConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.provider.volcengine.VolcengineVisualSigner;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 即梦视频 Provider：统一走 visual.volcengineapi.com，按 req_key 区分模型版本。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class JimengVideoProviderClient implements VideoProviderClient {

    @Override
    public String protocol() {
        return JimengConstants.PROTOCOL_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 即梦视频：按 provider_code 精确归属（独立签名链路）
        return providerCode != null
                && JimengConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);
        String modelCode = resolveEffectiveModel(modelConfig, request);
        // req_key 依赖场景（文生/首帧/首尾帧）与分辨率（720P/1080P），必须在组装前解析
        List<String> imageUrls = resolveFrameImages(request, modelCode);
        String reqKey = resolveSubmitReqKey(modelCode, imageUrls.size(), resolveResolution(request));

        Map<String, Object> body = buildSubmitBody(reqKey, modelCode, request, imageUrls);
        applyBase64FramesIfEnabled(body, modelConfig);

        String raw;
        try {
            raw = doSignedPost(modelConfig, JimengConstants.ACTION_SUBMIT, body);
        } catch (IllegalArgumentException e) {
            // 参数/配置类异常：上抛由上层包装为 6 字内用户可见异常
            log.error("即梦视频提交参数异常, modelCode={}, msg={}", modelCode, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("即梦视频提交网络/签名异常, modelCode={}", modelCode, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }

        JsonNode root = ProviderResponseHelper.readTree(raw);
        int code = readCode(root);
        if (code != JimengConstants.RESP_CODE_SUCCESS) {
            String errMsg = ProviderResponseHelper.readText(root, JimengConstants.RESP_MESSAGE);
            String requestId = ProviderResponseHelper.readText(root, JimengConstants.RESP_REQUEST_ID);
            log.error("即梦视频提交失败, modelCode={}, code={}, message={}, request_id={}",
                    modelCode, code, errMsg, requestId);
            return ProviderSubmitResult.builder()
                    .rawResponse(raw)
                    .build();
        }
        String taskId = ProviderResponseHelper.readText(root,
                JimengConstants.RESP_DATA + "." + JimengConstants.RESP_TASK_ID,
                JimengConstants.RESP_TASK_ID);
        if (StrUtil.isBlank(taskId)) {
            log.error("即梦视频提交成功但未返回 task_id, modelCode={}, raw={}", modelCode,
                    StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
            return ProviderSubmitResult.builder()
                    .rawResponse(raw)
                    .build();
        }

        log.info("即梦视频提交成功, modelCode={}, reqKey={}, taskId={}", modelCode, reqKey, taskId);
        //    3.0 系 req_key 随场景/分辨率变化，官方要求查询必须用提交时的同一 req_key，
        //    故将其编码进 providerTaskId（taskId|reqKey），查询时解码回用。
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId + JimengConstants.VIDEO_TASK_ID_REQ_KEY_SEPARATOR + reqKey)
                .rawResponse(raw)
                .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        String modelCode = ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        // 优先解码 providerTaskId 内编码的提交 req_key；历史裸 taskId 走模型码兜底映射
        String taskId = providerTaskId;
        String reqKey = null;
        if (StrUtil.isNotBlank(providerTaskId)
                && providerTaskId.contains(JimengConstants.VIDEO_TASK_ID_REQ_KEY_SEPARATOR)) {
            int sep = providerTaskId.indexOf(JimengConstants.VIDEO_TASK_ID_REQ_KEY_SEPARATOR);
            taskId = providerTaskId.substring(0, sep);
            reqKey = providerTaskId.substring(sep + 1);
        }
        if (StrUtil.isBlank(reqKey)) {
            try {
                reqKey = resolveFallbackReqKey(modelCode);
            } catch (IllegalArgumentException e) {
                // 配置类异常属永久失败，避免无限轮询
                log.error("即梦视频查询配置错误, modelCode={}, taskId={}, msg={}", modelCode, providerTaskId, e.getMessage());
                return ProviderTaskResult.builder()
                        .status(JimengConstants.TASK_STATUS_FAILED)
                        .errorMessage(e.getMessage())
                        .build();
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(JimengConstants.JSON_REQ_KEY, reqKey);
        body.put(JimengConstants.JSON_TASK_ID, taskId);

        String raw;
        try {
            raw = doSignedPost(modelConfig, JimengConstants.ACTION_QUERY, body);
        } catch (IllegalArgumentException e) {
            log.error("即梦视频查询参数/配置错误, modelCode={}, taskId={}, msg={}",
                    modelCode, providerTaskId, e.getMessage());
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(StrUtil.blankToDefault(e.getMessage(), "即梦查询失败"))
                    .build();
        } catch (IllegalStateException e) {
            log.error("即梦视频签名环境错误, modelCode={}, taskId={}, msg={}",
                    modelCode, providerTaskId, e.getMessage());
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(StrUtil.blankToDefault(e.getMessage(), "即梦查询失败"))
                    .build();
        } catch (Exception e) {
            // 仅网络/连接类瞬时异常：保留 PROCESSING 让补偿调度重试
            log.error("即梦视频查询瞬时异常, modelCode={}, taskId={}", modelCode, providerTaskId, e);
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_PROCESSING)
                    .errorMessage(e.getMessage())
                    .build();
        }

        JsonNode root = ProviderResponseHelper.readTree(raw);
        int code = readCode(root);
        if (code != JimengConstants.RESP_CODE_SUCCESS) {
            String message = ProviderResponseHelper.readText(root, JimengConstants.RESP_MESSAGE);
            String requestId = ProviderResponseHelper.readText(root, JimengConstants.RESP_REQUEST_ID);
            log.error("即梦视频查询失败, modelCode={}, taskId={}, code={}, message={}, request_id={}",
                    modelCode, providerTaskId, code, message, requestId);
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(StrUtil.blankToDefault(message, "即梦查询失败"))
                    .rawResponse(raw)
                    .build();
        }

        String vendorStatus = ProviderResponseHelper.readText(root,
                JimengConstants.RESP_DATA + "." + JimengConstants.RESP_STATUS);
        String normalized = normalizeStatus(vendorStatus);
        String resultUrl = null;
        if (JimengConstants.TASK_STATUS_SUCCEEDED.equals(normalized)) {
            resultUrl = ProviderResponseHelper.readText(root,
                    JimengConstants.RESP_DATA + "." + JimengConstants.RESP_VIDEO_URL);
            if (StrUtil.isBlank(resultUrl)) {
                resultUrl = ProviderResponseHelper.findFirstUrl(root);
            }
            if (StrUtil.isBlank(resultUrl)) {
                log.error("即梦视频 status=done 但未解析到 video_url, modelCode={}, taskId={}, raw={}",
                        modelCode, providerTaskId,
                        StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
                return ProviderTaskResult.builder()
                        .status(JimengConstants.TASK_STATUS_FAILED)
                        .errorMessage("结果缺失")
                        .rawResponse(raw)
                        .build();
            }
        }

        return ProviderTaskResult.builder()
                .status(normalized)
                .resultUrl(resultUrl)
                .rawResponse(raw)
                .build();
    }

    // ------------------------------------------------------------------
    // 请求体构建
    // ------------------------------------------------------------------

    /**
     * 组装提交 Body：
     * 3.0 Pro —— 文生视频 prompt 必填；图生视频（首帧）图与 prompt 至少其一；
     * 3.0 —— 官方所有场景（文生/首帧/首尾帧）prompt 均为必填；
     * frames 由时长换算并收口到官方 121/241 两档，aspect_ratio 仅文生视频场景下发。
     */
    private Map<String, Object> buildSubmitBody(String reqKey, String modelCode,
                                                MediaVideoGenerateRequest request, List<String> imageUrls) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(JimengConstants.JSON_REQ_KEY, reqKey);

        boolean hasImage = !imageUrls.isEmpty();
        if (hasImage) {
            body.put(JimengConstants.JSON_IMAGE_URLS, imageUrls);
        }

        String prompt = request == null ? null : request.getPrompt();
        if (StrUtil.isNotBlank(prompt)) {
            // 文档限制 800 字符，超长截断并告警
            if (prompt.length() > JimengConstants.VIDEO_PROMPT_MAX_LENGTH) {
                log.warn("即梦视频 prompt 超长截断, 原始长度={}, 截断至={}",
                        prompt.length(), JimengConstants.VIDEO_PROMPT_MAX_LENGTH);
                prompt = prompt.substring(0, JimengConstants.VIDEO_PROMPT_MAX_LENGTH);
            }
            body.put(JimengConstants.JSON_PROMPT, prompt);
        }

        boolean isPro = JimengConstants.VIDEO_MODEL_CODE_V30_PRO.equalsIgnoreCase(modelCode);
        if (StrUtil.isBlank(prompt) && (!isPro || !hasImage)) {
            // Pro 图生场景官方允许只传图；3.0 官方所有场景 prompt 必选
            log.error("即梦视频提交缺少 prompt, modelCode={}, hasImage={}", modelCode, hasImage);
            throw new IllegalArgumentException("缺少提示词");
        }

        body.put(JimengConstants.JSON_FRAMES, resolveFrames(request));

        if (!hasImage) {
            String aspectRatio = (request != null && StrUtil.isNotBlank(request.getAspectRatio()))
                    ? request.getAspectRatio().trim()
                    : JimengConstants.VIDEO_DEFAULT_ASPECT_RATIO;
            body.put(JimengConstants.JSON_ASPECT_RATIO, aspectRatio);
        }

        if (request != null && request.getOptions() != null) {
            Object seed = request.getOptions().get(JimengConstants.OPTIONS_SEED);
            if (seed != null) {
                body.put(JimengConstants.JSON_SEED, seed);
            }
        }
        return body;
    }

    /**
     * 模型开启 Base64 传图时，把 image_urls 整体换成官方二选一的 binary_data_base64（裸 base64 数组）。
     * 任一图转换失败则保持 image_urls 原样，避免 base64/URL 混填被官方拒收。
     */
    private void applyBase64FramesIfEnabled(Map<String, Object> body, AiModelConfigVo modelConfig) {
        if (!com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
            return;
        }
        Object urlsObj = body.get(JimengConstants.JSON_IMAGE_URLS);
        if (!(urlsObj instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                urls.add(String.valueOf(item));
            }
        }
        List<String> rawBase64s = com.aid.media.provider.ReferenceImageBase64Support.toRawBase64s(urls);
        if (rawBase64s.size() == urls.size()) {
            body.remove(JimengConstants.JSON_IMAGE_URLS);
            body.put(JimengConstants.JSON_BINARY_DATA_BASE64, rawBase64s);
            log.info("即梦视频帧图按 binary_data_base64 下发, count={}", rawBase64s.size());
        } else {
            log.warn("即梦视频帧图 base64 转换不完整({}→{}), 保持 image_urls", urls.size(), rawBase64s.size());
        }
    }

    /**
     * 解析帧图列表：首帧取 request.imageUrl；尾帧取 options.lastFrameImageUrl / endImageUrl / end_image_url。
     * 官方约束：Pro 仅支持 1 张首帧（尾帧直接拒绝）；3.0 首尾帧场景须首帧+尾帧成对（顺序 [首, 尾]）。
     */
    private List<String> resolveFrameImages(MediaVideoGenerateRequest request, String modelCode) {
        if (request == null) {
            return Collections.emptyList();
        }
        String firstFrame = StrUtil.isBlank(request.getImageUrl()) ? null : request.getImageUrl().trim();
        String lastFrame = resolveLastFrameUrl(request.getOptions());

        boolean isPro = JimengConstants.VIDEO_MODEL_CODE_V30_PRO.equalsIgnoreCase(modelCode);
        if (StrUtil.isNotBlank(lastFrame)) {
            if (isPro) {
                // 官方 3.0 Pro 仅支持文生视频与图生视频-首帧，没有首尾帧场景
                log.error("即梦视频3.0Pro 不支持尾帧, modelCode={}", modelCode);
                throw new IllegalArgumentException("尾帧不支持");
            }
            if (StrUtil.isBlank(firstFrame)) {
                // 官方首尾帧接口要求输入 2 张图（首帧在前尾帧在后）
                log.error("即梦视频首尾帧缺少首帧图, modelCode={}", modelCode);
                throw new IllegalArgumentException("缺少首帧图");
            }
            List<String> pair = new ArrayList<>(2);
            pair.add(firstFrame);
            pair.add(lastFrame.trim());
            return pair;
        }
        if (StrUtil.isBlank(firstFrame)) {
            return Collections.emptyList();
        }
        List<String> single = new ArrayList<>(1);
        single.add(firstFrame);
        return single;
    }

    /**
     * 尾帧 URL 提取：与其他视频 Provider 对齐的三个业务键，任一非空即取。
     */
    private String resolveLastFrameUrl(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (String key : new String[]{"lastFrameImageUrl", "endImageUrl", "end_image_url"}) {
            Object v = options.get(key);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /**
     * 解析分辨率档位（req_key 选择用）：options.resolution 显式传入优先；
     * 缺省 720P，与计费层 resolution 缺省推断（720p）保持同一口径，避免按 720P 扣费实际出 1080P。
     */
    private String resolveResolution(MediaVideoGenerateRequest request) {
        if (request != null && request.getOptions() != null) {
            Object v = request.getOptions().get(JimengConstants.OPTIONS_RESOLUTION);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                String normalized = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
                if (normalized.contains("1080")) {
                    return JimengConstants.VIDEO_RESOLUTION_1080P;
                }
                return JimengConstants.VIDEO_RESOLUTION_720P;
            }
        }
        return JimengConstants.VIDEO_RESOLUTION_720P;
    }

    /**
     * 时长（秒）→ frames：frames = 24 * 秒数 + 1。
     * 官方仅支持 5s（121 帧）与 10s（241 帧），其他秒数就近收口（<=7 归 5s，>7 归 10s）并告警。
     */
    private int resolveFrames(MediaVideoGenerateRequest request) {
        int seconds = (request != null && request.getDurationSeconds() != null && request.getDurationSeconds() > 0)
                ? request.getDurationSeconds()
                : JimengConstants.VIDEO_DEFAULT_DURATION_SECONDS;
        int snapped = seconds <= JimengConstants.VIDEO_DURATION_SNAP_THRESHOLD_SECONDS
                ? JimengConstants.VIDEO_DURATION_SHORT_SECONDS
                : JimengConstants.VIDEO_DURATION_LONG_SECONDS;
        if (snapped != seconds) {
            log.warn("即梦视频时长收口: 请求={}s, 实际下发={}s（官方仅支持5s/10s）", seconds, snapped);
        }
        return JimengConstants.VIDEO_FRAMES_PER_SECOND_FACTOR * snapped + 1;
    }

    // ------------------------------------------------------------------
    // 发起带签名的 POST（与图片 Provider 同一套 SigV4 链路）
    // ------------------------------------------------------------------

    private String doSignedPost(AiModelConfigVo modelConfig, String action, Map<String, Object> body) {
        if (Objects.isNull(modelConfig)
                || StrUtil.isBlank(modelConfig.getApiKey())
                || StrUtil.isBlank(modelConfig.getApiSecret())) {
            throw new IllegalArgumentException("即梦未配置 AK/SK");
        }
        String baseUrl = StrUtil.blankToDefault(
                modelConfig.getBaseUrl(), JimengConstants.DEFAULT_BASE_URL).trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String host = parseHostOrDefault(baseUrl);

        Map<String, String> query = new LinkedHashMap<>();
        query.put(JimengConstants.QUERY_ACTION, action);
        query.put(JimengConstants.QUERY_VERSION, JimengConstants.API_VERSION);
        String queryString = JimengConstants.QUERY_ACTION + '=' + action
                + '&' + JimengConstants.QUERY_VERSION + '=' + JimengConstants.API_VERSION;
        String fullUrl = baseUrl + "/?" + queryString;

        String payload = JSONUtil.toJsonStr(body);

        Map<String, String> signedHeaders = VolcengineVisualSigner.sign(
                modelConfig.getApiKey(),
                modelConfig.getApiSecret(),
                JimengConstants.REGION,
                JimengConstants.SERVICE,
                host,
                "POST",
                "/",
                query,
                JimengConstants.CONTENT_TYPE_JSON,
                payload);

        ModelIoDump.req(fullUrl, payload); // 【临时调试】记录下发上游入参
        HttpRequest post = HttpRequest.post(fullUrl)
                .body(payload)
                .timeout(JimengConstants.HTTP_TIMEOUT_MS);
        for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
            post.header(entry.getKey(), entry.getValue(), true);
        }
        try (HttpResponse response = post.execute()) {
            String raw = ModelIoDump.resp(fullUrl, response.body()); // 【临时调试】记录上游出参
            if (!response.isOk()) {
                log.error("即梦视频 HTTP 非 2xx, status={}, raw={}", response.getStatus(),
                        StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
            }
            return raw;
        }
    }

    /**
     * 从完整 URL 解析 host；失败回退默认 host。
     */
    private String parseHostOrDefault(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (StrUtil.isNotBlank(host)) {
                return host;
            }
        } catch (IllegalArgumentException ignore) {
            // 非法 baseUrl 交给默认值兜底
        }
        return JimengConstants.DEFAULT_HOST;
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    /**
     * 读取外层 code，容错为 0 便于上层识别异常。
     */
    private int readCode(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode node = root.get(JimengConstants.RESP_CODE);
        return (node == null || !node.isNumber()) ? 0 : node.asInt();
    }

    /**
     * 即梦 data.status → 平台统一状态。
     */
    private String normalizeStatus(String vendorStatus) {
        if (StrUtil.isBlank(vendorStatus)) {
            // 状态缺失按处理中，由上层继续轮询
            return JimengConstants.TASK_STATUS_PROCESSING;
        }
        String lower = vendorStatus.toLowerCase();
        if (JimengConstants.VENDOR_STATUS_DONE.equals(lower)) {
            return JimengConstants.TASK_STATUS_SUCCEEDED;
        }
        if (JimengConstants.VENDOR_STATUS_NOT_FOUND.equals(lower)
                || JimengConstants.VENDOR_STATUS_EXPIRED.equals(lower)) {
            return JimengConstants.TASK_STATUS_FAILED;
        }
        return JimengConstants.TASK_STATUS_PROCESSING;
    }

    /**
     * 解析真实上游模型名：展示码 model_code 与 real_model_code 解耦。
     */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        return ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
    }

    /**
     * 提交 req_key 选择（官方矩阵）：
     * 3.0 Pro 固定 jimeng_ti2v_v30_pro（文生/首帧共用）；
     * 3.0 按「帧图数量 × 分辨率」选择 6 个官方 req_key 之一（0图=文生，1图=首帧，2图=首尾帧）。
     */
    private String resolveSubmitReqKey(String modelCode, int frameImageCount, String resolution) {
        if (StrUtil.isBlank(modelCode)) {
            log.error("即梦视频 modelCode 为空");
            throw new IllegalArgumentException("缺少模型");
        }
        String lower = modelCode.toLowerCase(Locale.ROOT);
        if (JimengConstants.VIDEO_MODEL_CODE_V30_PRO.equals(lower)) {
            return JimengConstants.VIDEO_REQ_KEY_V30_PRO;
        }
        if (JimengConstants.VIDEO_MODEL_CODE_V30.equals(lower)) {
            boolean is1080 = JimengConstants.VIDEO_RESOLUTION_1080P.equalsIgnoreCase(resolution);
            if (frameImageCount >= 2) {
                return is1080 ? JimengConstants.VIDEO_REQ_KEY_V30_I2V_FIRST_TAIL_1080
                        : JimengConstants.VIDEO_REQ_KEY_V30_I2V_FIRST_TAIL_720;
            }
            if (frameImageCount == 1) {
                return is1080 ? JimengConstants.VIDEO_REQ_KEY_V30_I2V_FIRST_1080
                        : JimengConstants.VIDEO_REQ_KEY_V30_I2V_FIRST_720;
            }
            return is1080 ? JimengConstants.VIDEO_REQ_KEY_V30_T2V_1080
                    : JimengConstants.VIDEO_REQ_KEY_V30_T2V_720;
        }
        log.error("即梦视频 modelCode 未在固定映射中, modelCode={}", modelCode);
        throw new IllegalArgumentException("模型不支持");
    }

    /**
     * 查询兜底 req_key（仅历史裸 taskId 无编码时使用）；找不到直接抛错，避免误发请求。
     */
    private String resolveFallbackReqKey(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            log.error("即梦视频 modelCode 为空");
            throw new IllegalArgumentException("缺少模型");
        }
        String reqKey = JimengConstants.VIDEO_MODEL_CODE_TO_REQ_KEY.get(modelCode.toLowerCase(Locale.ROOT));
        if (StrUtil.isBlank(reqKey)) {
            log.error("即梦视频 modelCode 未在固定映射中, modelCode={}", modelCode);
            throw new IllegalArgumentException("模型不支持");
        }
        return reqKey;
    }
}
