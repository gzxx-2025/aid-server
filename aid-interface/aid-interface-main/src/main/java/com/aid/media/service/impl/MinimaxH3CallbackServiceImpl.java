package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.MinimaxH3CallbackSupport;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.service.MinimaxH3CallbackService;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** MiniMax H3 回调只唤醒统一轮询，匿名端点不直接放大为上游查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinimaxH3CallbackServiceImpl implements MinimaxH3CallbackService {

    private final IAidMediaTaskService aidMediaTaskService;
    private final IAiModelConfigService aiModelConfigService;
    private final TaskDispatchService taskDispatchService;

    @Override
    public String handleCallback(String rawBody) {
        try {
            JsonNode root = ProviderResponseHelper.readTree(rawBody);
            String challenge = text(root, "challenge");
            if (StrUtil.isNotBlank(challenge)) {
                return challenge;
            }
            String providerTaskId = text(root == null ? null : root.path("task"), "id");
            if (StrUtil.isBlank(providerTaskId)) {
                providerTaskId = text(root, "task_id");
            }
            if (StrUtil.isBlank(providerTaskId)) {
                log.warn("MiniMax H3 callback missing task id");
                return null;
            }
            AidMediaTask task = findActiveTask(providerTaskId);
            if (task == null) {
                log.info("MiniMax H3 callback did not match an active task");
                return null;
            }
            AiModelConfigVo config = resolveModel(task);
            if (!isExpectedConfig(config)
                || StrUtil.isBlank(MinimaxH3CallbackSupport.resolveCallbackUrlForSubmission(config))) {
                log.warn("MiniMax H3 callback model configuration unavailable, taskId={}", task.getId());
                return null;
            }
            // 官方无签名：回调不能作为可信状态，也不能让匿名请求直接触发上游 GET。
            // 这里只做数据库 CAS 唤醒，权威查询、终态收口和用量结算仍由统一调度器完成。
            taskDispatchService.scheduleImmediatePollIfWaitingCallback(task);
        } catch (Exception ex) {
            log.error("MiniMax H3 callback processing failed; polling remains authoritative", ex);
        }
        return null;
    }

    private AidMediaTask findActiveTask(String providerTaskId) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidMediaTask::getId, AidMediaTask::getModelName, AidMediaTask::getProtocol,
                AidMediaTask::getProviderTaskId, AidMediaTask::getStatus, AidMediaTask::getUserId)
            .eq(AidMediaTask::getProviderTaskId, providerTaskId)
            .eq(AidMediaTask::getProtocol, MinimaxH3Constants.PROTOCOL_VIDEO)
            // Anonymous callbacks may wake a task exactly once. The scheduler performs
            // the WAIT_CALLBACK -> WAIT_POLL CAS; later notifications are handled by polling.
            .eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name())
            .orderByDesc(AidMediaTask::getId)
            .last("LIMIT 1");
        return aidMediaTaskService.getOne(wrapper, false);
    }

    private AiModelConfigVo resolveModel(AidMediaTask task) {
        try {
            return aiModelConfigService.selectByModelCodeForUser(task.getModelName(), task.getUserId());
        } catch (Exception ex) {
            log.warn("MiniMax H3 callback cannot resolve model, taskId={}, error={}",
                task.getId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    private boolean isExpectedConfig(AiModelConfigVo config) {
        return config != null
            && MinimaxH3Constants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(config.getProviderCode()))
            && MinimaxH3Constants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(config.getProtocol()))
            && MinimaxH3Constants.MODEL_CODES.contains(config.getModelCode());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return StrUtil.trimToNull(node.path(field).asText(null));
    }
}
