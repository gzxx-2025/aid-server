package com.aid.promotion.service;

import com.aid.common.core.domain.AjaxResult;
import com.aid.promotion.dto.InvitePageRequest;
import com.aid.promotion.vo.InviteCodeCheckVO;
import com.aid.promotion.vo.InviteInfoVO;

/**
 * 邀请Service接口（邀请码、邀请关系绑定、C端邀请页数据）
 *
 * @author 视觉AID
 */
public interface IInviteService
{
    /**
     * 邀请码预校验（匿名，注册页输入邀请码时预检并展示邀请人信息）
     *
     * @param rawCode 用户输入的邀请码（大小写不敏感）
     * @return 校验结果（无效时带原因，不抛异常）
     */
    InviteCodeCheckVO checkInviteCode(String rawCode);

    /**
     * 注册瞬间绑定邀请关系（静默处理，绝不抛异常阻断注册主流程）。
     * 必须在注册事务内调用：注册回滚时关系一并回滚。
     * 防护：活动开关、邀请码格式/存在性、邀请人状态、自邀、重复绑定均静默拦截。
     * 注册后不提供补绑接口（防刷）。
     *
     * @param inviteeUserId 新注册用户ID
     * @param rawCode       注册时携带的邀请码（可空，空则不绑定）
     * @param channel       注册渠道（sms手机号/email邮箱/wechat微信）
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
