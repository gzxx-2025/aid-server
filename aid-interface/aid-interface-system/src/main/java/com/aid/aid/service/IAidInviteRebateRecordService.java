package com.aid.aid.service;

import java.math.BigDecimal;
import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidInviteRebateRecord;
import com.aid.aid.domain.dto.InviteRebateRowDTO;
import com.aid.aid.domain.vo.AidInviteRebateRecordVo;

/**
 * 邀请返佣记录Service接口
 *
 * @author 视觉AID
 */
public interface IAidInviteRebateRecordService extends IService<AidInviteRebateRecord>
{
    /**
     * 后台返佣记录列表（联表 sys_user）
     *
     * @param query 查询条件
     * @return 返佣记录聚合列表
     */
    List<AidInviteRebateRecordVo> selectRebateRecordVoList(AidInviteRebateRecordVo query);

    /**
     * 按订单号查询返佣记录（幂等判断/退款扣回定位）
     *
     * @param orderNo 充值订单号
     * @return 返佣记录（不存在返回 null）
     */
    AidInviteRebateRecord getByOrderNo(String orderNo);

    /**
     * C端「返佣明细」列表（联表 sys_user）
     *
     * @param inviterUserId 邀请人用户ID
     * @return 返佣明细行列表
     */
    List<InviteRebateRowDTO> selectRebateRows(Long inviterUserId);

    /**
     * 统计某邀请人累计已发放返佣积分（不含已撤回）
     *
     * @param inviterUserId 邀请人用户ID
     * @return 累计返佣积分
     */
    BigDecimal sumGrantedRebate(Long inviterUserId);
}
