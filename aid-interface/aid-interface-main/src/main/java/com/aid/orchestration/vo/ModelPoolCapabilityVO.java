package com.aid.orchestration.vo;

import lombok.Builder;
import lombok.Data;

/** 模型池绑定弹窗使用的精简能力定义。 */
@Data
@Builder
public class ModelPoolCapabilityVO
{
    private String code;
    private String label;
    private String generateMode;
    private boolean enabled;
    private boolean defaultCapability;
}
