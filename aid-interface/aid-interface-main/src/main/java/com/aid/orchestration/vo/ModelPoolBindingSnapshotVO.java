package com.aid.orchestration.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 模型与模型池关系快照。
 */
@Data
@Builder
public class ModelPoolBindingSnapshotVO
{
    private List<ModelPoolBindingPoolVO> pools;
    private List<ModelPoolBindingModelVO> models;
}
