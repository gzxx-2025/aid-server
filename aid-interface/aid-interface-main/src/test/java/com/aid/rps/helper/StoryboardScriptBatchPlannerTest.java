package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class StoryboardScriptBatchPlannerTest
{
    private final StoryboardScriptBatchPlanner planner = new StoryboardScriptBatchPlanner();

    @Test
    void canonicalizesBlankChunksAndAssignsContinuousIndexesInSourceOrder()
    {
        List<String> canonical = planner.canonicalizeScriptChunks(
                List.of("第一段", "  ", "第二段", "\n", "第三段"));
        List<StoryboardScriptBatchPlanner.BatchPlanItem> plans =
                planner.planScriptChunks(canonical);

        assertEquals(List.of("第一段", "第二段", "第三段"), canonical);
        assertEquals(List.of(0, 1, 2), plans.stream()
                .map(StoryboardScriptBatchPlanner.BatchPlanItem::getBatchIndex).toList());
        assertEquals(List.of("第一段", "第二段", "第三段"), plans.stream()
                .map(StoryboardScriptBatchPlanner.BatchPlanItem::getMergedPlotContent).toList());
    }
}
