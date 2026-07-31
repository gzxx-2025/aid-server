package com.aid.onboarding.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 上报单条 Tour 进度 - 入参
 *
 * @author 视觉AID
 */
@Data
public class OnboardingProgressReportReq {

    @NotBlank(message = "Tour标识不能为空")
    private String tourId;

    @NotBlank(message = "引导状态不能为空")
    private String status;

    @NotNull(message = "Tour版本号不能为空")
    @Min(value = 1, message = "Tour版本号无效")
    private Integer tourVersion;

    private String lastStepId;

    @NotBlank(message = "客户端时间不能为空")
    private String clientUpdatedAt;
}
