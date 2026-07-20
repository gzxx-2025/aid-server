package com.aid.aid.service.impl;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidInviteCode;
import com.aid.aid.mapper.AidInviteCodeMapper;
import com.aid.aid.service.IAidInviteCodeService;

/**
 * 用户邀请码Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidInviteCodeServiceImpl extends ServiceImpl<AidInviteCodeMapper, AidInviteCode> implements IAidInviteCodeService
{
    /**
     * 按用户ID查询邀请码记录
     * 仅查询字段：id, userId, inviteCode（列表展示与绑定校验均只需这三个字段）
     */
    @Override
    public AidInviteCode getByUserId(Long userId)
    {
        if (Objects.isNull(userId))
        {
            return null;
        }
        return this.getOne(Wrappers.<AidInviteCode>lambdaQuery()
                .select(AidInviteCode::getId, AidInviteCode::getUserId, AidInviteCode::getInviteCode)
                .eq(AidInviteCode::getUserId, userId)
                .eq(AidInviteCode::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /**
     * 按邀请码查询记录
     * 仅查询字段：id, userId, inviteCode
     */
    @Override
    public AidInviteCode getByCode(String inviteCode)
    {
        return this.getOne(Wrappers.<AidInviteCode>lambdaQuery()
                .select(AidInviteCode::getId, AidInviteCode::getUserId, AidInviteCode::getInviteCode)
                .eq(AidInviteCode::getInviteCode, inviteCode)
                .eq(AidInviteCode::getDelFlag, "0")
                .last("LIMIT 1"));
    }
}
