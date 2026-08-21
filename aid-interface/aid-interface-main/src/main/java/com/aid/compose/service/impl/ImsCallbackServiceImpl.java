package com.aid.compose.service.impl;

import java.net.URI;
import java.util.Date;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.service.ImsCallbackService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.impl.AliyunImsVideoProviderClient;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 阿里云 IMS 回调仅用于唤醒，防伪与终态确认由官方查询接口完成。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImsCallbackServiceImpl implements ImsCallbackService
{
    private final AidMediaTaskMapper taskMapper;
    private final AliyunImsVideoProviderClient providerClient;
    private final TaskCompletionService completionService;
    private final TaskDispatchService taskDispatchService;

    @Override
    public void handle(String jobId, String eventMessage, String userData)
    {
        try
        {
            String resolvedJobId = firstNotBlank(jobId, readJsonValue(eventMessage, "JobId", "jobId"));
            String callbackUserData = firstNotBlank(userData,
                    readJsonValue(eventMessage, "UserData", "userData"));
            AidMediaTask task = null;
            if (StrUtil.isNotBlank(resolvedJobId))
            {
                task = taskMapper.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getProtocol, MpsConfigManager.MODE_ALIYUN_IMS)
                        .eq(AidMediaTask::getProviderTaskId, resolvedJobId)
                        .last("LIMIT 1"));
            }
            if (task == null)
            {
                String taskId = firstNotBlank(readJsonValue(callbackUserData, "TaskId", "taskId"),
                        readJsonValue(eventMessage, "TaskId", "taskId"));
                if (StrUtil.isNotBlank(taskId))
                {
                    task = taskMapper.selectById(Long.valueOf(taskId));
                }
            }
            if (task == null)
            {
                log.warn("IMS回调未匹配任务, jobId={}", resolvedJobId);
                return;
            }
            if (!MpsConfigManager.MODE_ALIYUN_IMS.equals(task.getProtocol()))
            {
                log.warn("IMS回调任务协议不匹配, taskId={}, protocol={}", task.getId(), task.getProtocol());
                return;
            }
            ProviderTaskResult result = null;
            if (StrUtil.isBlank(task.getProviderTaskId()))
            {
                result = providerClient.query(null, resolvedJobId);
                if (!isSafeRecoveredProviderTask(task, resolvedJobId, callbackUserData, result)
                        || !bindRecoveredProviderTask(task, resolvedJobId, callbackUserData))
                {
                    log.warn("IMS回调无法认领待确认任务, taskId={}, jobId={}", task.getId(), resolvedJobId);
                    return;
                }
            }
            if (result == null)
            {
                result = providerClient.query(null, task.getProviderTaskId());
            }
            if (Boolean.TRUE.equals(result.getQuerySuccessful())
                    && Boolean.TRUE.equals(result.getTerminalConfirmed()))
            {
                completionService.completeTask(task.getId(), result);
                return;
            }
            // 回调已到但上游查询尚未显现终态：立即唤醒轮询，不采信回调正文直接判成败。
            taskMapper.update(null, Wrappers.<AidMediaTask>lambdaUpdate()
                    .eq(AidMediaTask::getId, task.getId())
                    .set(AidMediaTask::getNextPollTime, new Date())
                    .set(AidMediaTask::getUpdateTime, new Date()));
        }
        catch (Exception e)
        {
            // 回调异常不关单，补偿轮询仍会继续。
            log.warn("IMS回调处理异常, error={}", e.getMessage());
        }
    }

    /**
     * 回调入口不直接受信。仅当官方查询明确成功，且成片对象路径与任务冻结计划完全一致时，
     * 才允许用回调中的 JobId 恢复“提交结果待确认”的任务。
     */
    private boolean isSafeRecoveredProviderTask(AidMediaTask task, String jobId, String userData,
                                                ProviderTaskResult result)
    {
        String callbackTaskId = readJsonValue(userData, "TaskId", "taskId");
        if (!MediaTaskStatus.PENDING.name().equals(task.getStatus())
                || StrUtil.isBlank(jobId)
                || !String.valueOf(task.getId()).equals(callbackTaskId))
        {
            return false;
        }
        if (result == null
                || !Boolean.TRUE.equals(result.getQuerySuccessful())
                || !Boolean.TRUE.equals(result.getTerminalConfirmed())
                || !(MediaTaskStatus.SUCCEEDED.name().equals(result.getStatus())
                || MediaTaskStatus.FAILED.name().equals(result.getStatus())))
        {
            return false;
        }
        // 回调 UserData 可以伪造；必须再与 GetMediaProducingJob 官方响应中保存的 UserData 对上。
        String officialTaskId = readOfficialUserDataTaskId(result.getRawResponse());
        if (!String.valueOf(task.getId()).equals(officialTaskId))
        {
            return false;
        }
        // 官方明确失败时没有可校验的成片 URL，但 UserData 已能证明归属，应立即按上游失败收口。
        if (MediaTaskStatus.FAILED.name().equals(result.getStatus()))
        {
            return true;
        }
        if (StrUtil.isBlank(result.getResultUrl()))
        {
            return false;
        }
        String expectedObjectPath = readExpectedOutputObjectPath(task.getRequestJson());
        try
        {
            return StrUtil.isNotBlank(expectedObjectPath)
                    && normalizeObjectPath(expectedObjectPath)
                    .equals(normalizeObjectPath(URI.create(result.getResultUrl()).getPath()));
        }
        catch (Exception e)
        {
            log.warn("IMS回调恢复任务输出路径校验失败, taskId={}, jobId={}", task.getId(), jobId);
            return false;
        }
    }

    private String readOfficialUserDataTaskId(String rawResponse)
    {
        if (StrUtil.isBlank(rawResponse))
        {
            return null;
        }
        try
        {
            JSONObject response = JSON.parseObject(rawResponse);
            JSONObject job = response.getJSONObject("MediaProducingJob");
            if (job == null)
            {
                job = response.getJSONObject("mediaProducingJob");
            }
            String officialUserData = job == null ? null
                    : firstNotBlank(job.getString("UserData"), job.getString("userData"));
            return readJsonValue(officialUserData, "TaskId", "taskId");
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private boolean bindRecoveredProviderTask(AidMediaTask task, String jobId, String userData)
    {
        String callbackTaskId = readJsonValue(userData, "TaskId", "taskId");
        if (!MediaTaskStatus.PENDING.name().equals(task.getStatus())
                || StrUtil.isBlank(jobId)
                || !String.valueOf(task.getId()).equals(callbackTaskId))
        {
            return false;
        }
        task.setProviderTaskId(jobId);
        taskDispatchService.initComposeDispatchSchedule(task);
        int updated = taskMapper.update(null, Wrappers.<AidMediaTask>lambdaUpdate()
                .eq(AidMediaTask::getId, task.getId())
                .eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name())
                .and(w -> w.isNull(AidMediaTask::getProviderTaskId)
                        .or().eq(AidMediaTask::getProviderTaskId, ""))
                .set(AidMediaTask::getProviderTaskId, jobId)
                .set(AidMediaTask::getStatus, task.getStatus())
                .set(AidMediaTask::getDispatchMode, task.getDispatchMode())
                .set(AidMediaTask::getScheduleSnapshotJson, task.getScheduleSnapshotJson())
                .set(AidMediaTask::getUpstreamAcceptTime, task.getUpstreamAcceptTime())
                .set(AidMediaTask::getLastProgressTime, task.getLastProgressTime())
                .set(AidMediaTask::getCallbackDeadline, task.getCallbackDeadline())
                .set(AidMediaTask::getNextPollTime, task.getNextPollTime())
                .set(AidMediaTask::getUpdateTime, new Date()));
        if (updated == 1)
        {
            log.info("IMS回调已恢复提交任务, taskId={}, jobId={}", task.getId(), jobId);
            return true;
        }
        return false;
    }

    private String readJsonValue(String raw, String... keys)
    {
        if (StrUtil.isBlank(raw))
        {
            return null;
        }
        try
        {
            JSONObject object = JSON.parseObject(raw);
            for (String key : keys)
            {
                String value = object.getString(key);
                if (StrUtil.isNotBlank(value))
                {
                    return value;
                }
            }
        }
        catch (Exception ignored)
        {
            // 非 JSON 的 UserData/EventMessage 由其它定位字段兜底。
        }
        return null;
    }

    private String readExpectedOutputObjectPath(String requestJson)
    {
        if (StrUtil.isBlank(requestJson))
        {
            return null;
        }
        try
        {
            JSONObject request = JSON.parseObject(requestJson);
            JSONObject plan = request == null ? null : request.getJSONObject("composePlan");
            return plan == null ? null : plan.getString("outputObjectPath");
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String normalizeObjectPath(String value)
    {
        String path = StrUtil.blankToDefault(value, "").trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private String firstNotBlank(String first, String second)
    {
        return StrUtil.isNotBlank(first) ? first : second;
    }
}
