package com.aid.aid.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidInviteRelation;
import com.aid.aid.domain.dto.InvitedUserRowDTO;
import com.aid.aid.domain.vo.AidInviteRelationVo;
import com.aid.aid.mapper.AidInviteRelationMapper;
import com.aid.aid.service.IAidInviteRelationService;
import com.aid.common.exception.ServiceException;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 邀请关系Service业务层处理
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidInviteRelationServiceImpl extends ServiceImpl<AidInviteRelationMapper, AidInviteRelation> implements IAidInviteRelationService
{
    @Autowired
    private AidInviteRelationMapper aidInviteRelationMapper;

    /**
     * 后台邀请关系列表（联表 sys_user），分页由 Controller 的 startPage 统一处理
     */
    @Override
    public List<AidInviteRelationVo> selectInviteRelationVoList(AidInviteRelationVo query)
    {
        return aidInviteRelationMapper.selectInviteRelationVoList(query);
    }

    /**
     * 按被邀请人查询邀请关系
     * 仅查询字段：id, inviterUserId, inviteeUserId, inviteCode, status, totalRebate（返佣与判重所需）
     */
    @Override
    public AidInviteRelation getByInvitee(Long inviteeUserId)
    {
        if (Objects.isNull(inviteeUserId))
        {
            return null;
        }
        return this.getOne(Wrappers.<AidInviteRelation>lambdaQuery()
                .select(AidInviteRelation::getId, AidInviteRelation::getInviterUserId,
                        AidInviteRelation::getInviteeUserId, AidInviteRelation::getInviteCode,
                        AidInviteRelation::getStatus, AidInviteRelation::getTotalRebate)
                .eq(AidInviteRelation::getInviteeUserId, inviteeUserId)
                .eq(AidInviteRelation::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /**
     * C端「我邀请的用户」列表，分页由调用方 PageHelper.startPage 接管
     */
    @Override
    public List<InvitedUserRowDTO> selectInvitedUserRows(Long inviterUserId)
    {
        return aidInviteRelationMapper.selectInvitedUserRows(inviterUserId);
    }

    /**
     * 统计某邀请人的有效邀请人数
     */
    @Override
    public long countByInviter(Long inviterUserId)
    {
        if (Objects.isNull(inviterUserId))
        {
            return 0L;
        }
        return this.count(Wrappers.<AidInviteRelation>lambdaQuery()
                .eq(AidInviteRelation::getInviterUserId, inviterUserId)
                .eq(AidInviteRelation::getDelFlag, "0"));
    }

    /**
     * 原子增减关系累计返佣积分（SQL delta 更新，避免读改写并发丢更新）
     */
    @Override
    public int changeTotalRebate(Long id, BigDecimal delta)
    {
        if (Objects.isNull(id) || Objects.isNull(delta))
        {
            return 0;
        }
        return aidInviteRelationMapper.changeTotalRebate(id, delta);
    }

    /**
     * 后台禁用/恢复邀请关系（仅更新 status/remark/更新人时间，其余字段不允许改动）
     */
    @Override
    public void changeStatus(Long id, String status, String remark, String updateBy)
    {
        // 关系ID必填
        if (Objects.isNull(id))
        {
            log.info("邀请关系状态变更缺少ID");
            throw new ServiceException("参数有误");
        }
        // 状态只允许 0正常 / 1禁用
        if (!Objects.equals("0", status) && !Objects.equals("1", status))
        {
            log.info("邀请关系状态值非法, id={}, status={}", id, status);
            throw new ServiceException("状态值有误");
        }
        // 关系必须存在且未删除
        AidInviteRelation relation = this.getOne(Wrappers.<AidInviteRelation>lambdaQuery()
                .select(AidInviteRelation::getId, AidInviteRelation::getStatus)
                .eq(AidInviteRelation::getId, id)
                .eq(AidInviteRelation::getDelFlag, "0")
                .last("LIMIT 1"));
        if (Objects.isNull(relation))
        {
            log.info("邀请关系不存在, id={}", id);
            throw new ServiceException("关系不存在");
        }
        // 更新操作必须填写更新时间和更新者
        boolean updated = this.update(Wrappers.<AidInviteRelation>lambdaUpdate()
                .eq(AidInviteRelation::getId, id)
                .eq(AidInviteRelation::getDelFlag, "0")
                .set(AidInviteRelation::getStatus, status)
                .set(StrUtil.isNotBlank(remark), AidInviteRelation::getRemark, remark)
                .set(AidInviteRelation::getUpdateBy, updateBy)
                .set(AidInviteRelation::getUpdateTime, new Date()));
        if (!updated)
        {
            log.error("邀请关系状态变更失败, id={}, status={}", id, status);
            throw new ServiceException("操作失败");
        }
        log.info("邀请关系状态变更成功, id={}, status={}, updateBy={}", id, status, updateBy);
    }
}
