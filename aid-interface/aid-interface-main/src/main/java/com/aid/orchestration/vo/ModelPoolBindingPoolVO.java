package com.aid.orchestration.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 模型池关系快照中的模型池信息。
 */
@Data
@Builder
public class ModelPoolBindingPoolVO
{
    private Long id;
    private String funcName;
    private String funcCode;
    private String modelType;
    private String generateMode;
    private String status;
    private boolean configurationValid;
    private List<Long> modelIds;
}
