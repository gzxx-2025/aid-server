package com.aid.common.error;

import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.common.error.rule.AidErrorLogService;
import com.aid.common.error.rule.ErrorRuleCache;
import com.aid.common.error.rule.ErrorRuleEngine;
import com.aid.common.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

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
        if (ex == null) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        if (ex instanceof InterruptedException) {
            return TaskErrorResult.of(TaskErrorCode.TASK_INTERRUPTED, ex.getMessage());
        }
        // ServiceException 优先识别本平台业务异常
        if (ex instanceof ServiceException) {
            String message = ex.getMessage();
            if (message != null && message.contains("余额不足")) {
                return TaskErrorResult.of(TaskErrorCode.USER_BALANCE_NOT_ENOUGH, message);
            }
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
        if (StringUtils.isBlank(rawMessage)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        ErrorNormalizer self = INSTANCE;
        if (self == null) {
            // 兜底：Spring 还未启动完成（极少见，比如 init 期异常）
            log.warn("[ErrorNormalizer] 静态门面尚未初始化，临时返回兜底错误码");
            return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, rawMessage);
        }
        return self.doNormalize(taskId, providerCode, modelCode, httpStatus, rawMessage, true);
    }

    /**
     * 仅试运行不写日志（管理后台规则测试器使用）。
     * 与 {@link #normalize(String, String, String, int, String)} 行为一致，但不会向 aid_error_log 写入样本。
     */
    public static TaskErrorResult dryRun(String providerCode, String modelCode,
                                         int httpStatus, String rawMessage) {
        if (StringUtils.isBlank(rawMessage)) {
            return TaskErrorResult.of(TaskErrorCode.UNKNOWN);
        }
        ErrorNormalizer self = INSTANCE;
        if (self == null) {
            return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, rawMessage);
        }
        return self.doNormalize(null, providerCode, modelCode, httpStatus, rawMessage, false);
    }
    private TaskErrorResult doNormalize(String taskId, String providerCode, String modelCode,
                                        int httpStatus, String rawMessage, boolean recordSample) {
        // 临时调试日志（上线前删除）：完整打印上游原始返回，便于定位无细节的错误
        log.error("【临时调试·上线删除】上游原始返回 -> taskId={}, providerCode={}, modelCode={}, httpStatus={}, rawMessage={}",
                taskId, providerCode, modelCode, httpStatus, rawMessage);
        List<AidProviderErrorRule> rules = errorRuleCache.findEffective(providerCode, modelCode);
        for (AidProviderErrorRule rule : rules) {
            if (errorRuleEngine.matches(rule, httpStatus, rawMessage)) {
                TaskErrorCode code = parseErrorCode(rule.getErrorCode());
                if (code == null) {
                    log.warn("[ErrorNormalizer] 规则错误码无效, ruleId={}, errorCode={}",
                            rule.getId(), rule.getErrorCode());
                    continue;
                }
                TaskErrorResult result = TaskErrorResult.of(code, rawMessage);
                if (StringUtils.isNotBlank(rule.getUserMessage())) {
                    result.setUserMessage(rule.getUserMessage());
                }
                if (recordSample) {
                    // 异步记录命中样本，便于命中统计与运营观察
                    errorLogService.recordHit(providerCode, modelCode, taskId,
                            httpStatus, rawMessage, rule.getId(), code.name());
                }
                return result;
            }
        }
        log.info("[ErrorNormalizer] 未识别错误样本, providerCode={}, rawMessage={}",
                providerCode,
                rawMessage.length() > 200 ? rawMessage.substring(0, 200) : rawMessage);
        if (recordSample) {
            errorLogService.recordMiss(providerCode, modelCode, taskId,
                    httpStatus, rawMessage, TaskErrorCode.AI_GENERATION_FAILED.name());
        }
        return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, rawMessage);
    }

    private TaskErrorCode parseErrorCode(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        try {
            return TaskErrorCode.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
