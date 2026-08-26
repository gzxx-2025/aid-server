package com.aid.rps.queue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidExtractTask;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 清理已取消批量父任务持有的业务运行标记。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchParentResourceCleaner
{
    private static final String LIP_SYNC_TASK_TYPE = "storyboard_lip_sync_generate";
    private static final String LIP_SYNC_RUNNING_PREFIX = "storyboard:lip_sync:running:";
    private static final String RUNNING_HOLDER_TASK_PREFIX = "task:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public void releaseCancelledResources(AidExtractTask task)
    {
        if (task == null || task.getId() == null || !LIP_SYNC_TASK_TYPE.equals(task.getTaskType()))
        {
            return;
        }
        Set<Long> storyboardIds = new LinkedHashSet<>();
        collectStoryboardIds(task.getResultData(), "items", storyboardIds);
        collectStoryboardIds(task.getInputSnapshot(), "storyboardIds", storyboardIds);
        String holder = RUNNING_HOLDER_TASK_PREFIX + task.getId();
        for (Long storyboardId : storyboardIds)
        {
            try
            {
                stringRedisTemplate.execute(RELEASE_SCRIPT,
                        List.of(LIP_SYNC_RUNNING_PREFIX + storyboardId), holder);
            }
            catch (Exception ex)
            {
                log.warn("取消后清理对口型标记异常: taskId={}, storyboardId={}, err={}",
                        task.getId(), storyboardId, ex.getMessage());
            }
        }
    }

    private void collectStoryboardIds(String json, String arrayName, Set<Long> target)
    {
        if (StrUtil.isBlank(json))
        {
            return;
        }
        try
        {
            JSONObject root = JSON.parseObject(json);
            JSONArray values = root.getJSONArray(arrayName);
            if (values == null)
            {
                return;
            }
            for (Object value : values)
            {
                Long storyboardId;
                if (value instanceof Number number)
                {
                    storyboardId = number.longValue();
                }
                else
                {
                    JSONObject item = value instanceof JSONObject object
                            ? object : JSON.parseObject(JSON.toJSONString(value));
                    storyboardId = item.getLong("storyboardId");
                }
                if (Objects.nonNull(storyboardId))
                {
                    target.add(storyboardId);
                }
            }
        }
        catch (Exception ex)
        {
            log.warn("取消任务资源快照解析失败: field={}, err={}", arrayName, ex.getMessage());
        }
    }
}
