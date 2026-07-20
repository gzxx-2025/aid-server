package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidComicAuditRecord;

/**
 * 作品审核记录Service接口
 *
 * @author 视觉AID
 */
public interface IAidComicAuditRecordService extends IService<AidComicAuditRecord>
{
    /**
     * 查询审核记录列表
     *
     * @param query 查询条件（target_type、target_id、owner_user_id、action 任意组合，均可为空）
     * @return 审核记录集合（按创建时间倒序）
     */
    List<AidComicAuditRecord> selectAuditRecordList(AidComicAuditRecord query);

    /**
     * 写入一条审核流水记录（统一入口，C端提交与后台审核均调用此方法）。
     *
     * @param targetType   审核对象类型(project项目 episode剧集)
     * @param targetId     审核对象ID
     * @param ownerUserId  作品所属用户ID
     * @param action       审核动作(1提交审核 2审核通过 3审核驳回)
     * @param beforeStatus 变更前状态
     * @param afterStatus  变更后状态
     * @param auditReason  审核意见/驳回原因（可为空）
     * @param operator     操作人标识（C端为用户ID，后台为管理员账号）
     * @return 影响行数
     */
    int saveAuditRecord(String targetType, Long targetId, Long ownerUserId, Integer action,
                        Integer beforeStatus, Integer afterStatus, String auditReason, String operator);
}
