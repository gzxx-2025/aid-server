package com.aid.aid.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidInviteRelation;
import com.aid.aid.domain.dto.InvitedUserRowDTO;
import com.aid.aid.domain.vo.AidInviteRelationVo;

/**
 * 邀请关系Mapper接口
 *
 * @author 视觉AID
 */
public interface AidInviteRelationMapper extends BaseMapper<AidInviteRelation>
{
    /**
     * 后台邀请关系列表（联表 sys_user 取邀请人/被邀请人昵称与账号）。
     * 仅返回列表展示必要字段，分页由调用方 PageHelper.startPage 接管。
     *
     * @param query 查询条件（inviterUserId/inviteeUserId/inviteCode/status）
     * @return 邀请关系聚合列表
     */
    List<AidInviteRelationVo> selectInviteRelationVoList(AidInviteRelationVo query);

    /**
     * C端「我邀请的用户」列表（联表 sys_user 取被邀请人昵称/头像）。
     * 仅返回展示必要字段，分页由调用方 PageHelper.startPage 接管。
     *
     * @param inviterUserId 邀请人用户ID
     * @return 被邀请用户行列表
     */
    List<InvitedUserRowDTO> selectInvitedUserRows(@Param("inviterUserId") Long inviterUserId);

    /**
     * 原子增减关系累计返佣积分（delta 正数累加/负数扣减），避免读改写并发丢更新。
     *
     * @param id    关系ID
     * @param delta 返佣变动量
     * @return 受影响行数
     */
    int changeTotalRebate(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
