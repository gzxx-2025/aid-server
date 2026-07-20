package com.aid.onboarding.service;

import com.aid.onboarding.dto.*;

/**
 * 用户引导进度业务 Service 接口
 *
 * @author 视觉AID
 */
public interface IUserOnboardingService {

    OnboardingProgressVO getProgress(Long userId);

    OnboardingReportResultVO report(Long userId, OnboardingProgressReportReq req);

    OnboardingProgressVO sync(Long userId, OnboardingProgressSyncReq req);

    OnboardingProgressVO reset(Long userId, OnboardingProgressResetReq req);

    OnboardingProgressVO dismiss(Long userId, OnboardingProgressDismissReq req);
}
