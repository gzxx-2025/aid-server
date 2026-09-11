package com.aid.aid.service.impl;

import java.util.List;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidComicAuditRecord;
import com.aid.aid.mapper.AidComicAuditRecordMapper;
import com.aid.aid.service.IAidComicAuditRecordService;
import com.aid.common.utils.DateUtils;

/**
 * 作品审核记录Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidComicAuditRecordServiceImpl extends ServiceImpl<AidComicAuditRecordMapper, AidComicAuditRecord>
        implements IAidComicAuditRecordService
{
    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    /**
     * 查询审核记录列表
     *
     * @param query 查询条件
     * @return 审核记录集合（按创建时间倒序）
     */
    @Override
    public List<AidComicAuditRecord> selectAuditRecordList(AidComicAuditRecord query)
    {
        LambdaQueryWrapper<AidComicAuditRecord> wrapper = Wrappers.lambdaQuery();
        // 仅查询未删除记录
        wrapper.eq(AidComicAuditRecord::getDelFlag, DEL_FLAG_NORMAL);
        if (query != null)
        {
            // 审核对象类型筛选
            if (StrUtil.isNotBlank(query.getTargetType()))
            {
                wrapper.eq(AidComicAuditRecord::getTargetType, query.getTargetType());
            }
            // 审核对象ID筛选
            if (query.getTargetId() != null)
            {
                wrapper.eq(AidComicAuditRecord::getTargetId, query.getTargetId());
            }
            // 作品所属用户筛选
            if (query.getOwnerUserId() != null)
            {
                wrapper.eq(AidComicAuditRecord::getOwnerUserId, query.getOwnerUserId());
            }
            // 审核动作筛选
            if (query.getAction() != null)
            {
                wrapper.eq(AidComicAuditRecord::getAction, query.getAction());
            }
        }
        // 按创建时间倒序，最新审核记录在前
        wrapper.orderByDesc(AidComicAuditRecord::getCreateTime);
        return this.list(wrapper);
    }

    /**
     * 写入一条审核流水记录（统一入口）
     */
    @Override
    public int saveAuditRecord(String targetType, Long targetId, Long ownerUserId, Integer action,
                               Integer beforeStatus, Integer afterStatus, String auditReason, String operator)
    {
        AidComicAuditRecord record = new AidComicAuditRecord();
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setOwnerUserId(ownerUserId);
        record.setAction(action);
        record.setBeforeStatus(beforeStatus);
        record.setAfterStatus(afterStatus);
        record.setAuditReason(auditReason);
        record.setOperator(operator);
        record.setDelFlag(DEL_FLAG_NORMAL);
        // 创建操作必须填写创建者与创建时间
        record.setCreateBy(operator);
        record.setCreateTime(DateUtils.getNowDate());
        return this.save(record) ? 1 : 0;
    }
}
