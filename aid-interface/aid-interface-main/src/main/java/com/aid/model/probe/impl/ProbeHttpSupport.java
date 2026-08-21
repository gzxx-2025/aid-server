package com.aid.model.probe.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.constant.HttpConstants;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.model.probe.ProbeResult;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * 只读探测的 URL、鉴权与响应分类工具。
 */
public final class ProbeHttpSupport {

    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 8000;

    private static final int DETAIL_BODY_MAX_LEN = 500;
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_MIN = 500;

    private ProbeHttpSupport() {
    }

    /**
     * 校验执行鉴权探测所需的基础配置。
     *
     * @param provider 服务商配置
     * @return 配置错误；配置完整时返回 null
     */
    public static ProbeResult validateProvider(AidAiProvider provider) {
        if (Objects.isNull(provider) || StrUtil.isBlank(provider.getBaseUrl())) {
            return ProbeResult.fail("未配置网关地址", "baseUrl 为空");
        }
        if (StrUtil.isBlank(provider.getApiKey())) {
            return ProbeResult.fail("未配置密钥", "apiKey 为空");
        }
        return null;
    }

    /**
     * 按版本前缀感知规则拼接代理网关与相对路径。
     *
     * @param baseUrl      用户配置网关
     * @param relativePath 官方相对路径
     * @return 探测地址
     */
    public static String buildUrl(String baseUrl, String relativePath) {
        return ProviderEndpointUtils.buildSubmitUrl(baseUrl, relativePath);
    }

    /**
     * 向地址追加已编码的查询参数。
     *
     * @param url   地址
     * @param name  参数名
     * @param value 参数值
     * @return 带查询参数的地址
     */
    public static String addQuery(String url, String name, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + encode(name) + "=" + encode(value);
    }

    /**
     * 从生成路径的受控结尾推导同一代理前缀下的只读兄弟路径。
     *
     * @param apiSuffix    模型生成相对路径
     * @param siblingPath  只读兄弟路径
     * @param knownEndings 可安全剥离的生成路径结尾
     * @return 推导出的只读路径；无法可靠推导时返回 null
     */
    public static String deriveSiblingPath(String apiSuffix, String siblingPath,
                                           String... knownEndings) {
        if (StrUtil.isBlank(apiSuffix)) {
            return null;
        }
        String normalized = ProviderEndpointUtils.normalizeSubmitPath(apiSuffix);
        String path = normalized.split("\\?", 2)[0];
        for (String ending : knownEndings) {
            if (StrUtil.isNotBlank(ending) && path.endsWith(ending)) {
                return path.substring(0, path.length() - ending.length()) + siblingPath;
            }
        }
        return null;
    }

    /** 对动态模型名做安全的 URL 路径段编码。 */
    public static String encodePathSegment(String value) {
        return encode(StrUtil.trimToEmpty(value));
    }

    /**
     * 为请求附加服务商配置的鉴权头。
     *
     * @param request       HTTP 请求
     * @param provider      服务商配置
     * @param defaultHeader 默认鉴权头
     * @param defaultPrefix 默认鉴权前缀
     */
    public static void applyAuth(HttpRequest request, AidAiProvider provider,
                                 String defaultHeader, String defaultPrefix) {
        String authHeader = StrUtil.isNotBlank(provider.getAuthHeader())
                ? provider.getAuthHeader() : defaultHeader;
        String authPrefix = Objects.nonNull(provider.getAuthPrefix())
                ? provider.getAuthPrefix() : defaultPrefix;
        request.header(authHeader, StrUtil.nullToEmpty(authPrefix) + provider.getApiKey(), true);
    }

    /**
     * 使用常规 Bearer 规则附加鉴权头。
     *
     * @param request  HTTP 请求
     * @param provider 服务商配置
     */
    public static void applyBearerAuth(HttpRequest request, AidAiProvider provider) {
        applyAuth(request, provider, HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX);
    }

    /**
     * 执行 HTTP 请求并读取有限响应信息。
     *
     * @param request HTTP 请求
     * @return 响应快照
     */
    public static ProbeHttpResponse execute(HttpRequest request) {
        try (HttpResponse response = request
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            return new ProbeHttpResponse(response.getStatus(), response.body(), response.header("Content-Type"));
        }
    }

