package com.aid.media.provider.impl;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.common.moderation.tencent.TencentCloudTc3Signer;
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

    /** HTTP 超时（毫秒） */
    private static final int HTTP_TIMEOUT_MS = 30_000;

    /** MPS 配置管理器 */
    private final MpsConfigManager mpsConfigManager;

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
        String raw = doRequest(ACTION_EDIT_MEDIA, payload);
        JSONObject root = parse(raw);
        JSONObject response = root.getJSONObject("Response");
        if (Objects.isNull(response)) {
            log.error("MPS EditMedia 响应异常, raw={}", brief(raw));
            throw new RuntimeException("合成失败");
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error)) {
            log.error("MPS EditMedia 提交失败, error={}", error);
            throw new RuntimeException("合成失败");
        }
        String taskId = response.getString("TaskId");
        if (StrUtil.isBlank(taskId)) {
            log.error("MPS EditMedia 未返回 TaskId, raw={}", brief(raw));
            throw new RuntimeException("合成失败");
        }
        log.info("MPS EditMedia 提交成功, providerTaskId={}", taskId);
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .rawResponse(raw)
                .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        JSONObject reqBody = new JSONObject();
        reqBody.put("TaskId", providerTaskId);
        String raw = doRequest(ACTION_DESCRIBE_TASK, reqBody.toJSONString());
        JSONObject root = parse(raw);
        JSONObject response = root.getJSONObject("Response");
        if (Objects.isNull(response)) {
            log.warn("MPS DescribeTaskDetail 响应异常, providerTaskId={}, raw={}", providerTaskId, brief(raw));
            return ProviderTaskResult.builder()
                    .status(MediaTaskStatus.PROCESSING.name())
                    .rawResponse(raw)
                    .build();
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error)) {
            // 查询接口本身报错（非任务失败）：保持 PROCESSING，交由轮询兜底重试
            log.warn("MPS DescribeTaskDetail 查询报错, providerTaskId={}, error={}", providerTaskId, error);
            return ProviderTaskResult.builder()
                    .status(MediaTaskStatus.PROCESSING.name())
                    .rawResponse(raw)
                    .build();
        }
        JSONObject editTask = response.getJSONObject("EditMediaTask");
        String mpsStatus = Objects.nonNull(editTask) ? editTask.getString("Status") : response.getString("Status");
        Integer errCode = Objects.nonNull(editTask) ? editTask.getInteger("ErrCode") : null;
        String message = Objects.nonNull(editTask) ? editTask.getString("Message") : null;
        String normalized = normalizeStatus(mpsStatus, errCode);

        ProviderTaskResult.ProviderTaskResultBuilder builder = ProviderTaskResult.builder()
                .status(normalized)
                .rawResponse(raw);

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
                log.warn("MPS 任务成功但未解析到成片URL, providerTaskId={}, raw={}", providerTaskId, brief(raw));
            }
        } else if (MediaTaskStatus.FAILED.name().equals(normalized)) {
            String err = StrUtil.isNotBlank(message) ? message : ("ErrCode=" + errCode);
            builder.errorMessage(err);
        }
        return builder.build();
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
        MpsProperties props = mpsConfigManager.getMpsProperties();
        if (StrUtil.isBlank(bucket)) {
            bucket = props.getOutputBucket();
        }
        if (StrUtil.isBlank(region)) {
            region = props.getOutputRegion();
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
        if (!mpsConfigManager.isConfigured()) {
            log.error("MPS 未配置, 无法调用 {}", action);
            throw new RuntimeException("未配置");
        }
        MpsProperties props = mpsConfigManager.getMpsProperties();
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
            return response.body();
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
            log.warn("MPS 响应解析失败, raw={}", brief(raw));
            return new JSONObject();
        }
    }

    /**
     * 截断日志文本。
     *
     * @param raw 原始文本
     * @return 截断后的文本
     */
    private String brief(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        return raw.length() > 500 ? raw.substring(0, 500) : raw;
    }
}
