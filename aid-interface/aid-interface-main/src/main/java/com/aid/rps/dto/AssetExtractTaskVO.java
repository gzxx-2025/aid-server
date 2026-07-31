package com.aid.rps.dto;

import java.util.List;

import com.aid.rps.vo.RpsAssetVO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI任务响应VO
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetExtractTaskVO
{
    /** 任务ID */
    private Long taskId;

    /** 任务状态: PENDING/PROCESSING/SUCCEEDED/FAILED/CANCELLED/PARTIAL_FAILED */
    private String status;

    /** 提取资产总数 */
    private Integer totalCount;

    /** 提取结果（仅SUCCEEDED时有值，资产提取类型使用） */
    private List<RpsAssetVO> assets;

    /** 结果数据JSON（仅SUCCEEDED时有值，通用） */
    private String resultData;

    /** 错误信息（仅FAILED时有值） */
    private String errorMessage;

    /** 分镜脚本批次总数（仅分镜脚本批量任务返回，用于进度 i/N 中的 N） */
    private Integer totalBatches;

    /** 分镜脚本 shot 总数（仅分镜脚本批量任务返回） */
    private Integer totalShots;

    /** 链式下一步子任务ID（提示词+出图/出片合并任务使用，可为空） */
    private Long chainChildTaskId;

    /** 链式下一步子任务ID列表，按提交顺序排列。 */
    private List<Long> chainChildTaskIds;

    /** 链式下一步子任务类型（storyboard_image_generate / storyboard_video_generate，可为空） */
    private String chainChildTaskType;

    /** 非阻断提示（可为空）：如分镜脚本提交时检测到超长场次、最低镜头数被封顶的提醒，前端可 toast 展示 */
    private String warning;
}
