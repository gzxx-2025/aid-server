package com.aid.onboarding.dto;

import java.util.List;
import lombok.Data;

@Data
public class OnboardingProgressResetReq {
    private List<String> tourIds;
}
