package com.aid.asset.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并素材三段式分页规划器。
 *
 * @author 视觉AID
 */
public final class MergedAssetPagePlanner {

    private MergedAssetPagePlanner() {
    }

    /** 合并列表分段。 */
    public enum Segment {
        OFFICIAL_RECOMMENDED,
        CUSTOM,
        OFFICIAL_NORMAL
    }

    /**
     * 将全局分页窗口换算为各数据段的局部窗口。
     *
     * @param from 全局起始偏移
     * @param pageSize 分页大小
     * @param recommendedCount 官方推荐数量
     * @param customCount 个人素材数量
     * @param normalCount 官方非推荐数量
     * @return 有序局部切片
     */
    public static List<Slice> plan(long from, int pageSize, long recommendedCount,
                                   long customCount, long normalCount) {
        List<Slice> result = new ArrayList<>();
        long cursor = Math.max(0, from);
        int remaining = Math.max(0, pageSize);
        remaining = append(result, Segment.OFFICIAL_RECOMMENDED, cursor, remaining, recommendedCount);
        cursor = Math.max(0, cursor - recommendedCount);
        remaining = append(result, Segment.CUSTOM, cursor, remaining, customCount);
        cursor = Math.max(0, cursor - customCount);
        append(result, Segment.OFFICIAL_NORMAL, cursor, remaining, normalCount);
        return result;
    }

    private static int append(List<Slice> result, Segment segment, long offset,
                              int remaining, long segmentCount) {
        if (remaining <= 0 || offset >= segmentCount) {
            return remaining;
        }
        int limit = (int) Math.min(remaining, segmentCount - offset);
        result.add(new Slice(segment, offset, limit));
        return remaining - limit;
    }

    /** 分段局部分页窗口。 */
    @Data
    @AllArgsConstructor
    public static class Slice {
        private Segment segment;
        private long offset;
        private int limit;
    }
}
