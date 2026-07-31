package com.aid.compose.service.impl;

import com.aid.aid.domain.AidGenRecord;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertSame;

class StoryboardVideoSelectionResolverImplTest {

    private final StoryboardVideoSelectionResolverImpl resolver =
            new StoryboardVideoSelectionResolverImpl(null, null);

    @Test
    void shouldUseOriginalWhenItWasSelectedAfterOldComposeVideo() {
        AidGenRecord compose = record(20L, 1_000L);
        AidGenRecord original = record(10L, 2_000L);

        assertSame(original, resolver.chooseLatestSelection(original, compose));
    }

    @Test
    void shouldUseComposeWhenItWasSelectedAfterOriginalVideo() {
        AidGenRecord original = record(10L, 1_000L);
        AidGenRecord compose = record(20L, 2_000L);

        assertSame(compose, resolver.chooseLatestSelection(original, compose));
    }

    private AidGenRecord record(Long id, long updateMillis) {
        AidGenRecord record = new AidGenRecord();
        record.setId(id);
        record.setUpdateTime(new Date(updateMillis));
        return record;
    }
}
