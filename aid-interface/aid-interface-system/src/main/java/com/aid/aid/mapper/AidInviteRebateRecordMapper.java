package com.aid.aid.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidInviteRebateRecord;
import com.aid.aid.domain.dto.InviteRebateRowDTO;
import com.aid.aid.domain.vo.AidInviteRebateRecordVo;

/**
 * 邀请返佣记录Mapper接口
 *
 * @author 视觉AID
 */
public interface AidInviteRebateRecordMapper extends BaseMapper<AidInviteRebateRecord>
{
    /**
     * 后台返佣记录列表（联表 sys_user 取邀请人/被邀请人昵称）。
     * 仅返回列表展示必要字段，分页由调用方 PageHelper.startPage 接管。
     *
     * @param query 查询条件（inviterUserId/inviteeUserId/orderNo/status）
     * @return 返佣记录聚合列表
     */
    List<AidInviteRebateRecordVo> selectRebateRecordVoList(AidInviteRebateRecordVo query);

    /**
     * C端「返佣明细」列表（联表 sys_user 取被邀请人昵称）。
     * 仅返回展示必要字段，分页由调用方 PageHelper.startPage 接管。
     *
     * @param inviterUserId 邀请人用户ID
     * @return 返佣明细行列表
     */
    List<InviteRebateRowDTO> selectRebateRows(@Param("inviterUserId") Long inviterUserId);
}
