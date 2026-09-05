package com.aid.common.error;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.media.AidMediaTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/** 保存和读取任务终态的安全错误快照。 */
public final class TaskErrorSnapshot {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final int MAX_SNAPSHOT_LENGTH = 4000;

    private TaskErrorSnapshot() { }

    public static String write(TaskErrorResult error) {
        if (Objects.isNull(error)) {
            return null;
        }
        try {
            TaskErrorCode code = TaskErrorCode.valueOf(error.getErrorCode());
            return MAPPER.writeValueAsString(Map.of(
                    "errorCode", code.name(),
                    "userMessage", StrUtil.sub(StrUtil.blankToDefault(
                            error.getUserMessage(), code.getUserMessage()), 0, MAX_MESSAGE_LENGTH)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("任务错误快照无效", ex);
        }
    }

    public static String fromMessage(String message) {
        return StrUtil.isBlank(message) ? null : write(ErrorNormalizer.classifyByMessage(message));
    }

    /** 只解析服务端生成的独立快照字段，禁止用供应商原文调用本方法。 */
    public static TaskErrorResult read(String snapshot) {
        if (StrUtil.isBlank(snapshot) || snapshot.length() > MAX_SNAPSHOT_LENGTH) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(snapshot);
            TaskErrorCode code = TaskErrorCode.valueOf(node.path("errorCode").asText());
            TaskErrorResult error = TaskErrorResult.of(code);
            String message = node.path("userMessage").asText(null);
            if (StrUtil.isNotBlank(message) && !ErrorNormalizer.usesProtectedUserMessage(code)) {
                error.setUserMessage(StrUtil.sub(message, 0, MAX_MESSAGE_LENGTH));
            }
            return error;
        } catch (Exception ex) {
            // 旧数据或损坏快照仍可按原错误字段读取，不让详情接口失效。
            return null;
        }
    }

    public static TaskErrorResult resolve(String snapshot, String modelCode, String rawMessage) {
        TaskErrorResult error = read(snapshot);
        if (Objects.isNull(error)) {
            return ErrorNormalizer.classify(null, modelCode, -1, rawMessage);
        }
        error.setRawMessage(rawMessage);
        return error;
    }

    public static TaskErrorResult fromTask(AidExtractTask task) {
        return resolve(task.getErrorDetailJson(), task.getModelCode(), task.getErrorMessage());
    }

    public static TaskErrorResult fromTask(AidMediaTask task) {
        return resolve(task.getErrorDetailJson(), task.getModelName(), task.getErrorMessage());
    }
}
