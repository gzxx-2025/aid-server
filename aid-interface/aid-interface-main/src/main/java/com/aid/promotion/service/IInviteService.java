package com.aid.promotion.service;

import com.aid.common.core.domain.AjaxResult;
import com.aid.promotion.dto.InvitePageRequest;
import com.aid.promotion.vo.InviteInfoVO;

/**
 * 提供邀请码生成、注册绑定和邀请数据查询能力。
 *
 * @author 视觉AID
 */
public interface IInviteService
{
    /**
     * 校验首次注册携带的邀请码，不创建邀请关系。
     * 用于发送登录验证码前拦截无效邀请码，最终注册时仍需再次校验。
     *
     * @param rawCode 注册时携带的邀请码；为空时不参与邀请活动
     */
    void validateForRegistration(String rawCode);

    /**
     * 在注册事务内校验邀请码并绑定邀请关系。
     *
     * @param inviteeUserId 新注册用户ID
     * @param rawCode 注册时携带的邀请码
     * @param channel 注册渠道
     */
    void bindOnRegister(Long inviteeUserId, String rawCode, String channel);

    /**
     * 我的邀请信息（活动开启时懒生成邀请码；关闭时仅返回 enabled=false）
     *
     * @param userId 当前用户ID
     * @return 邀请页主数据
     */
    InviteInfoVO getMyInviteInfo(Long userId);

    /**
     * 我邀请的用户分页列表
     *
     * @param userId  当前用户ID（邀请人）
     * @param request 分页参数
     * @return total + data（InvitedUserVO 列表）
     */
    AjaxResult pageInvitedUsers(Long userId, InvitePageRequest request);

    /**
     * 我的返佣明细分页列表
     *
     * @param userId  当前用户ID（邀请人）
     * @param request 分页参数
     * @return total + data（InviteRebateItemVO 列表）
     */
    AjaxResult pageRebateRecords(Long userId, InvitePageRequest request);
}
