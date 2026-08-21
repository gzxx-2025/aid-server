package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StoryboardSelectiveTimelinePlannerTest
{
    @Test
    void replacementStaysAtOriginalMiddlePositionInsteadOfMovingToEnd()
    {
        StoryboardSelectiveTimelinePlanner.Plan plan = StoryboardSelectiveTimelinePlanner.plan(
                List.of(
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(10L, 1L),
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(20L, 2L),
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(30L, 3L)),
                Set.of(20L));

        List<TimelineItem> merged = new ArrayList<>();
        merged.add(new TimelineItem("保留前镜", plan.retainedSortOrder(1L)));
        merged.add(new TimelineItem("替换镜1", plan.generatedSortOrder(0)));
        merged.add(new TimelineItem("替换镜2", plan.generatedSortOrder(1)));
        merged.add(new TimelineItem("保留后镜", plan.retainedSortOrder(3L)));
        merged.sort(Comparator.comparingLong(TimelineItem::sortOrder));

        assertEquals(List.of("保留前镜", "替换镜1", "替换镜2", "保留后镜"),
                merged.stream().map(TimelineItem::name).toList());
        assertFalse(plan.hasDisjointSelectedSegments());
    }

    @Test
    void detectsSelectedScenesSeparatedByRetainedTimelineBlocks()
    {
        StoryboardSelectiveTimelinePlanner.Plan plan = StoryboardSelectiveTimelinePlanner.plan(
                List.of(
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(10L, 1L),
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(20L, 2L),
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(30L, 3L),
                        new StoryboardSelectiveTimelinePlanner.ShotPosition(20L, 4L)),
                Set.of(20L));

        assertTrue(plan.hasDisjointSelectedSegments());
    }

    private record TimelineItem(String name, long sortOrder)
    {
    }
}
