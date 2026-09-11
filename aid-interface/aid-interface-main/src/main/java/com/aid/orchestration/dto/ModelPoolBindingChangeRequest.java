package com.aid.orchestration.dto;

import java.util.List;

import lombok.Data;

/**
 * 模型与模型池批量关系变更请求。
 */
@Data
public class ModelPoolBindingChangeRequest
{
    /** 待处理模型主键。 */
    private List<Long> modelIds;

    /** 待处理模型池主键。 */
    private List<Long> poolIds;
}
