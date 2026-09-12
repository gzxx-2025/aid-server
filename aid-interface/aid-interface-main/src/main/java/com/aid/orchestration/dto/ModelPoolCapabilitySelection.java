package com.aid.orchestration.dto;

import java.util.List;

import lombok.Data;

/** 批量绑定模型池时，对单个模型与模型池关系选择的业务能力。 */
@Data
public class ModelPoolCapabilitySelection
{
    private Long modelId;
    private Long poolId;
    private List<String> capabilityCodes;
    private String defaultCapabilityCode;
}
