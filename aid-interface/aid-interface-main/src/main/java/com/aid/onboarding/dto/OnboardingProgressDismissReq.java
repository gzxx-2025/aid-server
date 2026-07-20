package com.aid.onboarding.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OnboardingProgressDismissReq {
    @NotNull(message = "dismissed不能为空")
    private Boolean dismissed;
}
