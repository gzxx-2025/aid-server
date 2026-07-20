package com.aid.aid.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidInviteRebateRecord;
import com.aid.aid.domain.dto.InviteRebateRowDTO;
import com.aid.aid.domain.vo.AidInviteRebateRecordVo;
import com.aid.aid.mapper.AidInviteRebateRecordMapper;
import com.aid.aid.service.IAidInviteRebateRecordService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 邀请返佣记录Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidInviteRebateRecordServiceImpl extends ServiceImpl<AidInviteRebateRecordMapper, AidInviteRebateRecord> implements IAidInviteRebateRecordService
{
    @Autowired
    private AidInviteRebateRecordMapper aidInviteRebateRecordMapper;

    /**
     * 后台返佣记录列表（联表 sys_user），分页由 Controller 的 startPage 统一处理
     */
    @Override
    public List<AidInviteRebateRecordVo> selectRebateRecordVoList(AidInviteRebateRecordVo query)
    {
        return aidInviteRebateRecordMapper.selectRebateRecordVoList(query);
    }

    /**
     * 按订单号查询返佣记录
     * 仅查询字段：id, inviterUserId, inviteeUserId, orderNo, rebateAmount, status（幂等判断与退款扣回所需）
     */
    @Override
    public AidInviteRebateRecord getByOrderNo(String orderNo)
    {
        if (StrUtil.isBlank(orderNo))
        {
            return null;
        }
        return this.getOne(Wrappers.<AidInviteRebateRecord>lambdaQuery()
                .select(AidInviteRebateRecord::getId, AidInviteRebateRecord::getInviterUserId,
                        AidInviteRebateRecord::getInviteeUserId, AidInviteRebateRecord::getOrderNo,
                        AidInviteRebateRecord::getRebateAmount, AidInviteRebateRecord::getStatus)
                .eq(AidInviteRebateRecord::getOrderNo, orderNo)
                .eq(AidInviteRebateRecord::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /**
     * C端「返佣明细」列表，分页由调用方 PageHelper.startPage 接管
     */
    @Override
    public List<InviteRebateRowDTO> selectRebateRows(Long inviterUserId)
    {
        return aidInviteRebateRecordMapper.selectRebateRows(inviterUserId);
    }

    /**
     * 统计某邀请人累计已发放返佣积分（status=granted）
     * 仅查询字段：rebateAmount（求和统计）
     */
    @Override
    public BigDecimal sumGrantedRebate(Long inviterUserId)
    {
        if (Objects.isNull(inviterUserId))
        {
            return BigDecimal.ZERO;
        }
        List<AidInviteRebateRecord> records = this.list(Wrappers.<AidInviteRebateRecord>lambdaQuery()
                .select(AidInviteRebateRecord::getRebateAmount)
                .eq(AidInviteRebateRecord::getInviterUserId, inviterUserId)
                .eq(AidInviteRebateRecord::getStatus, "granted")
                .eq(AidInviteRebateRecord::getDelFlag, "0"));
        if (CollectionUtil.isEmpty(records))
        {
            return BigDecimal.ZERO;
        }
        // 逐条累加已发放返佣，null 金额按 0 处理
        BigDecimal total = BigDecimal.ZERO;
        for (AidInviteRebateRecord record : records)
        {
            if (Objects.nonNull(record.getRebateAmount()))
            {
                total = total.add(record.getRebateAmount());
            }
        }
        return total;
    }
}
