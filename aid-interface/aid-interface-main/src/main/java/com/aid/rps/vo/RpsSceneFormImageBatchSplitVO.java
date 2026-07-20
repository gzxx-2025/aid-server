package com.aid.rps.vo;

import java.util.ArrayList;
import java.util.List;

import com.aid.common.vo.BatchOperationResultVO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景批量拆分四宫格响应 VO（单个 / 批量同接口）。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpsSceneFormImageBatchSplitVO
{
    /** 批量汇总（total / successCount / failCount / successIds / failures，id 为 sourceImageId） */
    private BatchOperationResultVO summary;

    /** 成功拆分的每张源图详情列表（顺序与成功执行顺序一致） */
    @Builder.Default
    private List<RpsSceneFormImageSplitVO> results = new ArrayList<>();
}
