package com.aid.rps.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.rps.dto.ExtractTaskMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * MQ 派发执行器：调度放行后构建 {@link ExtractTaskMessage} 发 RocketMQ，由 AssetExtractConsumer 消费。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class MqTaskDispatchExecutor implements TaskDispatchExecutor
{
    public static final String MODE = "MQ";

    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG_EXTRACT = "extract";
    private static final String MQ_TAG_IMAGE_UPSCALE = "image_upscale";
    private static final String TASK_TYPE_IMAGE_UPSCALE = "image_upscale";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MqTemplateFactory mqTemplateFactory;

    @Override
    public String dispatchMode()
    {
        return MODE;
    }

    @Override
    public boolean dispatch(QueuedTaskContext ctx)
    {
        Long taskId = ctx.getTaskId();
        try
        {
            ExtractTaskMessage message = ExtractTaskMessage.builder()
                    .taskId(taskId)
                    .projectId(ctx.getProjectId())
                    .episodeId(ctx.getEpisodeId())
                    .userId(ctx.getUserId())
                    .modelCode(ctx.getModelCode())
                    .taskType(ctx.getTaskType())
                    .build();
            String body = OBJECT_MAPPER.writeValueAsString(message);
            String tag = TASK_TYPE_IMAGE_UPSCALE.equals(ctx.getTaskType())
                    ? MQ_TAG_IMAGE_UPSCALE : MQ_TAG_EXTRACT;
            MqResult result = mqTemplateFactory.send(MQ_TOPIC, tag, String.valueOf(taskId), body);
            log.info("排队放行-MQ消息已发送: taskId={}, taskType={}, msgId={}",
                    taskId, ctx.getTaskType(), result.getMessageId());
            return true;
        }
        catch (Exception e)
        {
            log.error("排队放行-MQ发送失败: taskId={}", taskId, e);
            return false;
        }
    }
}
