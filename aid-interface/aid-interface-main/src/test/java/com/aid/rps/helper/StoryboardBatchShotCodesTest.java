package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class StoryboardBatchShotCodesTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void serializesManyDerivedSceneCodesBeyondLegacyVarcharLimit() throws Exception
    {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 60; i++)
        {
            codes.add(String.format("%03d", i));
        }

        String json = OBJECT_MAPPER.writeValueAsString(codes);

        assertTrue(json.length() > 255);
        assertEquals(codes, OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() { }));
    }
}
