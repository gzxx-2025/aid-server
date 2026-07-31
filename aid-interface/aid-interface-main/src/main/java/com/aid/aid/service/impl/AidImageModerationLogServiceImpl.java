package com.aid.aid.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidImageModerationLog;
import com.aid.aid.mapper.AidImageModerationLogMapper;
import com.aid.aid.service.IAidImageModerationLogService;
import com.aid.common.moderation.ModerationDecision;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.common.moderation.properties.ImageModerationProperties;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片内容审核日志Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AidImageModerationLogServiceImpl implements IAidImageModerationLogService
{
    /**
     * 无登录上下文时的创建者标识
     */
    private static final String SYSTEM_OPERATOR = "system";

    /**
     * 默认日志保留天数（配置缺失/非法时兜底）
     */
    private static final int DEFAULT_RETENTION_DAYS = 90;

    /**
     * 单批删除上限：避免大表单次删除锁表过久
     */
    private static final int CLEAN_BATCH_SIZE = 1000;

    /**
     * 审查日志 Mapper
     */
    private final AidImageModerationLogMapper aidImageModerationLogMapper;

    /**
     * 图片审查配置管理器（用于读取 logPassed 开关）
     */
    private final ImageModerationConfigManager imageModerationConfigManager;

    @Override
    public void record(AidImageModerationLog log)
    {
        // 入参为空直接跳过
        if (Objects.isNull(log))
        {
            return;
        }

        try
        {
            // 判断是否需要写库：命中 BLOCK/REVIEW/ERROR 必写，PASS 仅在 logPassed=true 时写
            if (!shouldRecord(log.getStatus()))
            {
                return;
            }

            // 填充创建时间与创建者
            log.setCreateTime(new Date());
            log.setCreateBy(currentOperator());

            aidImageModerationLogMapper.insert(log);
        }
        catch (Exception e)
        {
            // 写库失败仅记录日志，不向上抛出，避免影响上传主链路
            AidImageModerationLogServiceImpl.log.error("写入图片审查日志失败, status={}, userId={}, error={}",
                    log.getStatus(), log.getUserId(), e.getMessage(), e);
        }
    }

    @Override
    public int cleanExpired()
    {
        // 读取保留天数（配置缺失/非法兜底 90 天）
        ImageModerationProperties props = imageModerationConfigManager.getProperties();
        int days = (Objects.isNull(props) || props.getLogRetentionDays() <= 0)
                ? DEFAULT_RETENTION_DAYS : props.getLogRetentionDays();
        // 阈值：早于该时间的记录视为过期
        Date threshold = new Date(System.currentTimeMillis() - (long) days * 24L * 60L * 60L * 1000L);

        int total = 0;
        try
        {
            // 分批删除，避免大表单次删除锁表过久
            while (true)
            {
                List<AidImageModerationLog> batch = aidImageModerationLogMapper.selectList(
                        Wrappers.<AidImageModerationLog>lambdaQuery()
                                .select(AidImageModerationLog::getId)
                                .lt(AidImageModerationLog::getCreateTime, threshold)
                                .last("LIMIT " + CLEAN_BATCH_SIZE));
                if (CollectionUtil.isEmpty(batch))
                {
                    break;
                }
                List<Long> ids = batch.stream().map(AidImageModerationLog::getId).collect(Collectors.toList());
                aidImageModerationLogMapper.deleteBatchIds(ids);
                total += ids.size();
                // 不足一批说明已删完
                if (ids.size() < CLEAN_BATCH_SIZE)
                {
                    break;
                }
            }
        }
        catch (Exception e)
        {
            // 清理失败仅记日志，不影响业务
            log.error("清理过期图片审查日志失败, 保留天数={}, 已删={}", days, total, e);
        }
        return total;
    }

    /**
     * 判断给定状态是否需要写库
     * @param status 审查状态
     * @return true=需要写库
     */
    private boolean shouldRecord(String status)
    {
        // BLOCK/REVIEW/ERROR 一律记录
        if (StrUtil.equalsAnyIgnoreCase(status,
                ModerationDecision.BLOCK.name(),
                ModerationDecision.REVIEW.name(),
                ModerationDecision.ERROR.name()))
        {
            return true;
        }
        // PASS 仅在配置 logPassed=true 时记录
        if (StrUtil.equalsIgnoreCase(status, ModerationDecision.PASS.name()))
        {
            ImageModerationProperties props = imageModerationConfigManager.getProperties();
            return Objects.nonNull(props) && props.isLogPassed();
        }
        // 其它未知状态不记录
        return false;
    }

    /**
     * 获取当前操作者：无登录上下文时回退为系统标识
     *
     * @return 操作者标识
     */
    private String currentOperator()
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return StrUtil.isBlank(username) ? SYSTEM_OPERATOR : username;
        }
        catch (Exception e)
        {
            // 无登录上下文（如异步线程/系统任务），使用系统标识
            return SYSTEM_OPERATOR;
        }
    }
}
