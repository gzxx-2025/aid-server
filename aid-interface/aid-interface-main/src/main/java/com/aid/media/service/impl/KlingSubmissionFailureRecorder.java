package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.common.exception.ServiceException;
import com.aid.media.constants.KlingConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 可灵创建阶段失败记录器；记录失败不得影响媒体任务退款和终态落库。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlingSubmissionFailureRecorder {

    private final KlingTerminalFailureRecorder failureRecorder;

    /**
     * @return true 表示这是已识别的可灵上游创建拒绝，调用方可按业务拒绝而非系统异常记录日志
     */
    public boolean record(AidMediaTask task, Exception exception) {
        if (task == null
            || !KlingConstants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(task.getProtocol()))
            || !(exception instanceof ServiceException serviceException)
            || serviceException.getCode() == null
            || StrUtil.isBlank(serviceException.getDetailMessage())) {
            return false;
        }
        try {
            failureRecorder.record(task.getId(), task.getModelName(), serviceException.getCode(),
                serviceException.getDetailMessage());
        } catch (Exception recordException) {
            log.warn("Kling submission failure sample record ignored, taskId={}, errorType={}",
                task.getId(), recordException.getClass().getSimpleName());
        }
        return true;
    }
}
