package com.aid.orchestration.dto;

import java.util.List;

import lombok.Data;

/**
 * 模型与模型池关系查询请求。
 */
@Data
public class ModelPoolBindingQueryRequest
{
    /** 待查询模型；为空时返回全部有效模型。 */
    private List<Long> modelIds;
}
