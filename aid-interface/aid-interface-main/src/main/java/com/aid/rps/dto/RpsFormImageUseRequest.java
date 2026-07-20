package com.aid.rps.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 形态图片"使用中"状态请求 DTO，imageId 为标准字段，id 为兼容别名。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageUseRequest
{
    /** 项目 ID（必填）：批量操作范围闸门，所有目标图片须归属该项目 */
    @jakarta.validation.constraints.NotNull(message = "项目不能空")
    private Long projectId;

    /** 图片实例ID（标准字段，推荐）：aid_role_prop_scene_form_image.id */
    private Long imageId;

    /** imageId 的兼容别名，语义同 imageId */
    private Long id;

    /** 批量图片实例ID列表（单个 / 批量同接口，与 imageId / id 去重合并） */
    private List<Long> imageIds;

    /** 统一解析出有效的 imageId：优先 imageId，为空则回退 id */
    public Long effectiveImageId()
    {
        return Objects.nonNull(imageId) ? imageId : id;
    }

    /** 统一解析出待操作的图片ID去重有序列表（合并 imageIds 与单个 id，剔除 null） */
    public List<Long> effectiveImageIds()
    {
        // LinkedHashSet 保证去重同时维持前端传入顺序
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (Objects.nonNull(imageIds))
        {
            for (Long one : imageIds)
            {
                if (Objects.nonNull(one))
                {
                    merged.add(one);
                }
            }
        }
        Long single = effectiveImageId();
        if (Objects.nonNull(single))
        {
            merged.add(single);
        }
        return new ArrayList<>(merged);
    }

    /** 至少一个字段非空：controller 前置校验，避免空请求体直落 service */
    public boolean hasAnyId()
    {
        return !effectiveImageIds().isEmpty();
    }

    /** 原始提交 ID 总数（含 null，不去重，供 controller 对比 effective 数量透出差额） */
    public int rawIdCount()
    {
        int cnt = 0;
        if (Objects.nonNull(imageIds))
        {
            cnt += imageIds.size();
        }
        if (Objects.nonNull(imageId) || Objects.nonNull(id))
        {
            cnt++;
        }
        return cnt;
    }
}
