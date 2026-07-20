package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.constant.HttpConstants;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.VideoProviderClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DashScope 视频 Provider
 *
 * 业务定位：
 * 1) 承载百炼北京地域下的视频模型族接入（wan/wanx、kling、pixverse）。
 * 2) 对外暴露统一 submit/query 契约，内部按模型族选择请求方言。
 * 3) 兼容“apiHost 配域名”与“apiHost 配完整路径”两种部署方式。
 */
@Component
public class DashscopeVideoProviderClient implements VideoProviderClient {

    // 模型方言列表：按顺序匹配，优先命中特化方言。
    private static final List<VideoDialect> DIALECTS = initDialects();

    @Override
    public String protocol() {
        // 返回协议名称，供服务层 getVideoClient()/resolveVideoClient() 路由。
        return DashscopeConstants.PROTOCOL_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 阿里百炼视频（万相/可灵/爱诗）：按 provider_code 精确归属
        return providerCode != null
                && DashscopeConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        VideoDialect dialect = resolveDialect(effectiveModel);
        String submitUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> body = dialect.buildSubmitBody(effectiveModel, request, modelConfig);
        String raw = doPost(submitUrl, modelConfig.getApiKey(), JSONUtil.toJsonStr(body));
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskId = ProviderResponseHelper.readText(root, "output.task_id", "task_id", "data.task_id");
        String directUrl = ProviderResponseHelper.readText(root,
            "output.video_url",
            "video_url",
            "data.video_url",
            "data.url");
        if (StringUtils.isBlank(directUrl)) {
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
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskStatus = ProviderResponseHelper.readText(root,
            "output.task_status",
            "task_status",
            "data.task_status",
            "status");
        String normalizedStatus = normalizeStatus(taskStatus);
        String videoUrl = ProviderResponseHelper.readText(root,
            "output.video_url",
            "video_url",
            "data.video_url",
            "output.result.video_url",
            "output.result_url",
            "data.url");
        if (StringUtils.isBlank(videoUrl)) {
            videoUrl = ProviderResponseHelper.findFirstUrl(root);
        }
        String error = ProviderResponseHelper.readText(root,
            "output.message",
            "output.error_message",
            "message",
            "error.message",
            "error");
        Integer videoDuration = ProviderResponseHelper.readInt(root,
            "usage.output_video_duration",
            "usage.video_duration",
            "usage.duration");
        return ProviderTaskResult.builder()
            .status(normalizedStatus)
            .resultUrl(videoUrl)
            .errorMessage(error)
            .rawResponse(raw)
            .videoDurationSeconds(videoDuration)
            .build();
    }

    private String normalizeStatus(String status) {
        // 空状态按处理中返回，避免误判终态。
        if (StringUtils.isBlank(status)) {
            return DashscopeConstants.TASK_STATUS_PROCESSING;
        }
        // 转大写做包含判断，兼容不同供应商大小写风格。
        String upper = status.toUpperCase();
        // 成功关键字统一映射 SUCCEEDED。
        if (upper.contains(DashscopeConstants.VENDOR_TOKEN_SUCC) || upper.contains(DashscopeConstants.VENDOR_TOKEN_COMPLETE)) {
            return DashscopeConstants.TASK_STATUS_SUCCEEDED;
        }
        // 失败/取消/未知统一映射 FAILED，防止前端无限轮询。
        if (upper.contains(DashscopeConstants.VENDOR_TOKEN_FAIL) || upper.contains(DashscopeConstants.VENDOR_TOKEN_ERROR)
            || upper.contains(DashscopeConstants.VENDOR_TOKEN_CANCEL) || upper.contains(DashscopeConstants.VENDOR_TOKEN_UNKNOWN)) {
            return DashscopeConstants.TASK_STATUS_FAILED;
        }
        // 其余状态（PENDING/RUNNING 等）统一映射 PROCESSING。
        return DashscopeConstants.TASK_STATUS_PROCESSING;
    }

    /**
     * 构建 API 请求 URL：base_url + api_suffix（路径全部来自数据库配置）
     */
    private String buildApiUrl(String baseUrl, String apiSuffix) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("dashscope video model baseUrl 不能为空");
        }
        if (StringUtils.isBlank(apiSuffix)) {
            throw new IllegalArgumentException("dashscope video model apiSuffix 不能为空，请在 aid_ai_model 表配置");
        }
        return trimSlash(baseUrl.trim()) + apiSuffix;
    }

    private String buildTaskUrl(String baseUrl, String taskQuerySuffix, String providerTaskId) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("dashscope video model baseUrl 不能为空");
        }
        if (StringUtils.isBlank(taskQuerySuffix)) {
            throw new IllegalArgumentException("dashscope video model taskQuerySuffix 不能为空，请在 aid_ai_provider 表配置");
        }
        if (StringUtils.isBlank(providerTaskId)) {
            return baseUrl;
        }
        // 完整查询 URL = base_url + String.format(task_query_suffix, taskId)
        return trimSlash(baseUrl.trim()) + String.format(taskQuerySuffix, providerTaskId);
    }

    private String doPost(String url, String apiKey, String json) {
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        try (HttpResponse response = HttpRequest.post(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .header(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE)
            .body(json)
            .timeout(HttpConstants.DEFAULT_TIMEOUT_MS)
            .execute()) {
            return ModelIoDump.resp(url, response.body()); // 【临时调试】记录上游出参
        }
    }

    private String doGet(String url, String apiKey) {
        try (HttpResponse response = HttpRequest.get(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .timeout(HttpConstants.DEFAULT_TIMEOUT_MS)
            .execute()) {
            return ModelIoDump.resp(url, response.body()); // 【临时调试】记录上游出参（轮询）
        }
    }

    private String trimSlash(String base) {
        // 去除末尾 /，避免路径拼接出现双斜杠。
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        // 双方都为空时返回空串，交由上游接口返回参数错误。
        return "";
    }

    private VideoDialect resolveDialect(String modelName) {
        String normalized = StringUtils.defaultString(modelName).toLowerCase();
        for (VideoDialect dialect : DIALECTS) {
            if (dialect.supports(normalized)) {
                return dialect;
            }
        }
        return GeneralVideoSynthesisDialect.INSTANCE;
    }

    private static List<VideoDialect> initDialects() {
        List<VideoDialect> dialects = new ArrayList<>();
        dialects.add(LegacyImage2VideoDialect.INSTANCE);
        dialects.add(HappyHorseVideoDialect.INSTANCE);
        dialects.add(PixverseVideoDialect.INSTANCE);
        dialects.add(KlingVideoDialect.INSTANCE);
        dialects.add(WanVideoDialect.INSTANCE);
        dialects.add(GeneralVideoSynthesisDialect.INSTANCE);
        return Collections.unmodifiableList(dialects);
    }

    private interface VideoDialect {

        // 判断当前方言是否支持该模型。
        boolean supports(String normalizedModelName);

        // 构建提交请求体（modelConfig 供方言读取 capability_json，如参考图上限等）。
        Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                            AiModelConfigVo modelConfig);
    }

    private abstract static class AbstractVideoDialect implements VideoDialect {

        // 构建通用请求体骨架，并合并 options 中的 input/parameters/top-level 字段。
        protected Map<String, Object> buildBaseBody(String modelName, MediaVideoGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> input = new LinkedHashMap<>();
            if (StringUtils.isNotBlank(request.getPrompt())) {
                input.put(DashscopeConstants.JSON_PROMPT, request.getPrompt());
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            mergeOptions(body, input, parameters, request);
            body.put(DashscopeConstants.JSON_INPUT, input);
            if (!parameters.isEmpty()) {
                body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            }
            return body;
        }

        @SuppressWarnings("unchecked")
        protected Map<String, Object> getOrCreateInput(Map<String, Object> body) {
            if (!(body.get(DashscopeConstants.JSON_INPUT) instanceof Map<?, ?>)) {
                Map<String, Object> input = new LinkedHashMap<>();
                body.put(DashscopeConstants.JSON_INPUT, input);
                return input;
            }
            return (Map<String, Object>) body.get(DashscopeConstants.JSON_INPUT);
        }

        @SuppressWarnings("unchecked")
        protected Map<String, Object> getOrCreateParameters(Map<String, Object> body) {
            if (!(body.get(DashscopeConstants.JSON_PARAMETERS) instanceof Map<?, ?>)) {
                Map<String, Object> parameters = new LinkedHashMap<>();
                body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
                return parameters;
            }
            return (Map<String, Object>) body.get(DashscopeConstants.JSON_PARAMETERS);
        }

        /**
         * 顶层 options 字段白名单。
         * 仅允许经过评估的协议顶层字段透传，避免前端通过 options 把 {@code model}/{@code task_id}/{@code apiKey}
         * 等任意字段塞进请求体，覆盖系统已解析的模型名或注入鉴权参数；其余一律忽略。
         */
        private static final java.util.Set<String> ALLOWED_TOP_LEVEL_OPTIONS = java.util.Set.of(
                "resources",
                "extra_params",
                "seed"
        );

        /**
         * 将 options 中 input/parameters 及其余字段合并到请求体，支持模型差异参数透传。
         * @param body
         * @param input
         * @param parameters
         * @param request
         */
        protected void mergeOptions(Map<String, Object> body,
                                    Map<String, Object> input,
                                    Map<String, Object> parameters,
                                    MediaVideoGenerateRequest request) {
            Map<String, Object> options = request.getOptions();
            if (options == null || options.isEmpty()) {
                return;
            }
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (DashscopeConstants.OPTIONS_KEY_INPUT.equals(key) && value instanceof Map<?, ?> inputRaw) {
                    input.putAll(toStringObjectMap(inputRaw));
                    continue;
                }
                if (DashscopeConstants.OPTIONS_KEY_PARAMETERS.equals(key) && value instanceof Map<?, ?> paramsRaw) {
                    parameters.putAll(toStringObjectMap(paramsRaw));
                    continue;
                }
                //    避免前端通过 options 注入 `model`/`apiKey` 等敏感字段覆盖系统已解析值。
                if (ALLOWED_TOP_LEVEL_OPTIONS.contains(key)) {
                    body.put(key, value);
                }
            }
        }

        protected Map<String, Object> toStringObjectMap(Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
    }

    /**
     * HappyHorse 参考生视频方言（happyhorse-1.0-r2v）。
     */
    private static final class HappyHorseVideoDialect extends AbstractVideoDialect {

        // 单例实例：匹配 HappyHorse 参考生视频模型。
        private static final HappyHorseVideoDialect INSTANCE = new HappyHorseVideoDialect();

        // 系统私有占位「图片N」→ 文档「[Image N]」转换正则。
        private static final Pattern IMAGE_REF_PATTERN = Pattern.compile("图片(\\d+)");

        @Override
        public boolean supports(String normalizedModelName) {
            // happyhorse-1.0-r2v 等参考生视频模型，按 happyhorse 前缀命中。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_HAPPYHORSE_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);

            //    request.prompt 已在 submit() 首行 sanitize；此处再 sanitize 一次保证幂等去占位/映射段，再转 [Image N]。
            String sysPrompt = ReferencePromptSanitizer.sanitize(StringUtils.defaultString(request.getPrompt()));
            if (StringUtils.isNotBlank(sysPrompt)) {
                input.put(DashscopeConstants.JSON_PROMPT, IMAGE_REF_PATTERN.matcher(sysPrompt).replaceAll("[Image $1]"));
            } else {
                input.remove(DashscopeConstants.JSON_PROMPT);
            }

            //    再按 reference_image 重建；上限走 ReferenceImageLimiter(读 capability)，文档硬上限 9 兜底。
            input.remove(DashscopeConstants.JSON_MEDIA);
            List<String> refs = extractReferenceImages(request);
            refs = ReferenceImageLimiter.limit(refs, modelConfig,
                    DashscopeConstants.HAPPYHORSE_MAX_REFERENCE_IMAGES, "HappyHorse");
            int limit = Math.min(refs.size(), DashscopeConstants.HAPPYHORSE_MAX_REFERENCE_IMAGES);
            if (limit > 0) {
                List<Map<String, Object>> media = new ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(DashscopeConstants.JSON_TYPE, DashscopeConstants.MEDIA_ITEM_TYPE_REFERENCE_IMAGE);
                    item.put(DashscopeConstants.JSON_URL, refs.get(i));
                    media.add(item);
                }
                input.put(DashscopeConstants.JSON_MEDIA, media);
            }
            // 注：空 media 的 fail-fast 已在策略层（建任务前、计费前）处理；直连 API 误用由上游校验 + 早失败退款兜底。

            //    防止 options.parameters 注入非法值绕过校验、或与计费口径(读 options.resolution)不一致导致少计费。
            Map<String, Object> parameters = getOrCreateParameters(body);
            parameters.put(DashscopeConstants.JSON_RESOLUTION, resolveResolution(request));
            parameters.put(DashscopeConstants.JSON_RATIO, resolveRatio(request));
            parameters.put(DashscopeConstants.JSON_DURATION, resolveDuration(request));
            // watermark 默认去水印（创作内容不希望带「Happy Horse」水印），允许 options.parameters.watermark 覆盖。
            if (!parameters.containsKey(DashscopeConstants.JSON_WATERMARK)) {
                parameters.put(DashscopeConstants.JSON_WATERMARK, false);
            }
            // 不主动写 seed（文档可选，遵循系统「随机种子不主动传」约定）。
            return body;
        }

        /** 从 options.referenceImages 读取有序参考图 URL 列表（策略层已按 N 序装配）。 */
        @SuppressWarnings("unchecked")
        private List<String> extractReferenceImages(MediaVideoGenerateRequest request) {
            List<String> urls = new ArrayList<>();
            if (request == null || request.getOptions() == null) {
                return urls;
            }
            Object value = request.getOptions().get("referenceImages");
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String url && StringUtils.isNotBlank(url)) {
                        urls.add(url);
                    }
                }
            }
            return urls;
        }

        /** 分辨率：仅 720P / 1080P；options.resolution=1080P 时取 1080P，否则 720P（与计费口径一致）。 */
        private String resolveResolution(MediaVideoGenerateRequest request) {
            if (request != null && request.getOptions() != null) {
                Object res = request.getOptions().get(DashscopeConstants.JSON_RESOLUTION);
                if (res != null && DashscopeConstants.RESOLUTION_1080P.equalsIgnoreCase(String.valueOf(res).trim())) {
                    return DashscopeConstants.RESOLUTION_1080P;
                }
            }
            return DashscopeConstants.DEFAULT_VIDEO_RESOLUTION;
        }

        /** 宽高比：命中文档枚举则用请求值，否则回落默认 16:9。 */
        private String resolveRatio(MediaVideoGenerateRequest request) {
            String ratio = request == null ? null : request.getAspectRatio();
            if (StringUtils.isNotBlank(ratio) && DashscopeConstants.HAPPYHORSE_RATIOS.contains(ratio.trim())) {
                return ratio.trim();
            }
            return DashscopeConstants.HAPPYHORSE_DEFAULT_RATIO;
        }

        /** 时长：钳制到文档范围 [3,15]，缺省 5。 */
        private int resolveDuration(MediaVideoGenerateRequest request) {
            Integer d = request == null ? null : request.getDurationSeconds();
            if (d == null) {
                return DashscopeConstants.HAPPYHORSE_DEFAULT_DURATION;
            }
            if (d < DashscopeConstants.HAPPYHORSE_DURATION_MIN) {
                return DashscopeConstants.HAPPYHORSE_DURATION_MIN;
            }
            if (d > DashscopeConstants.HAPPYHORSE_DURATION_MAX) {
                return DashscopeConstants.HAPPYHORSE_DURATION_MAX;
            }
            return d;
        }
    }

    private static final class LegacyImage2VideoDialect extends AbstractVideoDialect {

        // 单例实例：匹配首尾帧路径模型（wan2.2-kf2v-flash / wanx2.1-kf2v-plus）。
        private static final LegacyImage2VideoDialect INSTANCE = new LegacyImage2VideoDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 所有含 kf2v 的首尾帧模型统一走 first_frame_url/last_frame_url 协议，
            // 必须在 WanVideoDialect（wan 前缀）之前命中，否则 wan2.2-kf2v-flash 会被误发 img_url。
            return normalizedModelName.contains(DashscopeConstants.MODEL_KF2V_KEYWORD);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);
            if (!input.containsKey(DashscopeConstants.JSON_FIRST_FRAME_URL) && StringUtils.isNotBlank(request.getImageUrl())) {
                input.put(DashscopeConstants.JSON_FIRST_FRAME_URL, request.getImageUrl());
            }
            // 尾帧：业务层放在 options.endImageUrl / end_image_url，按文档写入 input.last_frame_url
            if (!input.containsKey(DashscopeConstants.JSON_LAST_FRAME_URL)) {
                String endImageUrl = readEndImageUrl(request);
                if (StringUtils.isNotBlank(endImageUrl)) {
                    input.put(DashscopeConstants.JSON_LAST_FRAME_URL, endImageUrl);
                }
            }
            Map<String, Object> parameters = getOrCreateParameters(body);
            // 官方约束：kf2v 时长固定 5 秒且不支持修改，统一强制下发 5，防止异常时长被上游拒绝或与计费口径漂移
            parameters.put(DashscopeConstants.JSON_DURATION, DashscopeConstants.KF2V_FIXED_DURATION_SECONDS);
            // 分辨率档位透传（480P/720P/1080P），与计费口径同源，未传时交由上游默认值
            if (!parameters.containsKey(DashscopeConstants.JSON_RESOLUTION)) {
                Object resolution = request.getOptions() == null ? null : request.getOptions().get("resolution");
                if (resolution != null && StringUtils.isNotBlank(String.valueOf(resolution))) {
                    parameters.put(DashscopeConstants.JSON_RESOLUTION, String.valueOf(resolution).trim().toUpperCase());
                }
            }
            return body;
        }

        /** 读取尾帧 URL：兼容 endImageUrl / end_image_url 两种业务层键名。 */
        private String readEndImageUrl(MediaVideoGenerateRequest request) {
            Map<String, Object> options = request.getOptions();
            if (options == null) {
                return null;
            }
            Object camel = options.get("endImageUrl");
            if (camel != null && StringUtils.isNotBlank(String.valueOf(camel))) {
                return String.valueOf(camel).trim();
            }
            Object snake = options.get("end_image_url");
            if (snake != null && StringUtils.isNotBlank(String.valueOf(snake))) {
                return String.valueOf(snake).trim();
            }
            return null;
        }
    }

    private static final class PixverseVideoDialect extends AbstractVideoDialect {

        // 单例实例：匹配 pixverse 模型族。
        private static final PixverseVideoDialect INSTANCE = new PixverseVideoDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 爱诗模型统一以 pixverse/ 前缀命名。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_PIXVERSE_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);
            if (!input.containsKey(DashscopeConstants.JSON_MEDIA) && StringUtils.isNotBlank(request.getImageUrl())) {
                List<Map<String, Object>> media = new ArrayList<>();
                Map<String, Object> mediaItem = new LinkedHashMap<>();
                mediaItem.put(DashscopeConstants.JSON_TYPE, DashscopeConstants.MEDIA_ITEM_TYPE_IMAGE_URL);
                mediaItem.put(DashscopeConstants.JSON_URL, request.getImageUrl());
                media.add(mediaItem);
                input.put(DashscopeConstants.JSON_MEDIA, media);
            }
            input.remove(DashscopeConstants.JSON_IMG_URL);
            Map<String, Object> parameters = getOrCreateParameters(body);
            if (request.getDurationSeconds() != null && !parameters.containsKey(DashscopeConstants.JSON_DURATION)) {
                parameters.put(DashscopeConstants.JSON_DURATION, request.getDurationSeconds());
            }
            return body;
        }
    }

    private static final class KlingVideoDialect extends AbstractVideoDialect {

        // 单例实例：匹配可灵模型族。
        private static final KlingVideoDialect INSTANCE = new KlingVideoDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 可灵模型统一以 kling/ 前缀命名。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_KLING_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);
            if (!input.containsKey(DashscopeConstants.JSON_MEDIA) && StringUtils.isNotBlank(request.getImageUrl())) {
                List<Map<String, Object>> media = new ArrayList<>();
                Map<String, Object> mediaItem = new LinkedHashMap<>();
                mediaItem.put(DashscopeConstants.JSON_TYPE, DashscopeConstants.MEDIA_ITEM_TYPE_IMAGE_URL);
                mediaItem.put(DashscopeConstants.JSON_URL, request.getImageUrl());
                media.add(mediaItem);
                input.put(DashscopeConstants.JSON_MEDIA, media);
            }
            input.remove(DashscopeConstants.JSON_IMG_URL);
            Map<String, Object> parameters = getOrCreateParameters(body);
            if (request.getDurationSeconds() != null && !parameters.containsKey(DashscopeConstants.JSON_DURATION)) {
                parameters.put(DashscopeConstants.JSON_DURATION, request.getDurationSeconds());
            }
            return body;
        }
    }

    private static final class WanVideoDialect extends AbstractVideoDialect {

        // 单例实例：匹配万相模型族。
        private static final WanVideoDialect INSTANCE = new WanVideoDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 万相视频模型常见前缀 wan*/wanx*。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_VIDEO_WAN_PREFIX)
                || normalizedModelName.startsWith(DashscopeConstants.MODEL_VIDEO_WANX_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);
            if (!input.containsKey(DashscopeConstants.JSON_IMG_URL) && StringUtils.isNotBlank(request.getImageUrl())) {
                input.put(DashscopeConstants.JSON_IMG_URL, request.getImageUrl());
            }
            Map<String, Object> parameters = getOrCreateParameters(body);
            if (request.getDurationSeconds() != null && !parameters.containsKey(DashscopeConstants.JSON_DURATION)) {
                parameters.put(DashscopeConstants.JSON_DURATION, request.getDurationSeconds());
            }
            if (!parameters.containsKey(DashscopeConstants.JSON_RESOLUTION)) {
                parameters.put(DashscopeConstants.JSON_RESOLUTION, DashscopeConstants.DEFAULT_VIDEO_RESOLUTION);
            }
            return body;
        }
    }

    private static final class GeneralVideoSynthesisDialect extends AbstractVideoDialect {

        // 单例实例：兜底方言，避免未知模型直接不可调用。
        private static final GeneralVideoSynthesisDialect INSTANCE = new GeneralVideoSynthesisDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 兜底匹配全部模型。
            return true;
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaVideoGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
            Map<String, Object> body = buildBaseBody(modelName, request);
            Map<String, Object> input = getOrCreateInput(body);
            if (!input.containsKey(DashscopeConstants.JSON_IMG_URL) && StringUtils.isNotBlank(request.getImageUrl())) {
                input.put(DashscopeConstants.JSON_IMG_URL, request.getImageUrl());
            }
            Map<String, Object> parameters = getOrCreateParameters(body);
            if (request.getDurationSeconds() != null && !parameters.containsKey(DashscopeConstants.JSON_DURATION)) {
                parameters.put(DashscopeConstants.JSON_DURATION, request.getDurationSeconds());
            }
            return body;
        }
    }
}
