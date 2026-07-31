package com.aid.storyboard.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import lombok.Data;

/**
 * 设置 / 取消分镜最终图片 / 最终视频请求 DTO（支持单个 / 批量同接口）。
 *
 * @author 视觉AID
 */
@Data
public class SetFinalImageRequest
{
    /** 项目 ID（必填）：批量操作范围闸门，目标分镜须归属该项目，否则该条目按"项目不匹配"失败。 */
    @jakarta.validation.constraints.NotNull(message = "项目不能空")
    private Long projectId;

    /** 剧集 ID（必填）：电影项目固定传 {@code 0}，剧集项目须 &gt; 0 且属于该项目。 */
    @jakarta.validation.constraints.NotNull(message = "剧集不能空")
    private Long episodeId;

    /** 分镜 ID（单个时必填；批量时改用 {@link #items}） */
    private Long storyboardId;

    /** 生成记录 ID（单个时必填；批量时改用 {@link #items}） */
    private Long recordId;

    /** 批量条目列表（单个 / 批量同接口）：每项一对 storyboardId + recordId */
    private List<Item> items;

    /**
     * 合并 {@link #items} 与顶层单条入参，按 (storyboardId, recordId) 去重、剔除字段不全者，维持插入顺序。
     */
    public List<Item> effectiveItems()
    {
        LinkedHashMap<String, Item> merged = new LinkedHashMap<>();
        if (Objects.nonNull(items))
        {
            for (Item it : items)
            {
                if (Objects.nonNull(it) && Objects.nonNull(it.getStoryboardId()) && Objects.nonNull(it.getRecordId()))
                {
                    merged.put(it.getStoryboardId() + "|" + it.getRecordId(), it);
                }
            }
        }
        if (Objects.nonNull(storyboardId) && Objects.nonNull(recordId))
        {
            merged.put(storyboardId + "|" + recordId, new Item(storyboardId, recordId));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 原始提交条目总数（含字段不全的非法条目，不去重），供上层对比 effective 数量透出「参数缺失」差额。
     */
    public int rawItemCount()
    {
        int cnt = 0;
        if (Objects.nonNull(items))
        {
            for (Item it : items)
            {
                if (Objects.nonNull(it))
                {
                    cnt++;
                }
            }
        }
        if (Objects.nonNull(storyboardId) || Objects.nonNull(recordId))
        {
            cnt++;
        }
        return cnt;
    }

    /**
     * 批量条目：一对分镜 ID + 生成记录 ID。
     */
    @Data
    public static class Item
    {
        /** 分镜 ID */
        private Long storyboardId;

        /** 生成记录 ID */
        private Long recordId;

        public Item()
        {
        }

        public Item(Long storyboardId, Long recordId)
        {
            this.storyboardId = storyboardId;
            this.recordId = recordId;
        }
    }
}
