package com.aid.asset.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergedAssetPagePlannerTest {

    @Test
    void shouldCrossRecommendedAndCustomBoundary() {
        List<MergedAssetPagePlanner.Slice> slices = MergedAssetPagePlanner.plan(1, 4, 2, 3, 4);

        assertEquals(2, slices.size());
        assertSlice(slices.get(0), MergedAssetPagePlanner.Segment.OFFICIAL_RECOMMENDED, 1, 1);
        assertSlice(slices.get(1), MergedAssetPagePlanner.Segment.CUSTOM, 0, 3);
    }

    @Test
    void shouldCrossCustomAndOfficialNormalBoundary() {
        List<MergedAssetPagePlanner.Slice> slices = MergedAssetPagePlanner.plan(4, 4, 2, 3, 4);

        assertEquals(2, slices.size());
        assertSlice(slices.get(0), MergedAssetPagePlanner.Segment.CUSTOM, 2, 1);
        assertSlice(slices.get(1), MergedAssetPagePlanner.Segment.OFFICIAL_NORMAL, 0, 3);
    }

    @Test
    void shouldReturnEmptyWhenOffsetExceedsTotal() {
        assertTrue(MergedAssetPagePlanner.plan(9, 5, 2, 3, 4).isEmpty());
    }

    @Test
    void shouldKeepLongOffsetWithoutOverflow() {
        List<MergedAssetPagePlanner.Slice> slices = MergedAssetPagePlanner.plan(
                3_000_000_000L, 20, 4_000_000_000L, 0, 0);

        assertEquals(1, slices.size());
        assertSlice(slices.get(0), MergedAssetPagePlanner.Segment.OFFICIAL_RECOMMENDED,
                3_000_000_000L, 20);
    }

    private void assertSlice(MergedAssetPagePlanner.Slice slice,
                             MergedAssetPagePlanner.Segment segment,
                             long offset, int limit) {
        assertEquals(segment, slice.getSegment());
        assertEquals(offset, slice.getOffset());
        assertEquals(limit, slice.getLimit());
    }
}
