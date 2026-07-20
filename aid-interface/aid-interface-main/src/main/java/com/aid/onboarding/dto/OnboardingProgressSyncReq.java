package com.aid.onboarding.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OnboardingProgressSyncReq {

    @NotNull(message = "tours不能为空")
    @Valid
    private List<OnboardingProgressReportReq> tours;
}
