package com.aid.orchestration.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 模型池关系快照中的模型信息。
 */
@Data
@Builder
public class ModelPoolBindingModelVO
{
    private Long id;
    private String modelCode;
    private String modelName;
    private String modelType;
    private String generateMode;
    private String status;
    private List<Long> poolIds;
}
