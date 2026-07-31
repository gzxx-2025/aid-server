package com.aid.common.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用批量操作结果 VO。
 * 用于"单个 / 批量同接口"的批量动作（如 form_image 批量设/取消使用中、分镜批量设最终图 / 最终视频、
 * 场景批量拆分四宫格）：逐条独立执行，单条失败不影响其它条目，最终汇总成功 / 失败明细返回。
 * 单个调用时 {@code total=1}，与批量返回结构一致，前端无需区分两套出参。
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResultVO
{
    /** 本次提交的条目总数 */
    private Integer total;

    /** 成功条数 */
    private Integer successCount;

    /** 失败条数 */
    private Integer failCount;

    /** 成功的业务主键列表（form/use 为 imageId，分镜接口为 recordId，拆分为 sourceImageId） */
    private List<Long> successIds = new ArrayList<>();

    /** 失败明细（含业务主键与失败原因） */
    private List<FailItem> failures = new ArrayList<>();

    /** 记录一条成功条目 */
    public void addSuccess(Long id)
    {
        this.successIds.add(id);
    }

    /** 记录一条失败条目 */
    public void addFailure(Long id, String reason)
    {
        this.failures.add(new FailItem(id, reason));
    }

    /** 依据 successIds / failures 回填 total / successCount / failCount，组装完成后统一调用 */
    public BatchOperationResultVO summarize()
    {
        this.successCount = this.successIds.size();
        this.failCount = this.failures.size();
        this.total = this.successCount + this.failCount;
        return this;
    }

    /**
     * 单条失败明细。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailItem
    {
        /** 失败条目的业务主键 */
        private Long id;

        /** 失败原因（已美化，可直接展示给用户） */
        private String reason;
    }
}
