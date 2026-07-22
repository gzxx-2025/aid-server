package com.aid.rps.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import lombok.Data;

/**
 * 删除资产请求DTO（单个 / 批量同接口）
 *
 * @author 视觉AID
 */
@Data
public class RpsDeleteRequest {

    /** 主表资产ID（单删） */
    private Long id;

    /** 从表形态ID（仅单删时可传：删除指定形态；不传则删除主资产及其全部形态） */
    private Long formId;

    /** 批量主表资产ID列表（与 id 去重合并；批量删除时不允许携带 formId） */
    private List<Long> ids;

    /** 统一解析出待删除的主资产ID去重有序列表（合并 ids 与单个 id，剔除 null） */
    public List<Long> effectiveIds()
    {
        // LinkedHashSet 保证去重同时维持前端传入顺序
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (Objects.nonNull(ids))
        {
            for (Long one : ids)
            {
                if (Objects.nonNull(one))
                {
                    merged.add(one);
                }
            }
        }
        if (Objects.nonNull(id))
        {
            merged.add(id);
        }
        return new ArrayList<>(merged);
    }

    /** 至少一个资产ID非空：controller 前置校验，避免空请求体直落 service */
    public boolean hasAnyId()
    {
        return !effectiveIds().isEmpty();
    }

    /** 是否为批量模式（显式传了 ids 列表即视为批量，出参走统一批量结果） */
    public boolean isBatchMode()
    {
        return Objects.nonNull(ids) && !ids.isEmpty();
    }

    /** 原始提交 ID 总数（含 null，不去重，供 controller 对比 effective 数量透出差额） */
    public int rawIdCount()
    {
        int cnt = 0;
        if (Objects.nonNull(ids))
        {
            cnt += ids.size();
        }
        if (Objects.nonNull(id))
        {
            cnt++;
        }
        return cnt;
    }
}
