package com.aid.aid.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidInviteCode;

/**
 * 用户邀请码Service接口
 *
 * @author 视觉AID
 */
public interface IAidInviteCodeService extends IService<AidInviteCode>
{
    /**
     * 按用户ID查询邀请码记录
     *
     * @param userId 用户ID
     * @return 邀请码记录（不存在返回 null）
     */
    AidInviteCode getByUserId(Long userId);

    /**
     * 按邀请码查询记录（用于注册绑定/邀请码校验）
     *
     * @param inviteCode 邀请码（已归一化为大写）
     * @return 邀请码记录（不存在返回 null）
     */
    AidInviteCode getByCode(String inviteCode);
}
