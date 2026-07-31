package com.aid.common.error;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.common.error.rule.AidErrorLogService;
import com.aid.common.error.rule.ErrorRuleCache;
import com.aid.common.error.rule.ErrorRuleEngine;
import com.aid.common.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 统一错误归一化器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorNormalizer {

    private final ErrorRuleCache errorRuleCache;
    private final ErrorRuleEngine errorRuleEngine;
    private final AidErrorLogService errorLogService;
    private final ErrorProviderResolver errorProviderResolver;

    /** 静态门面：方便业务在 static 上下文调用。Spring 启动后由 {@link #init()} 注入。 */
    private static ErrorNormalizer INSTANCE;

    @PostConstruct
    public void init() {
        INSTANCE = this;
        log.info("[ErrorNormalizer] 静态门面已就绪");
    }
    /**
     * 从异常归一化（无 provider 上下文）。
     * 业务调用方很多，保持原签名兼容。
     */
    public static TaskErrorResult normalize(Throwable ex) {
        if (Objects.isNull(ex)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        if (ex instanceof InterruptedException) {
            return TaskErrorResult.of(TaskErrorCode.TASK_INTERRUPTED, ex.getMessage());
        }
        ServiceException balanceException = findUserBalanceException(ex);
        if (Objects.nonNull(balanceException)) {
            return TaskErrorResult.of(TaskErrorCode.USER_BALANCE_NOT_ENOUGH,
                    balanceException.getMessage());
        }
        if (ex instanceof ServiceException serviceException) {
            String rawMessage = StrUtil.blankToDefault(
                    serviceException.getDetailMessage(), serviceException.getMessage());
            return normalizeByMessage(rawMessage);
        }
        return normalizeByMessage(ex.getMessage());
    }

    /**
     * 从原始错误文案归一化（无 provider 上下文，走全局规则）。
     */
    public static TaskErrorResult normalizeByMessage(String rawMessage) {
        return normalize(null, null, null, -1, rawMessage);
    }

    /**
     * 只做展示分类，不记录错误样本。
     * 任务查询、列表和 SSE 断线补发统一使用本入口，避免每次读取都重复累计错误日志。
     */
    public static TaskErrorResult classify(String providerCode, String modelCode,
                                           int httpStatus, String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        ErrorNormalizer self = INSTANCE;
        if (Objects.isNull(self)) {
            return classifyFallback(rawMessage);
        }
        return self.doNormalize(null, providerCode, modelCode, httpStatus, rawMessage, false);
    }

    /**
     * 无厂商上下文的只读分类入口。
     */
    public static TaskErrorResult classifyByMessage(String rawMessage) {
        return classify(null, null, -1, rawMessage);
    }

    /**
     * 主入口：带 provider/model 上下文的归一化。
     *
     * @param taskId        关联任务 ID（可为 null，仅用于错误样本日志）
     * @param providerCode  厂商编码（可为 null，走全局规则）
     * @param modelCode     模型编码（可为 null，走厂商级 + 全局规则）
     * @param httpStatus    HTTP 状态码（&lt;=0 表示无）
     * @param rawMessage    上游原始错误体
     */
    public static TaskErrorResult normalize(String taskId, String providerCode, String modelCode,
                                            int httpStatus, String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        ErrorNormalizer self = INSTANCE;
        if (Objects.isNull(self)) {
            // 兜底：Spring 还未启动完成（极少见，比如 init 期异常）
            log.warn("[ErrorNormalizer] 静态门面尚未初始化，临时返回兜底错误码");
            return classifyFallback(rawMessage);
        }
        return self.doNormalize(taskId, providerCode, modelCode, httpStatus, rawMessage, true);
    }

    /**
     * 仅试运行不写日志（管理后台规则测试器使用）。
     * 与 {@link #normalize(String, String, String, int, String)} 行为一致，但不会向 aid_error_log 写入样本。
     */
    public static TaskErrorResult dryRun(String providerCode, String modelCode,
                                         int httpStatus, String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        ErrorNormalizer self = INSTANCE;
        if (Objects.isNull(self)) {
            return classifyFallback(rawMessage);
        }
        return self.doNormalize(null, providerCode, modelCode, httpStatus, rawMessage, false);
    }
    private TaskErrorResult doNormalize(String taskId, String providerCode, String modelCode,
                                        int httpStatus, String rawMessage, boolean recordSample) {
        String effectiveProviderCode = StrUtil.blankToDefault(
                providerCode, errorProviderResolver.resolve(modelCode));
        List<AidProviderErrorRule> rules = errorRuleCache.findEffective(effectiveProviderCode, modelCode);
        for (AidProviderErrorRule rule : rules) {
            if (errorRuleEngine.matches(rule, httpStatus, rawMessage)) {
                TaskErrorCode code = parseErrorCode(rule.getErrorCode());
                if (Objects.isNull(code)) {
                    log.warn("[ErrorNormalizer] 规则错误码无效, ruleId={}, errorCode={}",
                            rule.getId(), rule.getErrorCode());
                    continue;
                }
                TaskErrorResult result = TaskErrorResult.of(code, rawMessage);
                // 供应商运营与技术故障统一使用系统安全文案，禁止数据库规则重新暴露余额、密钥、账号或网关细节。
                if (StrUtil.isNotBlank(rule.getUserMessage()) && !usesProtectedUserMessage(code)) {
                    result.setUserMessage(rule.getUserMessage());
                }
                if (recordSample) {
                    // 异步记录命中样本，便于命中统计与运营观察
                    errorLogService.recordHit(effectiveProviderCode, modelCode, taskId,
                            httpStatus, rawMessage, rule.getId(), code.name());
                }
                return result;
            }
        }
        TaskErrorResult fallbackResult = classifyFallback(rawMessage);
        if (recordSample) {
            log.info("[ErrorNormalizer] 未识别错误样本, providerCode={}, messageLen={}",
                    effectiveProviderCode, rawMessage.length());
            errorLogService.recordMiss(effectiveProviderCode, modelCode, taskId,
                    httpStatus, rawMessage, fallbackResult.getErrorCode());
        }
        return fallbackResult;
    }

    /**
     * 数据库规则漏配或缓存不可用时的高频错误兜底。
     * 只放跨厂商、语义明确的样本，厂商差异仍由 aid_provider_error_rule 管理。
     */
    static TaskErrorResult classifyFallback(String rawMessage) {
        String lower = StrUtil.nullToEmpty(rawMessage).toLowerCase();
        if (containsAny(lower, "inputimagesensit", "contain real person", "contains real person",
                "may contain real person", "参考图可能包含真人")) {
            return TaskErrorResult.of(TaskErrorCode.REAL_PERSON_RESTRICTED, rawMessage);
        }
        if (containsAny(lower, "no available compatible accounts", "no compatible account",
                "当前模型暂时不可用")) {
            return TaskErrorResult.of(TaskErrorCode.MODEL_ACCOUNT_UNAVAILABLE, rawMessage);
        }
        if (containsAny(lower, "image queue is full", "service busy", "no available server",
                "system memory overloaded", "server overloaded", "模型任务较多")) {
            return TaskErrorResult.of(TaskErrorCode.PROVIDER_BUSY, rawMessage);
        }
        if (containsAny(lower, "content_policy_violation", "unable to generate this content",
                "blocked by safety", "sensitive content", "captcha", "内容未通过审核",
                "验证码图片")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_CONTENT_FILTERED, rawMessage);
        }
        if (containsAny(lower, "insufficient credits", "free quota exhausted",
                "quota exhausted", "quota exceeded", "insufficient balance",
                "balance insufficient", "account balance is insufficient",
                "free tier of the model has been exhausted", "credit balance is insufficient",
                "模型服务额度不足", "模型余额不足", "上游账户余额不足", "供应商余额不足")
                || Objects.equals(lower.trim(), "余额不足")) {
            return TaskErrorResult.of(TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED, rawMessage);
        }
        if (containsAny(lower, "invalid api key", "incorrect api key", "api key not valid",
                "unauthorized", "authentication failed", "invalid credential",
                "access denied", "signaturedoesnotmatch", "invalidaccesskeyid")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_AUTH_INVALID, rawMessage);
        }
        if (containsAny(lower, "rate limit exceeded", "too many requests", "request limit exceeded",
                "throttling", "ratelimitexceeded")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_RATE_LIMITED, rawMessage);
        }
        if (containsAny(lower, "invalid value for 'size'", "invalid value for `size`",
                "unsupported image size", "image dimensions", "maximum edge length",
                "both edges must be multiples of", "long edge to short edge ratio",
                "total pixels must be", "mask and image must be the same size",
                "清晰度不支持", "画面比例不支持", "图片尺寸不符合要求", "分辨率不支持")) {
            return TaskErrorResult.of(TaskErrorCode.USER_IMAGE_RESOLUTION_INVALID, rawMessage);
        }
        if (containsAny(lower, "unsupported image format", "unsupported file format",
                "invalid image format", "image format is not supported",
                "mask must contain an alpha channel", "文件格式不支持", "图片格式不支持")) {
            return TaskErrorResult.of(TaskErrorCode.USER_FILE_FORMAT_INVALID, rawMessage);
        }
        if (containsAny(lower, "payload too large", "request entity too large",
                "image is too large", "image too large", "file too large",
                "maximum payload size", "less than 50mb", "less than 50 mb",
                "文件过大", "图片过大")) {
            return TaskErrorResult.of(TaskErrorCode.USER_FILE_TOO_LARGE, rawMessage);
        }
        if (containsAny(lower, "image is too blurry", "image is unclear",
                "clear enough for a human", "watermarks or logos", "watermark or logo",
                "图片无法识别", "图片不清晰", "图片含水印")) {
            return TaskErrorResult.of(TaskErrorCode.USER_IMAGE_QUALITY_INVALID, rawMessage);
        }
        if (containsAny(lower, "start_image parameter is required", "multiple images, but mode was omitted",
                "current model does not support", "transparent background is not supported",
                "transparent backgrounds aren't supported", "input_fidelity is not supported",
                "input_fidelity cannot be set", "invalid value for 'quality'",
                "invalid value for `quality`", "invalid value for 'output_compression'",
                "invalid value for `output_compression`", "当前参数不受模型支持",
                "当前模型不支持")) {
            return TaskErrorResult.of(TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE, rawMessage);
        }
        if (containsAny(lower, "private ip address not allowed", "must be a public http",
                "download input image failed", "参考文件地址不可访问")) {
            return TaskErrorResult.of(TaskErrorCode.USER_FILE_DOWNLOAD_FAILED, rawMessage);
        }
        if (containsAny(lower, "上游未返回任务标识、url或文本结果", "模型返回为空", "合成为空",
                "no image url", "no video url", "模型未返回有效结果")) {
            return TaskErrorResult.of(TaskErrorCode.RESULT_INVALID, rawMessage);
        }
        if (containsAny(lower, "输出格式异常", "模型返回异常", "拆分内容异常", "拆分内容缺失",
                "拆分顺序异常", "拆分格式异常")) {
            return TaskErrorResult.of(TaskErrorCode.RESULT_FORMAT_INVALID, rawMessage);
        }
        if (containsAny(lower, "unknownhostexception", "connection reset", "unexpected end of file",
                "could not connect", "connection refused", "模型网络异常")
                || lower.trim().startsWith("<!doctype html")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_NETWORK_ERROR, rawMessage);
        }
        if (containsAny(lower, "context deadline exceeded", "read timed out", "connect timed out",
                "request timeout", "任务超时")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_TIMEOUT, rawMessage);
        }
        if (containsAny(lower, "comfyui is not reachable", "image generation is not enabled",
                "model service is not open")) {
            return TaskErrorResult.of(TaskErrorCode.UPSTREAM_SERVICE_NOT_OPEN, rawMessage);
        }
        if (containsAny(lower, "error updating database", "error querying database",
                "could not open jdbc connection", "data truncation", "sqlsyntaxerrorexception",
                "任务数据处理异常")) {
            return TaskErrorResult.of(TaskErrorCode.PERSIST_FAILED, rawMessage);
        }
        if (containsAny(lower, "oss 持久化失败", "oss persistence failed",
                "文件存储失败")) {
            return TaskErrorResult.of(TaskErrorCode.OSS_PERSIST_FAILED, rawMessage);
        }
        if (containsAny(lower, "服务重启中断", "interrupted", "任务被中断")) {
            return TaskErrorResult.of(TaskErrorCode.TASK_INTERRUPTED, rawMessage);
        }
        return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, rawMessage);
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 沿异常链查找统一计费模块写入的用户余额标记，兼容异步执行器的异常包装。
     */
    private static ServiceException findUserBalanceException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (Objects.nonNull(current) && depth < 10) {
            if (current instanceof ServiceException serviceException
                    && Objects.equals(serviceException.getDetailMessage(),
                    TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name())) {
                return serviceException;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    /**
     * 这些错误只供服务端定位和前端机器判断，面向用户的文案必须由代码统一控制。
     */
    static boolean usesProtectedUserMessage(TaskErrorCode code) {
        return switch (code) {
            case MERCHANT_QUOTA_EXHAUSTED,
                    PROVIDER_FREE_TIER_EXHAUSTED,
                    PROVIDER_QUOTA_EXHAUSTED,
                    UPSTREAM_AUTH_INVALID,
                    UPSTREAM_SERVICE_NOT_OPEN,
                    MODEL_ACCOUNT_UNAVAILABLE,
                    PROVIDER_BUSY,
                    UPSTREAM_RATE_LIMITED,
                    UPSTREAM_TIMEOUT,
                    UPSTREAM_NETWORK_ERROR,
                    UPSTREAM_SERVER_ERROR,
                    UPSTREAM_BAD_REQUEST -> true;
            default -> false;
        };
    }

    private TaskErrorCode parseErrorCode(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        try {
            return TaskErrorCode.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
