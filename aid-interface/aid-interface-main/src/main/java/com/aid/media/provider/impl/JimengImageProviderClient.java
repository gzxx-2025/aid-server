package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.JimengConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.volcengine.VolcengineVisualSigner;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 即梦图片 Provider：统一走 visual.volcengineapi.com，按 req_key 区分四个模型。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class JimengImageProviderClient implements ImageProviderClient {

    // --- 即梦 3.1/4.0/4.6 推荐宽高映射表（来自官方文档，按模型版本区分） ---

    /** 2K 档位下各比例对应的推荐宽高（面积 ≈ 2048*2048，3.1/4.0/4.6 官方推荐值相同） */
    private static final Map<String, int[]> JIMENG_RATIO_TO_2K_SIZE;
    /** 1K 档位 —— 4.0/4.6 文档推荐宽高（面积 ≈ 1024*1024，官方仅 1:1） */
    private static final Map<String, int[]> JIMENG_RATIO_TO_1K_SIZE;
    /** 1K 档位 —— 3.1 文档「标清1K」推荐宽高（1328 基准，与 4.0 系不同） */
    private static final Map<String, int[]> JIMENG_RATIO_TO_1K_SIZE_V31;
    /** 4K 档位 —— 4.0 文档推荐宽高（4:3=4694×3520, 21:9=6198×2656） */
    private static final Map<String, int[]> JIMENG_RATIO_TO_4K_SIZE_V40;
    /** 4K 档位 —— 4.6 文档推荐宽高（4:3=4693×3520, 21:9=6197×2656，与 4.0 差 1px） */
    private static final Map<String, int[]> JIMENG_RATIO_TO_4K_SIZE_V46;

    /** 3.1 默认宽高（官方：系统默认生成 1328*1328） */
    private static final int V31_DEFAULT_DIMENSION = 1328;
    /** 3.1 2K 档 1:1 宽高（官方高清2K推荐 2048*2048） */
    private static final int V31_2K_DIMENSION = 2048;

    static {
        // --- 2K（3.1「高清2K」与 4.0/4.6 推荐值一致） ---
        Map<String, int[]> m2k = new HashMap<>();
        m2k.put("1:1", new int[]{2048, 2048});
        m2k.put("4:3", new int[]{2304, 1728});
        m2k.put("3:4", new int[]{1728, 2304});
        m2k.put("3:2", new int[]{2496, 1664});
        m2k.put("2:3", new int[]{1664, 2496});
        m2k.put("16:9", new int[]{2560, 1440});
        m2k.put("9:16", new int[]{1440, 2560});
        m2k.put("21:9", new int[]{3024, 1296});
        m2k.put("9:21", new int[]{1296, 3024});
        JIMENG_RATIO_TO_2K_SIZE = Collections.unmodifiableMap(m2k);

        // --- 1K（4.0/4.6 官方仅 1:1 有推荐值） ---
        Map<String, int[]> m1k = new HashMap<>();
        m1k.put("1:1", new int[]{1024, 1024});
        JIMENG_RATIO_TO_1K_SIZE = Collections.unmodifiableMap(m1k);

        // --- 1K（3.1「标清1K」官方推荐：1328 基准） ---
        Map<String, int[]> m1kV31 = new HashMap<>();
        m1kV31.put("1:1", new int[]{1328, 1328});
        m1kV31.put("4:3", new int[]{1472, 1104});
        m1kV31.put("3:4", new int[]{1104, 1472});
        m1kV31.put("3:2", new int[]{1584, 1056});
        m1kV31.put("2:3", new int[]{1056, 1584});
        m1kV31.put("16:9", new int[]{1664, 936});
        m1kV31.put("9:16", new int[]{936, 1664});
        m1kV31.put("21:9", new int[]{2016, 864});
        m1kV31.put("9:21", new int[]{864, 2016});
        JIMENG_RATIO_TO_1K_SIZE_V31 = Collections.unmodifiableMap(m1kV31);

        // --- 4K（4.0 文档） ---
        Map<String, int[]> m4kV40 = new HashMap<>();
        m4kV40.put("1:1", new int[]{4096, 4096});
        m4kV40.put("4:3", new int[]{4694, 3520});
        m4kV40.put("3:4", new int[]{3520, 4694});
        m4kV40.put("3:2", new int[]{4992, 3328});
        m4kV40.put("2:3", new int[]{3328, 4992});
        m4kV40.put("16:9", new int[]{5404, 3040});
        m4kV40.put("9:16", new int[]{3040, 5404});
        m4kV40.put("21:9", new int[]{6198, 2656});
        m4kV40.put("9:21", new int[]{2656, 6198});
        JIMENG_RATIO_TO_4K_SIZE_V40 = Collections.unmodifiableMap(m4kV40);

        // --- 4K（4.6 文档，4:3 / 21:9 与 4.0 差 1px） ---
        Map<String, int[]> m4kV46 = new HashMap<>();
        m4kV46.put("1:1", new int[]{4096, 4096});
        m4kV46.put("4:3", new int[]{4693, 3520});
        m4kV46.put("3:4", new int[]{3520, 4693});
        m4kV46.put("3:2", new int[]{4992, 3328});
        m4kV46.put("2:3", new int[]{3328, 4992});
        m4kV46.put("16:9", new int[]{5404, 3040});
        m4kV46.put("9:16", new int[]{3040, 5404});
        m4kV46.put("21:9", new int[]{6197, 2656});
        m4kV46.put("9:21", new int[]{2656, 6197});
        JIMENG_RATIO_TO_4K_SIZE_V46 = Collections.unmodifiableMap(m4kV46);
    }

    @Override
    public String protocol() {
        return JimengConstants.PROTOCOL_IMAGE;
    }

    /** 提交瞬时失败最大尝试次数（含首次）：覆盖即梦网关 504 抖动 / 50430 并发超限的有限退避重试 */
    private static final int JIMENG_SUBMIT_MAX_ATTEMPTS = 3;
    /** 提交重试退避基数（毫秒）：第 n 次重试 sleep = base × n（2s、4s…） */
    private static final long JIMENG_SUBMIT_RETRY_BACKOFF_MS = 2000L;
    /** 即梦并发超限错误码（message=Request Has Reached API Concurrent Limit），属瞬时可重试 */
    private static final int JIMENG_CODE_CONCURRENT_LIMIT = 50430;

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 即梦图片：按 provider_code 精确归属（独立签名链路）
        return providerCode != null
                && JimengConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);
        String modelCode = resolveEffectiveModel(modelConfig, request);
        String reqKey = resolveReqKey(modelCode);

        List<String> imageInputs = resolveImageInputs(request);
        // 统一上限：读 capability_json.maxReferenceImages，缺省回退即梦各版本官方默认；超限截断 + warn（不再抛错）
        int jimengMaxRef = JimengConstants.MODEL_CODE_ULTRA.equalsIgnoreCase(modelCode)
                ? JimengConstants.MAX_REF_IMAGES_ULTRA
                : (JimengConstants.MODEL_CODE_V46.equalsIgnoreCase(modelCode)
                        ? JimengConstants.MAX_REF_IMAGES_V46
                        : JimengConstants.MAX_REF_IMAGES_V40);
        imageInputs = ReferenceImageLimiter.limit(imageInputs, modelConfig, jimengMaxRef, "即梦-" + modelCode);
        // Base64 传图开关：官方 binary_data_base64 与 image_urls 二选一，启用时下载转裸 base64 下发
        boolean useBase64 = com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig);
        if (useBase64 && CollectionUtil.isNotEmpty(imageInputs)) {
            List<String> rawBase64s = com.aid.media.provider.ReferenceImageBase64Support.toRawBase64s(imageInputs);
            if (rawBase64s.size() == imageInputs.size()) {
                imageInputs = rawBase64s;
                log.info("即梦参考图按 binary_data_base64 下发, modelCode={}, count={}", modelCode, rawBase64s.size());
            } else {
                // 任一图转换失败则整体退回 URL 形态，避免 base64/URL 混填被官方拒收
                useBase64 = false;
                log.warn("即梦参考图 base64 转换不完整({}→{}), 退回 image_urls, modelCode={}",
                        imageInputs.size(), rawBase64s.size(), modelCode);
            }
        } else {
            useBase64 = false;
        }
        Map<String, Object> body = buildSubmitBody(modelCode, reqKey, request, imageInputs, useBase64);

        boolean isImg2Img = CollectionUtil.isNotEmpty(imageInputs);
        log.info("即梦图片提交请求: modelCode={}, reqKey={}, promptLen={}, refImages={}, mode={}, width={}, height={}, size={}, forceSingle={}",
                modelCode, reqKey,
                body.containsKey(JimengConstants.JSON_PROMPT)
                        ? String.valueOf(body.get(JimengConstants.JSON_PROMPT)).length() : 0,
                imageInputs.size(),
                isImg2Img ? "图生图" : "文生图",
                body.get(JimengConstants.JSON_WIDTH),
                body.get(JimengConstants.JSON_HEIGHT),
                body.get(JimengConstants.JSON_SIZE),
                body.get(JimengConstants.JSON_FORCE_SINGLE));

        //    对即梦网关 504 超时 / 50430 并发超限等「瞬时错误」做有限次退避重试，避免上游一次抖动即判失败。
        //    重试发生在同一次媒体任务/同一次预冻结之内（generateImage 先冻结再调 submit），不会重复扣费。
        String raw = null;
        JsonNode root = null;
        int code = JimengConstants.RESP_CODE_SUCCESS;
        for (int attempt = 1; ; attempt++) {
            try {
                raw = doSignedPost(modelConfig, JimengConstants.ACTION_SUBMIT, body);
            } catch (IllegalArgumentException e) {
                // 参数类校验异常统一上抛（比如 ultra 没传图），文案保持 ≤6 个字由上层包装。
                log.error("即梦图片提交参数异常, modelCode={}, msg={}", modelCode, e.getMessage());
                throw e;
            } catch (Exception e) {
                // 网络/签名异常（连接超时、读超时、连接重置等）视为瞬时错误：重试上限内退避重试
                if (attempt < JIMENG_SUBMIT_MAX_ATTEMPTS) {
                    log.warn("即梦图片提交网络异常将重试, modelCode={}, attempt={}/{}, msg={}",
                            modelCode, attempt, JIMENG_SUBMIT_MAX_ATTEMPTS, e.getMessage());
                    sleepBackoff(attempt);
                    continue;
                }
                log.error("即梦图片提交网络/签名异常已达重试上限, modelCode={}, attempt={}", modelCode, attempt, e);
                return ProviderSubmitResult.builder()
                        .rawResponse(e.getMessage())
                        .build();
            }

            // 解析外层 code：10000=成功；HTML 网关错误解析不出 code 则为 0
            root = ProviderResponseHelper.readTree(raw);
            code = readCode(root);
            if (code == JimengConstants.RESP_CODE_SUCCESS) {
                break;
            }
            // 瞬时错误（网关 5xx 超时 / 50430 并发超限）：重试上限内退避重试
            if (isTransientSubmitFailure(code, raw) && attempt < JIMENG_SUBMIT_MAX_ATTEMPTS) {
                log.warn("即梦图片提交瞬时失败将重试, modelCode={}, code={}, attempt={}/{}, snippet={}",
                        modelCode, code, attempt, JIMENG_SUBMIT_MAX_ATTEMPTS,
                        StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
                sleepBackoff(attempt);
                continue;
            }
            // 非瞬时错误，或已达重试上限：按失败返回 rawResponse 供排障
            String errMsg = ProviderResponseHelper.readText(root, JimengConstants.RESP_MESSAGE);
            String requestId = ProviderResponseHelper.readText(root, JimengConstants.RESP_REQUEST_ID);
            log.error("即梦图片提交失败, modelCode={}, code={}, message={}, request_id={}, attempt={}, promptLen={}, refImages={}",
                    modelCode, code, errMsg, requestId, attempt,
                    body.containsKey(JimengConstants.JSON_PROMPT)
                            ? String.valueOf(body.get(JimengConstants.JSON_PROMPT)).length() : 0,
                    imageInputs.size());
            return ProviderSubmitResult.builder()
                    .rawResponse(raw)
                    .build();
        }

        String taskId = ProviderResponseHelper.readText(root,
                JimengConstants.RESP_DATA + "." + JimengConstants.RESP_TASK_ID,
                JimengConstants.RESP_TASK_ID);
        if (StrUtil.isBlank(taskId)) {
            log.error("即梦图片提交成功但未返回 task_id, modelCode={}, raw={}", modelCode,
                    StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
            return ProviderSubmitResult.builder()
                    .rawResponse(raw)
                    .build();
        }

        log.info("即梦图片提交成功, modelCode={}, reqKey={}, taskId={}", modelCode, reqKey, taskId);
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .rawResponse(raw)
                .build();
    }

    /**
     * 判断提交失败是否为「瞬时错误」（可退避重试）。
     *
     *   - 50430 并发超限（Request Has Reached API Concurrent Limit）；
     *   - 网关 502/503/504 超时类响应（即梦网关返回 HTML，解析不出 code）。
     *
     * 仅按错误码 + 网关特征短语判定，避免普通业务错误被误重试。
     */
    private boolean isTransientSubmitFailure(int code, String raw) {
        if (code == JIMENG_CODE_CONCURRENT_LIMIT) {
            return true;
        }
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("gateway time-out")
                || lower.contains("gateway timeout")
                || lower.contains("bad gateway")
                || lower.contains("service unavailable")
                || lower.contains("service temporarily unavailable");
    }

    /** 退避休眠：第 n 次重试 sleep = base × n；被中断时复位中断标记并立即返回。 */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(JIMENG_SUBMIT_RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        //    避免展示码（如 jimeng-xxx_a）无法命中 reqKey 固定映射导致轮询永久失败。
        String modelCode = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        // 配置类异常（未配模型/modelCode 不在固定映射）属永久失败，不能伪装成 PROCESSING 造成无限轮询。
        String reqKey;
        try {
            reqKey = resolveReqKey(modelCode);
        } catch (IllegalArgumentException e) {
            log.error("即梦图片查询配置错误, modelCode={}, taskId={}, msg={}", modelCode, providerTaskId, e.getMessage());
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(JimengConstants.JSON_REQ_KEY, reqKey);
        body.put(JimengConstants.JSON_TASK_ID, providerTaskId);
        body.put(JimengConstants.JSON_REQ_JSON, JimengConstants.DEFAULT_RETURN_URL_JSON);

        //    异常严格分类：AK/SK/modelConfig 缺失属永久失败；签名/加密算法缺失属环境错误，也是永久失败；
        //    仅网络/连接类瞬时异常才返回 PROCESSING 让补偿调度重试。
        String raw;
        try {
            raw = doSignedPost(modelConfig, JimengConstants.ACTION_QUERY, body);
        } catch (IllegalArgumentException e) {
            log.error("即梦图片查询参数/配置错误, modelCode={}, taskId={}, msg={}",
                    modelCode, providerTaskId, e.getMessage());
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(StrUtil.blankToDefault(e.getMessage(), "即梦查询失败"))
                    .build();
        } catch (IllegalStateException e) {
            log.error("即梦图片签名环境错误, modelCode={}, taskId={}, msg={}",
                    modelCode, providerTaskId, e.getMessage());
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_FAILED)
                    .errorMessage(StrUtil.blankToDefault(e.getMessage(), "即梦查询失败"))
                    .build();
        } catch (Exception e) {
            // 仅网络/连接类瞬时异常：保留 PROCESSING，由补偿调度继续轮询。
            log.error("即梦图片查询瞬时异常, modelCode={}, taskId={}", modelCode, providerTaskId, e);
            return ProviderTaskResult.builder()
                    .status(JimengConstants.TASK_STATUS_PROCESSING)
                    .errorMessage(e.getMessage())
                    .build();
        }

        JsonNode root = ProviderResponseHelper.readTree(raw);
        int code = readCode(root);
        if (code != JimengConstants.RESP_CODE_SUCCESS) {
            // 上游明确标记失败：例如 50413/50412 审核不过
            String message = ProviderResponseHelper.readText(root, JimengConstants.RESP_MESSAGE);
            String requestId = ProviderResponseHelper.readText(root, JimengConstants.RESP_REQUEST_ID);
            log.error("即梦图片查询失败, modelCode={}, taskId={}, code={}, message={}, request_id={}",
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
        //    上游偶发 status=done 但 image_urls 为空（例如 return_url 未生效、CDN 过期），
        //    若仍返回 SUCCEEDED 会让上层写入 originUrl=null 产生脏任务。
        String resultUrl = null;
        List<String> resultUrls = Collections.emptyList();
        Integer resultCount = null;
        if (JimengConstants.TASK_STATUS_SUCCEEDED.equals(normalized)) {
            // 读全部结果图 URL 列表供图片计费按实际张数结算；首项作为 resultUrl 兼容旧链路
            resultUrls = readImageUrls(root);
            if (!resultUrls.isEmpty()) {
                resultUrl = resultUrls.get(0);
            }
            if (StrUtil.isBlank(resultUrl)) {
                resultUrl = ProviderResponseHelper.findFirstUrl(root);
                if (StrUtil.isNotBlank(resultUrl)) {
                    // fallback 兜底只能识别首图，按 1 张计
                    resultUrls = Collections.singletonList(resultUrl);
                }
            }
            if (StrUtil.isBlank(resultUrl)) {
                log.error("即梦图片 status=done 但未解析到结果图 URL, modelCode={}, taskId={}, raw={}",
                        modelCode, providerTaskId,
                        StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
                return ProviderTaskResult.builder()
                        .status(JimengConstants.TASK_STATUS_FAILED)
                        .errorMessage("结果缺失")
                        .rawResponse(raw)
                        .build();
            }
            resultCount = resultUrls.size();
        }

        return ProviderTaskResult.builder()
                .status(normalized)
                .resultUrl(resultUrl)
                .resultUrls(resultUrls)
                .resultCount(resultCount)
                .rawResponse(raw)
                .build();
    }

    // ------------------------------------------------------------------
    // 请求体构建
    // ------------------------------------------------------------------

    /**
     * 按模型差异组装提交 Body。
     * 参考图校验按以下业务约束执行（与类注释声明一致），超限直接抛 {@link IllegalArgumentException}，
     * 上层会转为 6 字以内的用户可见异常。
     */
    private Map<String, Object> buildSubmitBody(String modelCode, String reqKey,
                                                MediaImageGenerateRequest request, List<String> imageInputs,
                                                boolean useBase64) {
        // 官方图片入参字段二选一：启用 Base64 传图走 binary_data_base64，默认走 image_urls
        String imageField = useBase64
                ? JimengConstants.JSON_BINARY_DATA_BASE64 : JimengConstants.JSON_IMAGE_URLS;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(JimengConstants.JSON_REQ_KEY, reqKey);

        // prompt 必填校验（ultra 没有 prompt 字段也允许为空）
        String prompt = request == null ? null : request.getPrompt();
        if (JimengConstants.MODEL_CODE_ULTRA.equalsIgnoreCase(modelCode)) {
            // ultra：严格单图输入，不带 prompt
            if (CollectionUtil.isEmpty(imageInputs)
                    || imageInputs.size() < JimengConstants.MIN_REF_IMAGES_ULTRA
                    || imageInputs.size() > JimengConstants.MAX_REF_IMAGES_ULTRA) {
                log.error("jimeng-image-ultra 必须且只能传 1 张参考图, 实际={}",
                        imageInputs == null ? 0 : imageInputs.size());
                throw new IllegalArgumentException("请传单图");
            }
            body.put(imageField, imageInputs);
            // resolution/scale 透传自 options
            applyOptionsUltra(body, request);
            return body;
        }

        if (JimengConstants.MODEL_CODE_V31.equalsIgnoreCase(modelCode)) {
            // 3.1：纯文生图（官方无图片输入参数）
            if (StrUtil.isBlank(prompt)) {
                throw new IllegalArgumentException("请填提示词");
            }
            // 官方限制：最长不超过 800 字符，超长截断
            if (prompt.length() > JimengConstants.PROMPT_MAX_LENGTH) {
                log.warn("即梦3.1 prompt 超长截断, 原始长度={}, 截断至={}",
                        prompt.length(), JimengConstants.PROMPT_MAX_LENGTH);
                prompt = prompt.substring(0, JimengConstants.PROMPT_MAX_LENGTH);
            }
            body.put(JimengConstants.JSON_PROMPT, prompt);
            applyOptionsV31(body, request);
            return body;
        }

        // 4.0 / 4.6：文生图或多参考图
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalArgumentException("请填提示词");
        }
        // prompt 长度收口：4.0 / 4.6 文档均限制 800 字符，超长截断
        if (prompt.length() > JimengConstants.PROMPT_MAX_LENGTH) {
            log.warn("即梦图片 prompt 超长截断, modelCode={}, 原始长度={}, 截断至={}",
                    modelCode, prompt.length(), JimengConstants.PROMPT_MAX_LENGTH);
            prompt = prompt.substring(0, JimengConstants.PROMPT_MAX_LENGTH);
        }
        int maxRef = JimengConstants.MODEL_CODE_V46.equalsIgnoreCase(modelCode)
                ? JimengConstants.MAX_REF_IMAGES_V46
                : JimengConstants.MAX_REF_IMAGES_V40;
        // 参考图数量已在 submit 入口经 ReferenceImageLimiter 统一截断到 maxRef，此处不再抛错
        if (CollectionUtil.isNotEmpty(imageInputs) && imageInputs.size() > maxRef) {
            log.warn("{} 参考图仍超限(理论不应发生), 截断至 max={}, 实际={}", modelCode, maxRef, imageInputs.size());
            imageInputs = new java.util.ArrayList<>(imageInputs.subList(0, maxRef));
        }
        body.put(JimengConstants.JSON_PROMPT, prompt);
        if (CollectionUtil.isNotEmpty(imageInputs)) {
            body.put(imageField, imageInputs);
        }
        // 按模型版本分流处理可选参数（scale 类型/范围/4K 表不同）
        if (JimengConstants.MODEL_CODE_V46.equalsIgnoreCase(modelCode)) {
            applyOptionsV46(body, request, modelCode);
        } else {
            applyOptionsV40(body, request, modelCode);
        }
        return body;
    }

    /**
     * ultra 专属透传字段：resolution（官方可选值小写 4k/8k）、scale（int [0,100]）。
     */
    private void applyOptionsUltra(Map<String, Object> body, MediaImageGenerateRequest request) {
        Map<String, Object> options = request == null ? null : request.getOptions();
        if (options == null) {
            return;
        }
        // resolution 官方仅接受小写 "4k"/"8k"：统一小写下发，非法值丢弃走上游默认 4k
        Object resolutionVal = options.get(JimengConstants.OPTIONS_RESOLUTION);
        if (resolutionVal != null && StrUtil.isNotBlank(String.valueOf(resolutionVal))) {
            String normalized = String.valueOf(resolutionVal).trim().toLowerCase(Locale.ROOT);
            if (JimengConstants.ULTRA_RESOLUTION_4K.equals(normalized)
                    || JimengConstants.ULTRA_RESOLUTION_8K.equals(normalized)) {
                body.put(JimengConstants.JSON_RESOLUTION, normalized);
            } else {
                log.warn("即梦超清 resolution 非法值丢弃, 输入={}, 官方仅支持4k/8k, 走上游默认4k", resolutionVal);
            }
        }
        // scale 官方范围 int [0,100]，clamp 后下发
        Object scaleVal = options.get(JimengConstants.OPTIONS_SCALE);
        if (scaleVal instanceof Number) {
            int clamped = Math.max(JimengConstants.SCALE_ULTRA_MIN,
                    Math.min(((Number) scaleVal).intValue(), JimengConstants.SCALE_ULTRA_MAX));
            body.put(JimengConstants.JSON_SCALE, clamped);
        }
    }

    /**
     * 3.1 透传字段：use_pre_llm、seed、width/height（官方无 size/scale/force_single 参数）。
     * use_pre_llm 未显式传入时固定 false：平台链路 prompt 普遍较长，官方建议长 prompt 关闭扩写，
     * 且与「模型不做二次改写」的统一口径一致；业务显式传入时按传入值。
     */
    private void applyOptionsV31(Map<String, Object> body, MediaImageGenerateRequest request) {
        applyWidthHeight(body, request, JimengConstants.MODEL_CODE_V31);
        Map<String, Object> options = request == null ? null : request.getOptions();
        if (options != null) {
            putIfPresent(body, options, JimengConstants.JSON_USE_PRE_LLM, JimengConstants.OPTIONS_USE_PRE_LLM);
            putIfPresent(body, options, JimengConstants.JSON_SEED, JimengConstants.OPTIONS_SEED);
        }
        if (!body.containsKey(JimengConstants.JSON_USE_PRE_LLM)) {
            body.put(JimengConstants.JSON_USE_PRE_LLM, false);
        }
    }

    /**
     * 4.0 可选参数：scale 为 float [0, 1]，默认 0.5。
     */
    private void applyOptionsV40(Map<String, Object> body, MediaImageGenerateRequest request, String modelCode) {
        applyWidthHeight(body, request, modelCode);
        Map<String, Object> options = request == null ? null : request.getOptions();
        if (options == null) {
            // 无 options 也要落入默认 scale
            body.put(JimengConstants.JSON_SCALE, JimengConstants.SCALE_V40_DEFAULT);
            return;
        }
        // scale：4.0 文档要求 float [0, 1]，默认 0.5
        Object scaleVal = options.get(JimengConstants.OPTIONS_SCALE);
        double sv;
        if (scaleVal instanceof Number) {
            sv = ((Number) scaleVal).doubleValue();
            if (sv > JimengConstants.SCALE_V40_MAX) {
                // 可能是按 4.6 的 [1,100] 传入，转为 [0,1]
                sv = sv / JimengConstants.SCALE_V46_MAX;
                log.info("即梦4.0 scale自动换算: 原值={}, 换算后={}", scaleVal, sv);
            }
        } else {
            // 未传 scale，落入默认值
            sv = JimengConstants.SCALE_V40_DEFAULT;
        }
        // 最终范围校验：clamp 到 [0, 1]
        sv = Math.max(JimengConstants.SCALE_V40_MIN, Math.min(sv, JimengConstants.SCALE_V40_MAX));
        body.put(JimengConstants.JSON_SCALE, sv);
        putIfPresent(body, options, JimengConstants.JSON_FORCE_SINGLE, JimengConstants.OPTIONS_FORCE_SINGLE);
        putIfPresent(body, options, JimengConstants.JSON_MIN_RATIO, JimengConstants.OPTIONS_MIN_RATIO);
        putIfPresent(body, options, JimengConstants.JSON_MAX_RATIO, JimengConstants.OPTIONS_MAX_RATIO);
        applyForceSingleGuard(body, request);
    }

    /**
     * 4.6 可选参数：scale 为 int [1, 100]，默认 50。
     */
    private void applyOptionsV46(Map<String, Object> body, MediaImageGenerateRequest request, String modelCode) {
        applyWidthHeight(body, request, modelCode);
        Map<String, Object> options = request == null ? null : request.getOptions();
        if (options == null) {
            // 无 options 也要落入默认 scale
            body.put(JimengConstants.JSON_SCALE, JimengConstants.SCALE_V46_DEFAULT);
            return;
        }
        // scale：4.6 文档要求 int [1, 100]，默认 50
        Object scaleVal = options.get(JimengConstants.OPTIONS_SCALE);
        int ivs;
        if (scaleVal instanceof Number) {
            double sv = ((Number) scaleVal).doubleValue();
            if (sv > 0 && sv <= JimengConstants.SCALE_V40_MAX) {
                // 可能是按 4.0 的 [0,1] 传入，转为 [1,100]
                ivs = (int) Math.round(sv * JimengConstants.SCALE_V46_MAX);
                log.info("即梦4.6 scale自动换算: 原值={}, 换算后={}", scaleVal, ivs);
            } else {
                ivs = ((Number) scaleVal).intValue();
            }
        } else {
            // 未传 scale，落入默认值
            ivs = JimengConstants.SCALE_V46_DEFAULT;
        }
        // 最终范围校验：clamp 到 [1, 100]
        ivs = Math.max(JimengConstants.SCALE_V46_MIN, Math.min(ivs, JimengConstants.SCALE_V46_MAX));
        body.put(JimengConstants.JSON_SCALE, ivs);
        putIfPresent(body, options, JimengConstants.JSON_FORCE_SINGLE, JimengConstants.OPTIONS_FORCE_SINGLE);
        putIfPresent(body, options, JimengConstants.JSON_MIN_RATIO, JimengConstants.OPTIONS_MIN_RATIO);
        putIfPresent(body, options, JimengConstants.JSON_MAX_RATIO, JimengConstants.OPTIONS_MAX_RATIO);
        applyForceSingleGuard(body, request);
    }

    /**
     * 组图张数与计费口径对齐：4.0/4.6 按生成张数计费且模型可能自行多出图。
     * 预扣张数（expectedImageCount，空按 1）为 1 时若不强制单图，模型多出图会超出预扣张数
     * 且结算不允许补收，造成漏计费；故未显式传 force_single 且预期 1 张时强制 force_single=true。
     */
    private void applyForceSingleGuard(Map<String, Object> body, MediaImageGenerateRequest request) {
        if (body.containsKey(JimengConstants.JSON_FORCE_SINGLE)) {
            return;
        }
        Integer expected = request == null ? null : request.getExpectedImageCount();
        if (expected == null || expected <= 1) {
            body.put(JimengConstants.JSON_FORCE_SINGLE, true);
        }
    }

    /**
     * 从 request.size 或 options.width/height 解析出 width/height 并写入 body。
     */
    private void applyWidthHeight(Map<String, Object> body, MediaImageGenerateRequest request, String modelCode) {
        if (request == null) {
            return;
        }
        Map<String, Object> options = request.getOptions();
        Object w = options == null ? null : options.get(JimengConstants.OPTIONS_WIDTH);
        Object h = options == null ? null : options.get(JimengConstants.OPTIONS_HEIGHT);
        if (w != null && h != null) {
            body.put(JimengConstants.JSON_WIDTH, w);
            body.put(JimengConstants.JSON_HEIGHT, h);
            if (options != null) {
                options.remove("aspect_ratio");
            }
            return;
        }
        String size = request.getSize();
        if (StrUtil.isNotBlank(size) && size.contains("*")) {
            String[] parts = size.split(JimengConstants.SIZE_DIMENSION_SPLIT_REGEX);
            if (parts.length == 2) {
                try {
                    int width = Integer.parseInt(parts[0].trim());
                    int height = Integer.parseInt(parts[1].trim());
                    body.put(JimengConstants.JSON_WIDTH, width);
                    body.put(JimengConstants.JSON_HEIGHT, height);
                } catch (NumberFormatException ignore) {
                    // 非整数 size 忽略，交给上游按默认面积处理
                }
            }
            if (options != null) {
                options.remove("aspect_ratio");
            }
            return;
        }
        boolean isV31 = JimengConstants.MODEL_CODE_V31.equalsIgnoreCase(modelCode);
        if (options != null) {
            Object ratioObj = options.remove("aspect_ratio");
            if (ratioObj != null) {
                String ratio = String.valueOf(ratioObj).trim();
                Map<String, int[]> table = resolveSizeTable(size, modelCode);
                int[] dims = table.get(ratio);
                if (dims != null) {
                    body.put(JimengConstants.JSON_WIDTH, dims[0]);
                    body.put(JimengConstants.JSON_HEIGHT, dims[1]);
                    log.info("即梦图片翻译 modelCode={}, aspect_ratio={}, size={} -> width={}, height={}",
                            modelCode, ratio, StrUtil.isBlank(size) ? "(默认2K)" : size, dims[0], dims[1]);
                    return;
                } else {
                    log.warn("即梦图片未知 aspect_ratio={}, 回退默认尺寸模式, modelCode={}, size={}",
                            ratio, modelCode, size);
                    // 回退到下面的档位兜底逻辑
                }
            }
        }
        if (isV31) {
            // 3.1 官方 Body 无 size(面积) 参数，只认 width/height：按档位落官方默认推荐值
            applyDefaultDimsV31(body, size);
            return;
        }
        if (StrUtil.isNotBlank(size)) {
            String upper = size.toUpperCase(Locale.ROOT);
            Integer area = switch (upper) {
                case "1K" -> JimengConstants.SIZE_AREA_1K;
                case "2K" -> JimengConstants.SIZE_AREA_2K;
                case "4K" -> JimengConstants.SIZE_AREA_4K;
                default -> null;
            };
            if (area != null) {
                body.put(JimengConstants.JSON_SIZE, area);
                log.info("即梦图片翻译 size={} -> area={}, modelCode={}", size, area, modelCode);
            } else {
                log.warn("即梦图片未识别 size={}，忽略，由上游默认, modelCode={}", size, modelCode);
            }
        }
        // 兜底：不传任何尺寸参数，上游默认 2K 面积 + 智能比例
    }

    /**
     * 3.1 档位兜底：无比例时按档位写官方 1:1 推荐宽高。
     * 官方约束宽高乘积 ≤ 2048*2048（无 4K），2K 以上档位一律压到 2048*2048；不传档位时不下发（上游默认 1328*1328）。
     */
    private void applyDefaultDimsV31(Map<String, Object> body, String size) {
        if (StrUtil.isBlank(size)) {
            return;
        }
        String upper = size.toUpperCase(Locale.ROOT);
        if ("1K".equals(upper)) {
            body.put(JimengConstants.JSON_WIDTH, V31_DEFAULT_DIMENSION);
            body.put(JimengConstants.JSON_HEIGHT, V31_DEFAULT_DIMENSION);
        } else if ("2K".equals(upper)) {
            body.put(JimengConstants.JSON_WIDTH, V31_2K_DIMENSION);
            body.put(JimengConstants.JSON_HEIGHT, V31_2K_DIMENSION);
        } else if ("4K".equals(upper)) {
            // 3.1 官方上限 2048*2048，4K 请求压回 2K 并告警
            log.warn("即梦3.1 不支持 4K, 压回 2K(2048*2048)");
            body.put(JimengConstants.JSON_WIDTH, V31_2K_DIMENSION);
            body.put(JimengConstants.JSON_HEIGHT, V31_2K_DIMENSION);
        } else {
            log.warn("即梦3.1 未识别 size={}，不下发宽高，由上游默认 1328*1328", size);
        }
    }

    /**
     * 根据 size 档位 + modelCode 选择推荐宽高表。
     * 1K：3.1 用官方 1328 基准表，4.0/4.6 用 1024 表；
     * 2K：三个版本官方推荐值一致，共用；
     * 4K：仅 4.0/4.6（两版在 4:3/21:9 差 1px），3.1 无 4K 压回 2K 表。
     */
    private Map<String, int[]> resolveSizeTable(String size, String modelCode) {
        boolean isV31 = JimengConstants.MODEL_CODE_V31.equalsIgnoreCase(modelCode);
        if ("1K".equalsIgnoreCase(size)) {
            return isV31 ? JIMENG_RATIO_TO_1K_SIZE_V31 : JIMENG_RATIO_TO_1K_SIZE;
        }
        if ("4K".equalsIgnoreCase(size)) {
            if (isV31) {
                // 3.1 官方无 4K，回退 2K 表
                log.warn("即梦3.1 不支持 4K 档位，按 2K 推荐宽高下发");
                return JIMENG_RATIO_TO_2K_SIZE;
            }
            return JimengConstants.MODEL_CODE_V46.equalsIgnoreCase(modelCode)
                    ? JIMENG_RATIO_TO_4K_SIZE_V46 : JIMENG_RATIO_TO_4K_SIZE_V40;
        }
        // 默认（含 "2K"、空、其他）
        return JIMENG_RATIO_TO_2K_SIZE;
    }

    /**
     * options 里有对应 key 就写入 body，保持原值类型（数字保持数字、字符串保持字符串）。
     */
    private void putIfPresent(Map<String, Object> body, Map<String, Object> options,
                              String bodyKey, String optionKey) {
        Object v = options.get(optionKey);
        if (v != null) {
            body.put(bodyKey, v);
        }
    }

    // ------------------------------------------------------------------
    // 参考图归集
    // ------------------------------------------------------------------

    /**
     * 合并请求中三处来源的参考图：options.images / options.referenceImages / referenceImageUrl。
     * 保持顺序且去重。
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveImageInputs(MediaImageGenerateRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<String> merged = new ArrayList<>();
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            Object imagesVal = options.get(JimengConstants.OPTIONS_IMAGES);
            if (imagesVal instanceof List<?>) {
                for (Object o : (List<Object>) imagesVal) {
                    if (o != null) {
                        merged.add(String.valueOf(o));
                    }
                }
            }
            Object refImagesVal = options.get(JimengConstants.OPTIONS_REFERENCE_IMAGES);
            if (refImagesVal instanceof List<?>) {
                for (Object o : (List<Object>) refImagesVal) {
                    if (o != null) {
                        merged.add(String.valueOf(o));
                    }
                }
            }
        }
        if (StrUtil.isNotBlank(request.getReferenceImageUrl())) {
            merged.add(request.getReferenceImageUrl());
        }
        // 去重保留首次出现顺序
        List<String> deduped = new ArrayList<>(merged.size());
        for (String s : merged) {
            if (StrUtil.isNotBlank(s) && !deduped.contains(s)) {
                deduped.add(s);
            }
        }
        return deduped;
    }

    // ------------------------------------------------------------------
    // 发起带签名的 POST
    // ------------------------------------------------------------------

    /**
     * 使用 {@link VolcengineVisualSigner} 对 Body 做 SigV4 签名，拼上 Query 后 POST。
     */
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
                log.error("即梦 HTTP 非 2xx, status={}, raw={}", response.getStatus(),
                        StringUtils.abbreviate(raw, JimengConstants.LOG_RESPONSE_SNIPPET_MAX));
            }
            return raw;
        }
    }

    /**
     * 从完整 URL 中解析 host；解析失败时回退到默认 host。
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
     * 读取外层 code 字段，容错为 0 便于上层识别异常。
     */
    private int readCode(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode node = root.get(JimengConstants.RESP_CODE);
        return (node == null || !node.isNumber()) ? 0 : node.asInt();
    }

    /**
     * 读取 data.image_urls 全部 URL，顺序保留。空或非法数组返回空列表。
     */
    private List<String> readImageUrls(JsonNode root) {
        if (root == null) {
            return Collections.emptyList();
        }
        JsonNode data = root.get(JimengConstants.RESP_DATA);
        if (data == null) {
            return Collections.emptyList();
        }
        JsonNode urls = data.get(JimengConstants.RESP_IMAGE_URLS);
        if (urls == null || !urls.isArray() || urls.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(urls.size());
        for (JsonNode node : urls) {
            if (node == null || node.isNull()) {
                continue;
            }
            String v = node.asText(null);
            if (StrUtil.isNotBlank(v)) {
                out.add(v);
            }
        }
        return out;
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
     * 解析最终使用的 modelCode：经 ModelCodeResolver 解析（real_model_code 解耦展示码）。
     */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        return com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
    }

    /**
     * modelCode → 上游 req_key 固定映射；找不到直接抛错，避免误发请求。
     * 路由层（{@code MediaGenerationServiceImpl#resolveImageClient} / {@link #supportsModel}）
     * 都采用小写前缀匹配，本方法也按小写查表，保证"路由命中 → 映射成功"一致，
     * 避免外部传 {@code Jimeng-Image-4.0} 等大小写混写时进到本实现又立刻报"模型不支持"。
     */
    private String resolveReqKey(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            log.error("即梦图片 modelCode 为空");
            throw new IllegalArgumentException("缺少模型");
        }
        String reqKey = JimengConstants.MODEL_CODE_TO_REQ_KEY.get(modelCode.toLowerCase(java.util.Locale.ROOT));
        if (StrUtil.isBlank(reqKey)) {
            log.error("即梦图片 modelCode 未在固定映射中, modelCode={}", modelCode);
            throw new IllegalArgumentException("模型不支持");
        }
        return reqKey;
    }
}
