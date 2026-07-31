package com.aid.user.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.promotion.dto.InviteCodeCheckRequest;
import com.aid.promotion.dto.InvitePageRequest;
import com.aid.promotion.service.IInviteService;
import com.aid.promotion.vo.InviteCodeCheckVO;
import com.aid.promotion.vo.InviteInfoVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * C端邀请Controller（邀请码/邀请关系/返佣明细）
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "邀请推广", description = "C端邀请码、我的邀请、返佣明细接口")
@RestController
@RequestMapping("/api/user/invite")
public class InviteController extends BaseController {

    @Resource
    private IInviteService inviteService;

    /**
     * 邀请码预校验（匿名接口）
     *
     * 注册页输入邀请码时预检有效性并回显邀请人昵称/头像；
     * 无效时返回 valid=false + reason，不抛异常（邀请码错误不阻断注册流程）。
     *
     * @param request 入参（inviteCode 邀请码，大小写不敏感）
     * @return data：{@link InviteCodeCheckVO}（valid 是否有效 / reason 无效原因 / inviterNickName 邀请人昵称 / inviterAvatar 邀请人头像）
     */
    @Operation(summary = "邀请码预校验", description = "匿名接口，注册页输入邀请码时预检并展示邀请人信息")
    @Anonymous
    @PostMapping("/check")
    public AjaxResult checkInviteCode(@RequestBody InviteCodeCheckRequest request) {
        // 空对象防御：body 为空时按空码处理，返回"邀请码无效"
        String rawCode = request == null ? null : request.getInviteCode();
        return AjaxResult.success(inviteService.checkInviteCode(rawCode));
    }

    /**
     * 我的邀请信息（邀请页主数据，需登录）
     *
     * 活动开启时首次查询自动生成专属邀请码；活动关闭时仅返回 enabled=false，前端隐藏邀请入口。
     *
     * @return data：{@link InviteInfoVO}（enabled 活动开关 / inviteCode 我的邀请码 / rebateRatio 返佣比例% /
     *         rebateMaxPerOrder 单笔返佣上限 / invitedCount 已邀请人数 / totalRebate 累计返佣积分）
     */
    @Operation(summary = "我的邀请信息", description = "邀请页主数据，活动开启时自动生成邀请码")
    @PostMapping("/info")
    public AjaxResult myInviteInfo() {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success(inviteService.getMyInviteInfo(userId));
    }

    /**
     * 我邀请的用户分页列表（需登录）
     *
     * @param request 分页入参（pageNum 页码默认1 / pageSize 每页条数默认10上限100）
     * @return total 总条数；data：InvitedUserVO 列表（nickName 被邀请人昵称 / avatar 头像 /
     *         totalRebate 该用户累计为我带来的返佣 / registerTime 注册时间）
     */
    @Operation(summary = "我邀请的用户列表", description = "分页查询我邀请注册的用户")
    @PostMapping("/users")
    public AjaxResult pageInvitedUsers(@RequestBody(required = false) InvitePageRequest request) {
        Long userId = SecurityUtils.getUserId();
        return inviteService.pageInvitedUsers(userId, request);
    }

    /**
     * 我的返佣明细分页列表（需登录）
     *
     * @param request 分页入参（pageNum 页码默认1 / pageSize 每页条数默认10上限100）
     * @return total 总条数；data：InviteRebateItemVO 列表（nickName 充值人昵称 / orderCredits 订单到账积分 /
     *         rebateRatio 返佣比例% / rebateAmount 返佣积分 / status 状态granted已发放revoked已撤回 /
     *         statusName 状态中文名 / createTime 返佣时间）
     */
    @Operation(summary = "我的返佣明细", description = "分页查询邀请充值返佣记录")
    @PostMapping("/rebates")
    public AjaxResult pageRebateRecords(@RequestBody(required = false) InvitePageRequest request) {
        Long userId = SecurityUtils.getUserId();
        return inviteService.pageRebateRecords(userId, request);
    }
}
