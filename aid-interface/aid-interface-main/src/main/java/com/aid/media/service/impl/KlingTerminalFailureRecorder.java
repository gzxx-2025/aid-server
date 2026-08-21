package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.error.ErrorNormalizer;
import com.aid.media.constants.KlingConstants;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 可灵终态失败样本记录器；记录失败不得影响媒体任务终态收口。 */
@Slf4j
@Component
public class KlingTerminalFailureRecorder {

    public void record(Long taskId, String modelName, String rawErrorMessage) {
        record(taskId, modelName, -1, rawErrorMessage);
    }

    /** 创建阶段失败时保留 HTTP 状态，供错误规则命中和后台排障。 */
    public void record(Long taskId, String modelName, int httpStatus, String rawErrorMessage) {
        if (StrUtil.isBlank(rawErrorMessage)) {
            return;
        }
        try {
            String sanitized = MediaTaskPayloadSanitizer.sanitizeForStorage(rawErrorMessage);
            if (StrUtil.isNotBlank(sanitized)) {
                ErrorNormalizer.normalize(taskId == null ? null : String.valueOf(taskId),
                    KlingConstants.PROVIDER_CODE, modelName, httpStatus, sanitized);
            }
        } catch (Exception ex) {
            log.warn("Kling terminal failure sample record ignored, taskId={}, errorType={}",
                taskId, ex.getClass().getSimpleName());
        }
    }
}
