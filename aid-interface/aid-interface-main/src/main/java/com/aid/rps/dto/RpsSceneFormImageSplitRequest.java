package com.aid.rps.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import lombok.Data;

/**
 * 场景拆分四宫格请求 DTO，仅传源图 ID，后端反查归属并逐张切成 2×2 四张子图。
 *
 * @author 视觉AID
 */
@Data
public class RpsSceneFormImageSplitRequest
{
    /** 项目 ID（必填）：批量拆分范围闸门，所有源图须归属该项目 */
    @jakarta.validation.constraints.NotNull(message = "项目不能空")
    private Long projectId;

    /** 拆分源图 ID（单个场景；批量时可改用 sourceImageIds） */
    private Long sourceImageId;

    /** 批量拆分源图 ID 列表（与 sourceImageId 去重合并） */
    private List<Long> sourceImageIds;

    /** 统一解析出待拆分源图ID去重有序列表（合并两字段，剔除 null） */
    public List<Long> effectiveSourceIds()
    {
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (Objects.nonNull(sourceImageIds))
        {
            for (Long one : sourceImageIds)
            {
                if (Objects.nonNull(one))
                {
                    merged.add(one);
                }
            }
        }
        if (Objects.nonNull(sourceImageId))
        {
            merged.add(sourceImageId);
        }
        return new ArrayList<>(merged);
    }

    /** 原始提交源图 ID 总数（含 null，供 controller 对比 effective 数量透出差额） */
    public int rawIdCount()
    {
        int cnt = 0;
        if (Objects.nonNull(sourceImageIds))
        {
            cnt += sourceImageIds.size();
        }
        if (Objects.nonNull(sourceImageId))
        {
            cnt++;
        }
        return cnt;
    }
}

