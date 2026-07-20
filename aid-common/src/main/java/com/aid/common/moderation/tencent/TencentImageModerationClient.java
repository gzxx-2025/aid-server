package com.aid.common.moderation.tencent;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.moderation.ImageModerationClient;
import com.aid.common.moderation.ModerationRequest;
import com.aid.common.moderation.ModerationResult;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.common.moderation.exception.ImageModerationException;
import com.aid.common.moderation.properties.ImageModerationProperties;
import com.aid.common.moderation.util.ImageNormalizer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云图片内容安全审查客户端（IMS ImageModeration）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentImageModerationClient implements ImageModerationClient
{
    /** IMS 服务名 */
    private static final String SERVICE = "ims";

    /** IMS 请求域名 */
    private static final String HOST = "ims.tencentcloudapi.com";

    /** 请求地址 */
    private static final String ENDPOINT = "https://" + HOST;

    /** 接口名 */
    private static final String ACTION = "ImageModeration";

    /** 接口版本 */
    private static final String VERSION = "2020-12-29";

    /** 厂商标识 */
    private static final String PROVIDER_TENCENT = "tencent";

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 5000;

    /** 下载源图字节最长读取超时（毫秒） */
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 8000;

    /** 单次送审字节大小上限：10MB（IMS Base64 限制） */
    private static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;

    /** DataId 最大长度 */
    private static final int MAX_DATA_ID_LEN = 64;

    /**
     * 「图片本身问题」类错误码集合：命中后值得换条路（FileUrl → 下载 + 归一化 + FileContent）再试一次。
     */
    private static final Set<String> IMAGE_FAULT_CODES = Set.of(
            "InvalidParameter.InvalidImageContent",
            "InvalidParameterValue.InvalidImageContent",
            "InvalidParameterValue.InvalidContent",
            "InvalidParameterValue.InvalidFileContentSize",
            "InvalidParameterValue.EmptyImageContent",
            "ResourceUnavailable.ImageDownloadError",
            "ResourceUnavailable.InvalidImageContent",
            "InvalidParameter.ImageAspectRatioTooLarge",
            "InvalidParameter.ImageDataTooSmall",
            "InvalidParameter.ImageSizeTooSmall",
            "InvalidParameterValue.ImageSizeTooSmall"
    );

    /**
     * 鉴权类错误码前缀集合：命中后立即停止重试，避免浪费配额。
     */
    private static final Set<String> AUTH_ERROR_PREFIXES = Set.of(
            "AuthFailure",
            "UnauthorizedOperation"
    );

    /** 配置管理器 */
    private final ImageModerationConfigManager configManager;

    @Override
    public boolean enabled()
    {
        ImageModerationProperties props = configManager.getProperties();
        if (Objects.isNull(props) || !props.isEnabled())
        {
            return false;
        }
        // 仅腾讯云厂商且凭证齐备时可用
        return PROVIDER_TENCENT.equalsIgnoreCase(props.getProvider())
                && StrUtil.isNotBlank(props.getTencentSecretId())
                && StrUtil.isNotBlank(props.getTencentSecretKey());
    }

    @Override
    public ModerationResult moderate(ModerationRequest req)
    {
        // 使用已落库的生效配置执行审查
        return doModerate(configManager.getProperties(), req);
    }

    /**
     * 使用临时配置执行一次审查（用于后台连通性测试，配置不落库）
     *
     * @param tmpProps 临时配置
     * @param req      审查请求
     * @return 审查结果
     */
    public ModerationResult moderateWith(ImageModerationProperties tmpProps, ModerationRequest req)
    {
        if (Objects.isNull(tmpProps))
        {
            log.error("图片审查临时配置为空");
            throw new ImageModerationException("配置为空");
        }
        return doModerate(tmpProps, req);
    }

    /**
     * 审查核心逻辑：URL 优先 → 失败回退 base64（带 JPEG 归一化）。
     *
     * @param props 生效配置
     * @param req   审查请求
     * @return 审查结果
     */
    private ModerationResult doModerate(ImageModerationProperties props, ModerationRequest req)
    {
        boolean hasBytes = Objects.nonNull(req.getFileContent()) && req.getFileContent().length > 0;
        boolean hasUrl = StrUtil.isNotBlank(req.getFileUrl()) && isHttpUrl(req.getFileUrl());

        if (!hasBytes && !hasUrl)
        {
            log.error("图片审查缺少图片来源, bizSource={}, userId={}", req.getBizSource(), req.getUserId());
            throw new ImageModerationException("缺少图片来源");
        }

        // —— 路径 1：仅有字节 → 先归一化再 base64 提交 ——
        if (!hasUrl)
        {
            byte[] normalized = ImageNormalizer.normalizeToJpeg(req.getFileContent());
            ModerationResult r = callImsAndParse(props, buildBase64Payload(normalized, req));
            if (r.isError())
            {
                logAndThrow(r, "bytes");
            }
            return r;
        }

        // —— 路径 2：有 URL → URL 优先 ——
        ModerationResult r1 = callImsAndParse(props, buildUrlPayload(req.getFileUrl(), req));
        if (!r1.isError())
        {
            return r1;
        }
        // 鉴权类错误：立即抛出，重试无意义
        if (isAuthError(r1.getErrorCode()))
        {
            logAndThrow(r1, "url");
        }
        // 不是「图片本身问题」类错误 → 直接抛，避免在系统错误上浪费下载流量
        if (!isImageFault(r1.getErrorCode()))
        {
            logAndThrow(r1, "url");
        }

        // —— 路径 3：URL 模式因「图片本身问题」失败 → 下载 + 归一化 + base64 重试 ——
        log.warn("IMS FileUrl 模式返回图片问题码({})，回退下载+JPEG归一化+FileContent 重试, url={}, bizSource={}",
                r1.getErrorCode(), req.getFileUrl(), req.getBizSource());
        byte[] raw;
        try
        {
            raw = downloadBytes(req.getFileUrl());
        }
        catch (ImageModerationException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("送审图片下载失败, url={}, error={}", req.getFileUrl(), e.getMessage(), e);
            throw new ImageModerationException("图片下载失败", e);
        }
        byte[] normalized = ImageNormalizer.normalizeToJpeg(raw);

        ModerationResult r2 = callImsAndParse(props, buildBase64Payload(normalized, req));
        if (r2.isError())
        {
            // 第二次仍报「图片本身问题」 → 真的是坏图，抛出明确文案
            if (isImageFault(r2.getErrorCode()))
            {
                log.error("IMS 回退 FileContent 模式仍判图片问题码({}), 源图无法救活, url={}, bizSource={}",
                        r2.getErrorCode(), req.getFileUrl(), req.getBizSource());
                throw new ImageModerationException("图片格式异常");
            }
            logAndThrow(r2, "base64-retry");
        }
        return r2;
    }

    /** 把异常结果转抛为业务异常并打印诊断日志。 */
    private void logAndThrow(ModerationResult r, String stage)
    {
        log.error("图片审查接口返回错误, stage={}, code={}, message={}",
                stage, r.getErrorCode(), r.getErrorMessage());
        if (isAuthError(r.getErrorCode()))
        {
            throw new ImageModerationException("审查鉴权失败");
        }
        if (isImageFault(r.getErrorCode()))
        {
            throw new ImageModerationException("图片格式异常");
        }
        throw new ImageModerationException("审查接口错误");
    }

    /** 是否「图片本身问题」类错误码（值得换条路重试）。 */
    private boolean isImageFault(String code)
    {
        return StrUtil.isNotBlank(code) && IMAGE_FAULT_CODES.contains(code);
    }

    /** 是否「鉴权 / 套餐」类错误码（重试无意义）。 */
    private boolean isAuthError(String code)
    {
        if (StrUtil.isBlank(code))
        {
            return false;
        }
        for (String prefix : AUTH_ERROR_PREFIXES)
        {
            if (code.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /** 仅识别 http/https 完整 URL；相对路径无法被腾讯抓取，必须走字节模式。 */
    private boolean isHttpUrl(String url)
    {
        if (StrUtil.isBlank(url))
        {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 拼装「FileUrl」请求体 JSON。 */
    private String buildUrlPayload(String url, ModerationRequest req)
    {
        JSONObject payload = new JSONObject();
        payload.put("Type", "IMAGE");
        payload.put("FileUrl", url);
        payload.put("DataId", buildDataId(req.getUserId()));
        return payload.toJSONString();
    }

    /** 拼装「FileContent」（base64）请求体 JSON。 */
    private String buildBase64Payload(byte[] bytes, ModerationRequest req)
    {
        JSONObject payload = new JSONObject();
        payload.put("Type", "IMAGE");
        payload.put("FileContent", Base64.getEncoder().encodeToString(bytes));
        payload.put("DataId", buildDataId(req.getUserId()));
        return payload.toJSONString();
    }

    /**
     * 下载远程图片字节，附带 10MB 大小上限。
     *
     * @param url 公网图片 URL
     * @return 字节内容
     */
    private byte[] downloadBytes(String url)
    {
        try (HttpResponse response = HttpRequest.get(url)
                .header("User-Agent", "AID-ImageModeration/1.0")
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS)
                .execute())
        {
            if (!response.isOk())
            {
                log.error("下载送审图片状态非 200, url={}, status={}", url, response.getStatus());
                throw new ImageModerationException("图片下载失败");
            }
            byte[] bytes = response.bodyBytes();
            if (Objects.isNull(bytes) || bytes.length == 0)
            {
                log.error("下载送审图片字节为空, url={}", url);
                throw new ImageModerationException("图片内容为空");
            }
            if (bytes.length > MAX_DOWNLOAD_BYTES)
            {
                log.error("下载送审图片超出 10MB 上限, url={}, size={}B", url, bytes.length);
                throw new ImageModerationException("图片过大");
            }
            return bytes;
        }
    }

    /**
     * 发起一次签名请求并解析结果。请求级网络错误抛 ImageModerationException；
     * 业务级错误（包括鉴权类与图片本身问题）通过 {@link ModerationResult#isError()} 返回。
     *
     * @param props       生效配置
     * @param payloadJson 请求体 JSON
     * @return 审查结果（带 errorCode / errorMessage）
     */
    private ModerationResult callImsAndParse(ImageModerationProperties props, String payloadJson)
    {
        long timestampSec = System.currentTimeMillis() / 1000L;
        Map<String, String> headers = TencentCloudTc3Signer.buildHeaders(SERVICE, HOST, ACTION, VERSION,
                props.getTencentRegion(), payloadJson, props.getTencentSecretId(), props.getTencentSecretKey(),
                timestampSec);
        String body;
        try (HttpResponse response = HttpRequest.post(ENDPOINT)
                .addHeaders(headers)
                .body(payloadJson)
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute())
        {
            body = response.body();
        }
        catch (Exception e)
        {
            log.error("图片审查请求失败, error={}", e.getMessage(), e);
            throw new ImageModerationException("审查请求失败", e);
        }
        return parseResponse(body);
    }

    /**
     * 凭证 + 连通性探测（不传图片）：用于后台「测试连接」按钮。
     *
     * @param props 临时配置
     * @return 探测结果
     */
    public ConnectivityProbeResult probeConnectivity(ImageModerationProperties props)
    {
        if (Objects.isNull(props)
                || StrUtil.isBlank(props.getTencentSecretId())
                || StrUtil.isBlank(props.getTencentSecretKey()))
        {
            return ConnectivityProbeResult.fail("请填写完整密钥", null);
        }
        // 故意不传 FileContent/FileUrl：鉴权通过则返回参数类错误，鉴权失败则返回 AuthFailure
        JSONObject payload = new JSONObject();
        payload.put("Type", "IMAGE");
        payload.put("DataId", buildDataId(null));
        String body;
        try
        {
            long timestampSec = System.currentTimeMillis() / 1000L;
            Map<String, String> headers = TencentCloudTc3Signer.buildHeaders(SERVICE, HOST, ACTION, VERSION,
                    props.getTencentRegion(), payload.toJSONString(),
                    props.getTencentSecretId(), props.getTencentSecretKey(), timestampSec);
            try (HttpResponse response = HttpRequest.post(ENDPOINT)
                    .addHeaders(headers)
                    .body(payload.toJSONString())
                    .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS)
                    .execute())
            {
                body = response.body();
            }
        }
        catch (Exception e)
        {
            return ConnectivityProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return interpretProbe(body);
    }

    /**
     * 解读探测响应：鉴权类错误判失败，其余（参数类错误 / 无错误）判成功。
     */
    private ConnectivityProbeResult interpretProbe(String body)
    {
        if (StrUtil.isBlank(body))
        {
            return ConnectivityProbeResult.fail("审查响应为空", null);
        }
        JSONObject response;
        try
        {
            JSONObject root = JSON.parseObject(body);
            response = Objects.isNull(root) ? null : root.getJSONObject("Response");
        }
        catch (Exception e)
        {
            return ConnectivityProbeResult.fail("审查响应解析失败", StrUtil.brief(body, 200));
        }
        if (Objects.isNull(response))
        {
            return ConnectivityProbeResult.fail("审查响应异常", StrUtil.brief(body, 200));
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.isNull(error))
        {
            return ConnectivityProbeResult.ok("连接成功", "RequestId=" + response.getString("RequestId"));
        }
        String code = StrUtil.trimToEmpty(error.getString("Code"));
        String message = StrUtil.trimToEmpty(error.getString("Message"));
        String detail = code + ":" + message;
        if (isAuthError(code))
        {
            return ConnectivityProbeResult.fail("密钥无效或未开通", detail);
        }
        // 参数类错误（图片为空等）说明鉴权已通过、网关连通
        return ConnectivityProbeResult.ok("连接成功(凭证有效)", detail);
    }

    /**
     * 解析 IMS 响应：成功返回审查结果，业务错误返回带 errorCode 的结果对象，网络/解析异常抛出。
     *
     * @param body 响应体
     * @return 审查结果
     */
    private ModerationResult parseResponse(String body)
    {
        if (StrUtil.isBlank(body))
        {
            log.error("图片审查响应为空");
            throw new ImageModerationException("审查响应为空");
        }
        JSONObject response;
        try
        {
            JSONObject root = JSON.parseObject(body);
            response = Objects.isNull(root) ? null : root.getJSONObject("Response");
        }
        catch (Exception e)
        {
            log.error("图片审查响应解析失败, body={}, error={}", body, e.getMessage(), e);
            throw new ImageModerationException("审查响应解析失败", e);
        }
        if (Objects.isNull(response))
        {
            log.error("图片审查响应缺少 Response 字段, body={}", body);
            throw new ImageModerationException("审查响应异常");
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error))
        {
            String code = error.getString("Code");
            String message = error.getString("Message");
            // 业务级错误：返回结果对象，不抛异常，让调用方决定是否重试
            ModerationResult r = ModerationResult.error(code, message);
            r.setRawJson(body);
            return r;
        }
        // 正常结果
        ModerationResult result = new ModerationResult();
        result.setSuggestion(response.getString("Suggestion"));
        result.setLabel(response.getString("Label"));
        result.setSubLabel(response.getString("SubLabel"));
        result.setScore(response.getInteger("Score"));
        result.setFileMd5(response.getString("FileMD5"));
        result.setRequestId(response.getString("RequestId"));
        result.setRawJson(body);
        return result;
    }

    /** 构建 DataId：userId + 时间戳，截断到 64 位。 */
    private String buildDataId(Long userId)
    {
        String prefix = Objects.isNull(userId) ? "0" : String.valueOf(userId);
        String dataId = prefix + "_" + System.currentTimeMillis();
        if (dataId.length() > MAX_DATA_ID_LEN)
        {
            dataId = dataId.substring(0, MAX_DATA_ID_LEN);
        }
        return dataId;
    }

    /**
     * 凭证连通性探测结果
     */
    public static class ConnectivityProbeResult
    {
        /** 是否连通 */
        private final boolean ok;

        /** 结论文案 */
        private final String message;

        /** 调试明细（不含密钥） */
        private final String detail;

        private ConnectivityProbeResult(boolean ok, String message, String detail)
        {
            this.ok = ok;
            this.message = message;
            this.detail = detail;
        }

        public static ConnectivityProbeResult ok(String message, String detail)
        {
            return new ConnectivityProbeResult(true, message, detail);
        }

        public static ConnectivityProbeResult fail(String message, String detail)
        {
            return new ConnectivityProbeResult(false, message, detail);
        }

        public boolean isOk()
        {
            return ok;
        }

        public String getMessage()
        {
            return message;
        }

        public String getDetail()
        {
            return detail;
        }
    }
}