    /**
     * 分类所有探测共同的确定性失败响应。
     *
     * @param response HTTP 响应
     * @return 失败结果；需由供应商继续解释时返回 null
     */
    public static ProbeResult classifyCommonFailure(ProbeHttpResponse response) {
        int status = response.status();
        String detail = detail(response);
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN || isStructuredAuthError(response.body())) {
            return ProbeResult.fail("密钥无效或无权限", detail);
        }
        if (status == HTTP_TOO_MANY_REQUESTS) {
            return ProbeResult.fail("请求过于频繁", detail);
        }
        if (status >= HTTP_SERVER_ERROR_MIN) {
            return ProbeResult.fail("上游服务异常", detail);
        }
        return null;
    }

    /**
     * 判断代理是否没有开放当前只读探测路由。
     *
     * @param response HTTP 响应
     * @return 是否只能确认网关可达
     */
    public static boolean isReadOnlyRouteUnavailable(ProbeHttpResponse response) {
        if (response.status() == HTTP_METHOD_NOT_ALLOWED) {
            return true;
        }
        if (response.status() != HTTP_NOT_FOUND) {
            return false;
        }
        String body = StrUtil.trim(response.body());
        JSONObject root = parseObject(body);
        if (Objects.isNull(root)) {
            return !isPlainModelMissing(body);
        }
        String routeMessage = firstNotBlank(root.getString("message"), root.getString("detail"));
        Object errorValue = root.get("error");
        if (errorValue instanceof JSONObject error) {
            routeMessage = firstNotBlank(error.getString("message"), error.getString("detail"), routeMessage);
        } else if (errorValue instanceof String errorText) {
            routeMessage = firstNotBlank(errorText, routeMessage);
        }
        return isRouteMissingMessage(routeMessage);
    }

    /**
     * 构造只确认网关可达的诚实降级结果。
     *
     * @return 未验证密钥和模型的可达结果
     */
    public static ProbeResult gatewayOnlyForUnavailableReadOnlyRoute() {
        ProbeResult result = ProbeResult.ok("仅网关可达");
        result.setDetail("代理未开放只读探测接口，未验证密钥或模型");
        return result;
    }

    /**
     * 判断是否为 HTTP 成功响应。
     *
     * @param response HTTP 响应
     * @return 是否成功
     */
    public static boolean isHttpSuccess(ProbeHttpResponse response) {
        return response.status() >= HTTP_OK_MIN && response.status() < HTTP_OK_MAX_EXCLUSIVE;
    }

    /**
     * 构造未被供应商契约识别的失败结果。
     *
     * @param response HTTP 响应
     * @return 失败结果
     */
    public static ProbeResult unexpected(ProbeHttpResponse response) {
        if (response.status() == HTTP_NOT_FOUND) {
            return ProbeResult.fail("接口地址不存在", detail(response));
        }
        return ProbeResult.fail("网关返回异常", detail(response));
    }

    /**
     * 解析 JSON 对象。
     *
     * @param body 响应体
     * @return JSON 对象；格式不符返回 null
     */
    public static JSONObject parseObject(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            Object parsed = JSON.parse(body);
            return parsed instanceof JSONObject object ? object : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析模型真实调用代码。
     *
     * @param model 模型配置
     * @return 真实模型代码
     */
    public static String resolveModelCode(AidAiModel model) {
        if (Objects.isNull(model)) {
            return null;
        }
        return StrUtil.isNotBlank(model.getRealModelCode()) ? model.getRealModelCode() : model.getModelCode();
    }

    /**
     * 生成不会命中真实业务任务的探测标识。
     *
     * @return 随机探测标识
     */
    public static String randomProbeId() {
        return "aid-probe-" + java.util.UUID.randomUUID();
    }

    /**
     * 生成符合纯数字任务标识格式的不存在任务 ID。
     *
     * @return 十九位随机任务标识
     */
    public static String randomNumericTaskId() {
        long value = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(1_000_000_000_000_000_000L, Long.MAX_VALUE);
        return Long.toString(value);
    }

    /**
     * 返回不含密钥的响应明细。
     *
     * @param response HTTP 响应
     * @return 响应明细
     */
    public static String detail(ProbeHttpResponse response) {
        return "HTTP " + response.status() + " | " + truncate(response.body());
    }

    private static boolean isRouteMissingMessage(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("route not found")
                || normalized.contains("no route")
                || normalized.contains("cannot get ")
                || normalized.contains("page not found");
    }

    private static boolean isPlainModelMissing(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("model")
                && (normalized.contains("not found")
                || normalized.contains("does not exist")
                || normalized.contains("not exist"));
    }

    private static boolean isStructuredAuthError(String body) {
        JSONObject root = parseObject(body);
        if (Objects.isNull(root)) {
            return false;
        }
        String code = firstNotBlank(root.getString("code"), root.getString("type"));
        Object errorValue = root.get("error");
        if (errorValue instanceof JSONObject error) {
            code = firstNotBlank(error.getString("code"), error.getString("type"), code);
        } else if (errorValue instanceof String errorText) {
            code = firstNotBlank(errorText, code);
        }
        if (StrUtil.isBlank(code)) {
            return false;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Objects.equals(normalized, "unauthorized")
                || Objects.equals(normalized, "forbidden")
                || Objects.equals(normalized, "invalid_api_key")
                || Objects.equals(normalized, "authentication_error")
                || Objects.equals(normalized, "invalidaccesskey")
                || Objects.equals(normalized, "signaturedoesnotmatch")
                || Objects.equals(normalized, "accessdenied");
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(StrUtil.nullToEmpty(value), StandardCharsets.UTF_8);
    }

    private static String truncate(String body) {
        if (StrUtil.isBlank(body)) {
            return "";
        }
        return body.length() > DETAIL_BODY_MAX_LEN ? body.substring(0, DETAIL_BODY_MAX_LEN) : body;
    }
}
