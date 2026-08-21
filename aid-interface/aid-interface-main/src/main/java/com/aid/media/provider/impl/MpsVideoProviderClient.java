package com.aid.media.provider.impl;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.exception.ComposeUpstreamUnavailableException;
import com.aid.common.moderation.tencent.TencentCloudTc3Signer;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS 协议 Provider（按 protocol=tencent-mps 路由，不进模型/供应商目录）。
 * 提交走 EditMedia、查询走 DescribeTaskDetail，鉴权复用 {@link TencentCloudTc3Signer}
 * （service=mps、host=mps.tencentcloudapi.com、version=2019-06-12）。
 * MPS 不在模型目录，{@link AiModelConfigVo} 仅作接口形参占位，真实配置由 {@link MpsConfigManager} 读取。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpsVideoProviderClient implements VideoProviderClient {

    /** 协议标识 */
    public static final String PROTOCOL_MPS = "tencent-mps";

    /** COMPOSE 任务的 EditMedia 请求体在 options 中的承载键 */
    public static final String OPTION_EDIT_MEDIA_REQUEST = "editMediaRequest";

    /** MPS 接口域名 */
    private static final String MPS_HOST = "mps.tencentcloudapi.com";

    /** MPS 接口地址 */
    private static final String MPS_ENDPOINT = "https://mps.tencentcloudapi.com";

    /** MPS 产品名 */
    private static final String MPS_SERVICE = "mps";

    /** MPS 接口版本 */
    private static final String MPS_VERSION = "2019-06-12";

    /** 提交合成 Action */
    private static final String ACTION_EDIT_MEDIA = "EditMedia";

    /** 查询任务详情 Action */
    private static final String ACTION_DESCRIBE_TASK = "DescribeTaskDetail";

    /** 查询最近任务 Action（仅用于 SessionId 去重后的任务 ID 恢复） */
    private static final String ACTION_DESCRIBE_TASKS = "DescribeTasks";

    /** 腾讯云 SessionId 重复错误码 */
    private static final String ERROR_DUPLICATE_SESSION_ID = "InvalidParameterValue.SessionId";

    /** 恢复扫描最多反查的编辑任务数，避免罕见异常路径放大上游请求 */
    private static final int MAX_RECOVERY_DETAILS = 100;

    /** HTTP 超时（毫秒） */
    private static final int HTTP_TIMEOUT_MS = 30_000;

    /** MPS 配置管理器 */
    private final MpsConfigManager mpsConfigManager;
    private final OssConfigManager ossConfigManager;

    @Override
    public String protocol() {
        return PROTOCOL_MPS;
    }

    @Override
    public boolean supportsProtocol(String protocol) {
        return PROTOCOL_MPS.equalsIgnoreCase(protocol);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        Object body = request == null || request.getOptions() == null
                ? null : request.getOptions().get(OPTION_EDIT_MEDIA_REQUEST);
        if (Objects.isNull(body)) {
            log.error("MPS EditMedia 缺少请求体, options 为空");
            throw new RuntimeException("合成失败");
        }
        String payload = JSON.toJSONString(body);
        validateOutputOwnership(parse(payload));
        String raw = doRequest(ACTION_EDIT_MEDIA, payload);
        JSONObject root = parse(raw);
        JSONObject response = root.getJSONObject("Response");
        if (Objects.isNull(response)) {
            log.error("MPS EditMedia 响应异常, responseLen={}", StrUtil.length(raw));
            throw new ComposeUpstreamUnavailableException("提交待确认");
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error)) {
            String errorCode = error.getString("Code");
            if (ERROR_DUPLICATE_SESSION_ID.equalsIgnoreCase(errorCode)) {
                JSONObject submitBody = parse(payload);
                String recoveredTaskId = recoverDuplicateTaskId(
                        submitBody.getString("SessionId"), submitBody.getString("SessionContext"),
                        submitBody.getString("OutputObjectPath"));
                if (StrUtil.isNotBlank(recoveredTaskId)) {
                    log.info("MPS EditMedia 重复提交已恢复原任务, providerTaskId={}", recoveredTaskId);
                    return ProviderSubmitResult.builder()
                            .providerTaskId(recoveredTaskId)
                            .rawResponse(raw)
                            .build();
                }
                log.warn("MPS EditMedia SessionId 已存在但暂未恢复任务 ID, sessionContext={}",
                        submitBody.getString("SessionContext"));
                throw new ComposeUpstreamUnavailableException("任务待恢复");
            }
            if (isRetryableApiError(errorCode)) {
                log.warn("MPS EditMedia 上游暂不可用, error={}", error);
                throw new ComposeUpstreamUnavailableException("上游暂不可用");
            }
            log.error("MPS EditMedia 提交失败, error={}", error);
            throw new RuntimeException("合成失败");
        }
        String taskId = response.getString("TaskId");
        if (StrUtil.isBlank(taskId)) {
            log.error("MPS EditMedia 未返回 TaskId, responseLen={}", StrUtil.length(raw));
            throw new RuntimeException("合成失败");
        }
        log.info("MPS EditMedia 提交成功, providerTaskId={}", taskId);
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .rawResponse(raw)
                .build();
    }

    /**
     * SessionId 重复时，在三天去重窗口内的编辑任务中反查详情，并匹配唯一输出对象路径。
     * DescribeTasks 不提供会话字段过滤，因此只扫描三种状态各自最新一页，并设置总详情查询上限。
     */
    private String recoverDuplicateTaskId(String sessionId, String sessionContext, String outputObjectPath) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(sessionContext)
                || StrUtil.isBlank(outputObjectPath)) {
            return null;
        }
        // SessionId 的去重窗口是三天。应用长时间停机后仍需覆盖整个窗口，避免一直命中重复错误却无法恢复。
        String startTime = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        String endTime = Instant.now().plus(1, ChronoUnit.MINUTES).toString();
        int checked = 0;
        for (String status : List.of("PROCESSING", "WAITING", "FINISH")) {
            JSONObject listRequest = new JSONObject();
            listRequest.put("Status", status);
            listRequest.put("Limit", 100);
            listRequest.put("StartTime", startTime);
            listRequest.put("EndTime", endTime);
            JSONObject listResponse = parse(doRequest(ACTION_DESCRIBE_TASKS, listRequest.toJSONString()))
                    .getJSONObject("Response");
            if (Objects.isNull(listResponse) || Objects.nonNull(listResponse.getJSONObject("Error"))) {
                continue;
            }
            JSONArray taskSet = listResponse.getJSONArray("TaskSet");
            if (Objects.isNull(taskSet)) {
                continue;
            }
            for (Object itemValue : taskSet) {
                if (!(itemValue instanceof JSONObject item)
                        || !"EditMediaTask".equalsIgnoreCase(item.getString("TaskType"))) {
                    continue;
                }
                if (++checked > MAX_RECOVERY_DETAILS) {
                    return null;
                }
                String candidateTaskId = item.getString("TaskId");
                if (StrUtil.isBlank(candidateTaskId)) {
                    continue;
                }
                JSONObject detailRequest = new JSONObject();
                detailRequest.put("TaskId", candidateTaskId);
                JSONObject detailResponse = parse(doRequest(ACTION_DESCRIBE_TASK, detailRequest.toJSONString()))
                        .getJSONObject("Response");
                if (matchesRecoveredTask(detailResponse, sessionId, sessionContext, outputObjectPath)) {
                    return candidateTaskId;
                }
            }
        }
        return null;
    }

    /**
     * MPS 的 DescribeTaskDetail 文档未承诺回传 SessionId/SessionContext，因此恢复主依据是每个系统任务
     * 唯一的输出对象路径；若响应额外带回会话字段，则同时做一致性校验。
     */
    private boolean matchesRecoveredTask(JSONObject response, String sessionId, String sessionContext,
                                         String expectedOutputPath) {
        if (Objects.isNull(response)
                || !"EditMediaTask".equalsIgnoreCase(response.getString("TaskType"))) {
            return false;
        }
        String returnedSessionId = response.getString("SessionId");
        String returnedContext = response.getString("SessionContext");
        if ((StrUtil.isNotBlank(returnedSessionId) && !sessionId.equals(returnedSessionId))
                || (StrUtil.isNotBlank(returnedContext) && !sessionContext.equals(returnedContext))) {
            return false;
        }
        JSONObject editTask = response.getJSONObject("EditMediaTask");
        JSONObject output = editTask == null ? null : editTask.getJSONObject("Output");
        String actualPath = output == null ? null : output.getString("Path");
        return outputPathMatches(expectedOutputPath, actualPath);
    }

    private boolean outputPathMatches(String expected, String actual) {
        if (StrUtil.isBlank(expected) || StrUtil.isBlank(actual)) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        String marker = ".{format}";
        if (!expected.endsWith(marker)) {
            return false;
        }
        String prefix = expected.substring(0, expected.length() - marker.length());
        if (!actual.startsWith(prefix + ".")) {
            return false;
        }
        String extension = actual.substring(prefix.length() + 1);
        return extension.matches("[A-Za-z0-9]{1,10}");
    }

    /** 判断腾讯云业务错误是否属于可重试的瞬态错误。 */
    private boolean isRetryableApiError(String errorCode) {
        return StrUtil.startWithIgnoreCase(errorCode, "InternalError")
                || StrUtil.startWithIgnoreCase(errorCode, "RequestLimitExceeded")
                || StrUtil.startWithIgnoreCase(errorCode, "LimitExceeded");
    }

    /** 排队期间若管理员切换了存储桶，拒绝把旧任务继续输出到原 COS。 */
    private void validateOutputOwnership(JSONObject submitBody) {
        OssProperties current = ossConfigManager.getOssProperties();
        JSONObject output = submitBody == null ? null : submitBody.getJSONObject("OutputStorage");
        JSONObject cos = output == null ? null : output.getJSONObject("CosOutputStorage");
        if (current == null || !"cos".equalsIgnoreCase(current.getUploadMode()) || cos == null
                || !StrUtil.equals(cos.getString("Bucket"), current.getCosBucketName())
                || !StrUtil.equalsIgnoreCase(cos.getString("Region"), current.getCosRegion())) {
            log.error("MPS输出存储归属已变化, currentMode={}, currentBucket={}, requestBucket={}",
                    current == null ? null : current.getUploadMode(),
                    current == null ? null : current.getCosBucketName(),
                    cos == null ? null : cos.getString("Bucket"));
            throw new IllegalStateException("存储已变更");
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        JSONObject reqBody = new JSONObject();
        reqBody.put("TaskId", providerTaskId);
        String raw = doRequest(ACTION_DESCRIBE_TASK, reqBody.toJSONString());
        JSONObject root = parse(raw);
        JSONObject response = root.getJSONObject("Response");
        if (Objects.isNull(response)) {
            log.warn("MPS DescribeTaskDetail 响应异常, providerTaskId={}, responseLen={}",
                    providerTaskId, StrUtil.length(raw));
            return ProviderTaskResult.builder()
                    .status(MediaTaskStatus.PROCESSING.name())
                    .rawResponse(raw)
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error)) {
            // 查询接口本身报错（非任务失败）：保持 PROCESSING，交由轮询兜底重试
            log.warn("MPS DescribeTaskDetail 查询报错, providerTaskId={}, error={}", providerTaskId, error);
            return ProviderTaskResult.builder()
                    .status(MediaTaskStatus.PROCESSING.name())
                    .errorMessage(error.toString())
                    .rawResponse(raw)
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        JSONObject editTask = response.getJSONObject("EditMediaTask");
        String mpsStatus = Objects.nonNull(editTask) ? editTask.getString("Status") : response.getString("Status");
        Integer errCode = Objects.nonNull(editTask) ? editTask.getInteger("ErrCode") : null;
        String message = Objects.nonNull(editTask) ? editTask.getString("Message") : null;
        if (!isKnownStatus(mpsStatus)) {
            log.warn("MPS DescribeTaskDetail 返回未知状态, providerTaskId={}, status={}",
                    providerTaskId, mpsStatus);
            return ProviderTaskResult.builder()
                    .status(MediaTaskStatus.PROCESSING.name())
                    .errorMessage("上游未知状态:" + StrUtil.blankToDefault(mpsStatus, "empty"))
                    .rawResponse(raw)
                    .querySuccessful(Boolean.FALSE)
                    .providerStatus(mpsStatus)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        String normalized = normalizeStatus(mpsStatus, errCode);

        ProviderTaskResult.ProviderTaskResultBuilder builder = ProviderTaskResult.builder()
                .status(normalized)
                .rawResponse(raw)
                .querySuccessful(Boolean.TRUE)
                .providerStatus(mpsStatus)
                .terminalConfirmed("FINISH".equalsIgnoreCase(mpsStatus));

        // 透传 MPS 任务真实进度（EditMediaTask.Progress，0-100）：处理中回写业务表进度展示
        Integer progress = Objects.nonNull(editTask) ? editTask.getInteger("Progress") : null;
        if (Objects.nonNull(progress)) {
            builder.progress(progress);
        }

        if (MediaTaskStatus.SUCCEEDED.name().equals(normalized)) {
            String resultUrl = resolveOutputUrl(editTask);
            Long duration = resolveOutputDuration(editTask);
            builder.resultUrl(resultUrl);
            builder.outputDurationSeconds(duration);
            if (StrUtil.isBlank(resultUrl)) {
                log.warn("MPS 任务成功但未解析到成片URL, providerTaskId={}, responseLen={}",
                        providerTaskId, StrUtil.length(raw));
                return ProviderTaskResult.builder()
                        .status(MediaTaskStatus.PROCESSING.name())
                        .errorMessage("上游成功但结果链接未就绪")
                        .rawResponse(raw)
                        .querySuccessful(Boolean.FALSE)
                        .providerStatus(mpsStatus)
                        .terminalConfirmed(Boolean.FALSE)
                        .build();
            }
            if (!isOwnedCosUrl(resultUrl)) {
                log.error("MPS输出不属于当前COS, providerTaskId={}, resultUrl={}", providerTaskId, resultUrl);
                return ProviderTaskResult.builder()
                        .status(MediaTaskStatus.FAILED.name())
                        .providerStatus(mpsStatus)
                        .errorMessage("输出归属错误")
                        .rawErrorMessage("MPS output storage mismatch")
                        .rawResponse(raw)
                        .querySuccessful(Boolean.TRUE)
                        .terminalConfirmed(Boolean.TRUE)
                        .build();
            }
        } else if (MediaTaskStatus.FAILED.name().equals(normalized)) {
            String err = StrUtil.isNotBlank(message) ? message : ("ErrCode=" + errCode);
            builder.errorMessage(err);
        }
        return builder.build();
    }

    /** 成片必须仍属于后台当前 COS；在途任务期间存储归属变更会被保存接口阻止。 */
    private boolean isOwnedCosUrl(String value) {
        try {
            OssProperties storage = ossConfigManager.getOssProperties();
            if (storage == null || !"cos".equalsIgnoreCase(storage.getUploadMode())) {
                return false;
            }
            URI uri = URI.create(value);
            String expectedHost = storage.getCosBucketName() + ".cos."
                    + storage.getCosRegion() + ".myqcloud.com";
            return StrUtil.equalsIgnoreCase(uri.getHost(), expectedHost)
                    && StrUtil.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath());
        } catch (Exception e) {
            return false;
        }
    }
    /**
     * 归一化 MPS 任务状态：WAITING/PROCESSING → PROCESSING；FINISH + ErrCode==0 → SUCCEEDED；FINISH + ErrCode!=0 → FAILED。
     *
     * @param mpsStatus MPS 状态
     * @param errCode   错误码
     * @return 系统归一化状态
     */
    private String normalizeStatus(String mpsStatus, Integer errCode) {
        if (StrUtil.isBlank(mpsStatus)) {
            return MediaTaskStatus.PROCESSING.name();
        }
        String upper = mpsStatus.trim().toUpperCase();
        if ("FINISH".equals(upper)) {
            if (Objects.isNull(errCode) || errCode == 0) {
                return MediaTaskStatus.SUCCEEDED.name();
            }
            return MediaTaskStatus.FAILED.name();
        }
        // WAITING / PROCESSING / 其它中间态
        return MediaTaskStatus.PROCESSING.name();
    }

    private boolean isKnownStatus(String mpsStatus) {
        if (StrUtil.isBlank(mpsStatus)) {
            return false;
        }
        String upper = mpsStatus.trim().toUpperCase();
        return "WAITING".equals(upper) || "PROCESSING".equals(upper) || "FINISH".equals(upper);
    }

    /**
     * 从 EditMediaTask.Output 解析成片 COS URL（拼对象 URL 作 originUrl）。
     *
     * @param editTask 子任务详情
     * @return 成片 URL，解析不到返回 null
     */
    private String resolveOutputUrl(JSONObject editTask) {
        if (Objects.isNull(editTask)) {
            return null;
        }
        JSONObject output = editTask.getJSONObject("Output");
        if (Objects.isNull(output)) {
            return null;
        }
        String path = output.getString("Path");
        if (StrUtil.isBlank(path)) {
            path = output.getString("OutputObjectPath");
        }
        if (StrUtil.isBlank(path)) {
            // 兼容部分 MPS 输出结构以 Object 承载对象键
            path = output.getString("Object");
        }
        if (StrUtil.isBlank(path)) {
            return null;
        }
        // 优先使用输出详情自带的桶/地域，缺失则回退 MPS 配置
        String bucket = null;
        String region = null;
        JSONObject storage = output.getJSONObject("OutputStorage");
        if (Objects.nonNull(storage)) {
            JSONObject cos = storage.getJSONObject("CosOutputStorage");
            if (Objects.nonNull(cos)) {
                bucket = cos.getString("Bucket");
                region = cos.getString("Region");
            }
        }
        OssProperties storageProps = ossConfigManager.getOssProperties();
        if (StrUtil.isBlank(bucket)) {
            bucket = storageProps.getCosBucketName();
        }
        if (StrUtil.isBlank(region)) {
            region = storageProps.getCosRegion();
        }
        if (StrUtil.isBlank(bucket) || StrUtil.isBlank(region)) {
            return null;
        }
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return "https://" + bucket + ".cos." + region + ".myqcloud.com/" + normalizedPath;
    }

    /**
     * 从 EditMediaTask.Output.MetaData 解析实际输出时长（秒），向上取整。
     *
     * @param editTask 子任务详情
     * @return 实际输出秒数，解析不到返回 null
     */
    private Long resolveOutputDuration(JSONObject editTask) {
        if (Objects.isNull(editTask)) {
            return null;
        }
        JSONObject output = editTask.getJSONObject("Output");
        if (Objects.isNull(output)) {
            return null;
        }
        JSONObject metaData = output.getJSONObject("MetaData");
        Double duration = null;
        if (Objects.nonNull(metaData)) {
            duration = metaData.getDouble("Duration");
        }
        if (Objects.isNull(duration)) {
            duration = output.getDouble("Duration");
        }
        if (Objects.isNull(duration) || duration <= 0) {
            return null;
        }
        return (long) Math.ceil(duration);
    }

    /**
     * 对 MPS 接口做 TC3 签名并发起 POST 请求。
     *
     * @param action  接口 Action
     * @param payload 请求体 JSON
     * @return 上游原始响应
     */
    private String doRequest(String action, String payload) {
        MpsProperties props = mpsConfigManager.getMpsProperties();
        if (StrUtil.isBlank(props.getSecretId()) || StrUtil.isBlank(props.getSecretKey())
                || StrUtil.isBlank(props.getRegion())) {
            log.error("MPS 未配置, 无法调用 {}", action);
            throw new RuntimeException("未配置");
        }
        long timestamp = System.currentTimeMillis() / 1000L;
        Map<String, String> headers = TencentCloudTc3Signer.buildHeaders(
                MPS_SERVICE, MPS_HOST, action, MPS_VERSION,
                props.getRegion(), payload,
                props.getSecretId(), props.getSecretKey(), timestamp);
        try (HttpResponse response = HttpRequest.post(MPS_ENDPOINT)
                .addHeaders(headers)
                .body(payload)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()) {
            if (response.getStatus() == 408 || response.getStatus() == 429 || response.getStatus() >= 500) {
                throw new ComposeUpstreamUnavailableException("上游暂不可用");
            }
            return response.body();
        } catch (ComposeUpstreamUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ComposeUpstreamUnavailableException("上游暂不可用", e);
        }
    }

    /**
     * 解析 JSON 响应（解析失败兜底为空对象）。
     *
     * @param raw 原始响应
     * @return JSON 对象
     */
    private JSONObject parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            log.warn("MPS 响应解析失败, responseLen={}", StrUtil.length(raw));
            return new JSONObject();
        }
    }
}
