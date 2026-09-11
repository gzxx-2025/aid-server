package com.aid.orchestration.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 模型与模型池批量关系变更结果。
 */
@Data
@Builder
public class ModelPoolBindingChangeVO
{
    private int requestedModelCount;
    private int requestedPoolCount;
    private int changedPoolCount;
    private int changedRelationCount;
    private int unchangedRelationCount;
    private ModelPoolBindingSnapshotVO snapshot;
}
