package com.aid.user.controller;

import com.aid.aid.domain.vo.UserInfoVO;
import com.aid.auth.service.AuthService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端个人信息Controller
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "个人信息", description = "C端个人信息查询接口")
@RestController
@RequestMapping("/api/user")
public class UserProfileController extends BaseController {

    @Resource
    private AuthService authService;

    /**
     * 查询当前登录用户的个人信息
     *
     * 返回内容与登录接口的 {@code userInfo} 字段完全一致（含余额、头像、会员、实名等，PII 已脱敏），
     * 但不含 token 与社交绑定列表。余额等会变动字段实时查库，避免前端沿用登录时的旧值。
     *
     * @return 个人信息（{@link UserInfoVO}，放在 data 中返回）
     */
    @Operation(summary = "查询个人信息", description = "返回当前登录用户信息，字段与登录接口userInfo一致")
    @PostMapping("/profile")
    public AjaxResult profile() {
        UserInfoVO userInfo = authService.getCurrentUserInfo();
        return AjaxResult.success(userInfo);
    }
}
