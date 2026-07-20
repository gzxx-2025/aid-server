package com.aid.aid.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidExtractTaskBillingSnapshot;

/**
 * 提取任务计费快照Service接口
 *
 * @author 视觉AID
 */
public interface IAidExtractTaskBillingSnapshotService extends IService<AidExtractTaskBillingSnapshot>
{
    /**
     * 保存或更新任务计费快照。
     *
     * @param task 提取任务
     * @param snapshotStage 快照阶段
     * @param snapshotJson 快照JSON
     */
    void saveOrUpdateSnapshot(AidExtractTask task, String snapshotStage, String snapshotJson);

    /**
     * 查询任务计费快照JSON。
     *
     * @param taskId 提取任务ID
     * @param snapshotStage 快照阶段
     * @return 快照JSON
     */
    String getSnapshotJson(Long taskId, String snapshotStage);

    /**
     * 删除任务指定阶段快照。
     *
     * @param taskId 提取任务ID
     * @param snapshotStage 快照阶段
     */
    void deleteSnapshot(Long taskId, String snapshotStage);
}
