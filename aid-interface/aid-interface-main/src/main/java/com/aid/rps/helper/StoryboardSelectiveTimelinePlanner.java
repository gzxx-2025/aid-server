package com.aid.rps.helper;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 计算选择性覆盖分镜在既有时间线中的稳定插入位置。
 *
 * @author 视觉AID
 */
public final class StoryboardSelectiveTimelinePlanner
{
    private static final long TAIL_RESERVE = 5001L;

    private StoryboardSelectiveTimelinePlanner()
    {
    }

    /**
     * 计算选择范围的首个原位置与尾部预留区。
     *
     * @param timeline 当前时间线
     * @param selectedSceneIds 选择覆盖的场景ID
     * @return 选择性覆盖位置计划
     */
    public static Plan plan(List<ShotPosition> timeline, Set<Long> selectedSceneIds)
    {
        long maxSortOrder = 0L;
        Long firstSelectedSortOrder = null;
        int selectedSegmentCount = 0;
        boolean previousSelected = false;
        if (timeline != null)
        {
            for (ShotPosition shot : timeline)
            {
                if (Objects.isNull(shot))
                {
                    continue;
                }
                long sortOrder = Math.max(0L, shot.sortOrder());
                maxSortOrder = Math.max(maxSortOrder, sortOrder);
                boolean selected = selectedSceneIds != null && selectedSceneIds.contains(shot.sceneId());
                if (selected && !previousSelected)
                {
                    selectedSegmentCount++;
                }
                previousSelected = selected;
                if (selected
                        && (Objects.isNull(firstSelectedSortOrder)
                        || sortOrder < firstSelectedSortOrder))
                {
                    firstSelectedSortOrder = sortOrder;
                }
            }
        }
        long insertionBase = Objects.isNull(firstSelectedSortOrder)
                ? maxSortOrder : Math.max(0L, firstSelectedSortOrder - 1L);
        return new Plan(insertionBase, TAIL_RESERVE, selectedSegmentCount);
    }

    public record ShotPosition(Long sceneId, long sortOrder)
    {
    }

    public record Plan(long insertionBase, long tailReserve, int selectedSegmentCount)
    {
        public boolean hasDisjointSelectedSegments()
        {
            return selectedSegmentCount > 1;
        }

        public long generatedSortOrder(int generatedIndex)
        {
            return insertionBase + generatedIndex + 1L;
        }

        public long retainedSortOrder(long originalSortOrder)
        {
            return originalSortOrder > insertionBase
                    ? originalSortOrder + tailReserve : originalSortOrder;
        }
    }
}
