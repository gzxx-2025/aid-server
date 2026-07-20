package com.aid.aid.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidExtractTaskBillingSnapshot;
import com.aid.aid.mapper.AidExtractTaskBillingSnapshotMapper;
import com.aid.aid.service.IAidExtractTaskBillingSnapshotService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 提取任务计费快照Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidExtractTaskBillingSnapshotServiceImpl
        extends ServiceImpl<AidExtractTaskBillingSnapshotMapper, AidExtractTaskBillingSnapshot>
        implements IAidExtractTaskBillingSnapshotService
{
    private static final String DEL_FLAG_NORMAL = "0";

    @Override
    public void saveOrUpdateSnapshot(AidExtractTask task, String snapshotStage, String snapshotJson)
    {
        if (Objects.isNull(task) || Objects.isNull(task.getId()) || StrUtil.isBlank(snapshotStage))
        {
            throw new ServiceException("快照任务异常");
        }
        if (StrUtil.isBlank(snapshotJson))
        {
            deleteSnapshot(task.getId(), snapshotStage);
            return;
        }

        AidExtractTaskBillingSnapshot existing = this.getOne(
                Wrappers.<AidExtractTaskBillingSnapshot>lambdaQuery()
                        .select(AidExtractTaskBillingSnapshot::getId)
                        .eq(AidExtractTaskBillingSnapshot::getTaskId, task.getId())
                        .eq(AidExtractTaskBillingSnapshot::getSnapshotStage, snapshotStage)
                        .last("limit 1"));

        AidExtractTaskBillingSnapshot snapshot = Objects.nonNull(existing)
                ? existing : new AidExtractTaskBillingSnapshot();
        snapshot.setTaskId(task.getId());
        snapshot.setProjectId(task.getProjectId());
        snapshot.setEpisodeId(task.getEpisodeId());
        snapshot.setUserId(task.getUserId());
        snapshot.setTaskType(task.getTaskType());
        snapshot.setSnapshotStage(snapshotStage);
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setSnapshotSize(snapshotJson.length());
        snapshot.setSnapshotHash(sha256(snapshotJson));
        snapshot.setDelFlag(DEL_FLAG_NORMAL);
        snapshot.setUpdateTime(DateUtils.getNowDate());
        snapshot.setUpdateBy(String.valueOf(task.getUserId()));
        if (Objects.isNull(snapshot.getId()))
        {
            snapshot.setCreateTime(DateUtils.getNowDate());
            snapshot.setCreateBy(String.valueOf(task.getUserId()));
            this.save(snapshot);
        }
        else
        {
            this.updateById(snapshot);
        }
    }

    @Override
    public String getSnapshotJson(Long taskId, String snapshotStage)
    {
        if (Objects.isNull(taskId) || StrUtil.isBlank(snapshotStage))
        {
            return null;
        }
        AidExtractTaskBillingSnapshot snapshot = this.getOne(
                Wrappers.<AidExtractTaskBillingSnapshot>lambdaQuery()
                        .select(AidExtractTaskBillingSnapshot::getId,
                                AidExtractTaskBillingSnapshot::getSnapshotJson)
                        .eq(AidExtractTaskBillingSnapshot::getTaskId, taskId)
                        .eq(AidExtractTaskBillingSnapshot::getSnapshotStage, snapshotStage)
                        .eq(AidExtractTaskBillingSnapshot::getDelFlag, DEL_FLAG_NORMAL)
                        .last("limit 1"));
        return Objects.isNull(snapshot) ? null : snapshot.getSnapshotJson();
    }

    @Override
    public void deleteSnapshot(Long taskId, String snapshotStage)
    {
        if (Objects.isNull(taskId) || StrUtil.isBlank(snapshotStage))
        {
            return;
        }
        this.remove(Wrappers.<AidExtractTaskBillingSnapshot>lambdaQuery()
                .eq(AidExtractTaskBillingSnapshot::getTaskId, taskId)
                .eq(AidExtractTaskBillingSnapshot::getSnapshotStage, snapshotStage));
    }

    private String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash)
            {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new ServiceException("快照哈希异常");
        }
    }
}
