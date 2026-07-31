package com.aid.business.main.controller.onboarding;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.onboarding.dto.*;
import com.aid.onboarding.service.IUserOnboardingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户引导进度 Controller
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "用户引导进度", description = "引导进度获取、上报、同步、重置、全局开关")
@RestController
@RequestMapping("/api/user/onboarding")
public class UserOnboardingController extends BaseController {

    @Resource
    private IUserOnboardingService onboardingService;

    /**
     * 获取引导进度
     * 返回当前登录用户的全部引导进度及全局开关
     */
    @Operation(summary = "获取引导进度", description = "返回当前登录用户的全量 Tour 进度和全局开关，首次访问返回空列表")
    @PostMapping("/progress/get")
    public AjaxResult getProgress() {
        Long userId = getUserId();
        return success(onboardingService.getProgress(userId));
    }

    /**
     * 上报单条 Tour 进度。
     */
    @Operation(summary = "上报单条Tour进度", description = "同一userId+tourId重复调用视为UPSERT，按clientUpdatedAt冲突合并")
    @PostMapping("/progress/report")
    public AjaxResult report(@Valid @RequestBody OnboardingProgressReportReq req) {
        Long userId = getUserId();
        return success(onboardingService.report(userId, req));
    }

    /**
     * 批量同步进度
     * 登录后或离线恢复时，前端将本地未同步的多条进度一次性提交
     */
    @Operation(summary = "批量同步进度", description = "逐条冲突合并后返回全量快照")
    @PostMapping("/progress/sync")
    public AjaxResult sync(@Valid @RequestBody OnboardingProgressSyncReq req) {
        Long userId = getUserId();
        return success(onboardingService.sync(userId, req));
    }

    /**
     * 重置引导进度
     * 用户从菜单选择「重新观看引导」时调用
     */
    @Operation(summary = "重置引导进度", description = "tourIds为空时重置全部Tour；非空时仅重置指定Tour")
    @PostMapping("/progress/reset")
    public AjaxResult reset(@RequestBody(required = false) OnboardingProgressResetReq req) {
        Long userId = getUserId();
        return success(onboardingService.reset(userId, req));
    }

    /**
     * 全局关闭/恢复引导
     * dismissed=true：不再自动弹出任何引导
     * dismissed=false：恢复自动触发
     */
    @Operation(summary = "全局关闭/恢复引导", description = "true=不再自动弹出引导，false=恢复自动触发")
    @PostMapping("/progress/dismiss")
    public AjaxResult dismiss(@Valid @RequestBody OnboardingProgressDismissReq req) {
        Long userId = getUserId();
        return success(onboardingService.dismiss(userId, req));
    }
}
