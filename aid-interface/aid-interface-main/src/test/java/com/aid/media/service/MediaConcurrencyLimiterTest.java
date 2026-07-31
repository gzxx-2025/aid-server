package com.aid.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MediaConcurrencyLimiterTest
{
    @Test
    void shouldReadLegacyConcurrencyKeysAtTheirOwnLevel()
    {
        assertEquals(3, MediaConcurrencyLimiter.parseModelConcurrency("{\"modelConcurrency\":3}"));
        assertEquals(5, MediaConcurrencyLimiter.parseProviderConcurrency("{\"providerConcurrency\":5}"));
    }

    @Test
    void shouldPreferUnifiedMaxConcurrency()
    {
        String json = "{\"maxConcurrency\":2,\"modelConcurrency\":7,\"providerConcurrency\":9}";

        assertEquals(2, MediaConcurrencyLimiter.parseModelConcurrency(json));
        assertEquals(2, MediaConcurrencyLimiter.parseProviderConcurrency(json));
    }

    @Test
    void shouldNotMixModelAndProviderLegacyKeys()
    {
        assertEquals(MediaConcurrencyLimiter.UNLIMITED,
                MediaConcurrencyLimiter.parseModelConcurrency("{\"providerConcurrency\":5}"));
        assertEquals(MediaConcurrencyLimiter.UNLIMITED,
                MediaConcurrencyLimiter.parseProviderConcurrency("{\"modelConcurrency\":3}"));
    }
}
