package com.aid.billing.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedanceSqlContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String migration;
    private static String init;
    private static String privateSql;

    @BeforeAll
    static void loadSql() throws Exception {
        migration = Files.readString(findRepoFile("sql/v1.0.0-beta.6.sql"), StandardCharsets.UTF_8);
        init = Files.readString(findRepoFile("sql/aid-init.sql"), StandardCharsets.UTF_8);
        privateSql = Files.readString(findRepoFile(
                "sql/private/v1.0.0-beta.6-seedance25-operations.sql"), StandardCharsets.UTF_8);
    }

    @Test
    void officialTokenPriceGridsArePersistedForSeedance20And25() throws Exception {
        for (String sql : Set.of(migration, init, privateSql)) {
            JsonNode rule25 = rule(sql, "seedance25_token_rule");
            assertEquals("TOKEN", rule25.path("meterType").asText());
            assertEquals(Set.of("480P", "720P"), dimensionKeys(rule25));
            assertPrices(rule25, Map.of(
                    "SEEDANCE25_480P_INVIDEO", 42,
                    "SEEDANCE25_720P_INVIDEO", 42,
                    "SEEDANCE25_480P", 70,
                    "SEEDANCE25_720P", 70,
                    "SEEDANCE25_FALLBACK", 70));

            JsonNode rule20 = rule(sql, "seedance20_token_rule");
            assertEquals("TOKEN", rule20.path("meterType").asText());
            assertPrices(rule20, Map.of(
                    "SEEDANCE20_480P_INVIDEO", 28,
                    "SEEDANCE20_720P_INVIDEO", 28,
                    "SEEDANCE20_1080P_INVIDEO", 31,
                    "SEEDANCE20_4K_INVIDEO", 16,
                    "SEEDANCE20_480P", 46,
                    "SEEDANCE20_720P", 46,
                    "SEEDANCE20_1080P", 51,
                    "SEEDANCE20_4K", 26,
                    "SEEDANCE20_FALLBACK", 51));
        }
        assertTrue(migration.contains("'$.settleRule.settleMode','REFUND_ONLY'"));
        assertTrue(migration.contains("'$.videoTokenEstimate.fallbackResolution','720P'"));
        assertEquals("REFUND_ONLY", rule(init, "seedance25_token_rule")
                .path("settleRule").path("settleMode").asText());
        assertEquals("720P", rule(init, "seedance25_token_rule")
                .path("videoTokenEstimate").path("fallbackResolution").asText());
        assertEquals("REFUND_ONLY", rule(privateSql, "seedance25_token_rule")
                .path("settleRule").path("settleMode").asText());
    }

    @Test
    void sixDisabledScenesUseOneOfficialModelAndExpectedMultipliers() {
        String realCode = "doubao-seedance-2-5-260628";
        for (String code : Set.of("text", "first-frame", "first-last-frame", "reference", "edit", "extend")) {
            String modelCode = "doubao-seedance-2.5-" + code;
            Pattern row = Pattern.compile("(?s)\\(@seedance_provider_id,'" + Pattern.quote(modelCode)
                    + "','" + realCode + "'.{0,600}?'video','[^']+',0,1,.{0,300}?'seedance-video',\\d+,'1','0'");
            assertTrue(row.matcher(migration).find(), modelCode);
            Pattern onlineRow = Pattern.compile("(?s)\\(@seedance_provider_id,'" + Pattern.quote(modelCode)
                    + "','" + realCode + "'.{0,600}?'video','[^']+',0,1\\.1,.{0,300}?'seedance-video',\\d+,'1','0'");
            assertTrue(onlineRow.matcher(privateSql).find(), "online " + modelCode);
            assertTrue(init.contains("'" + modelCode + "','" + realCode + "'"), modelCode);
        }
        assertEquals(6, Pattern.compile("@seedance_provider_id,'doubao-seedance-2\\.5-")
                .matcher(migration).results().count());
        assertTrue(privateSql.contains("`billing_multiplier`=1.1000"));
        assertTrue(privateSql.contains("AND `billing_multiplier`=1.0000"));
        assertTrue(privateSql.contains("WHERE `model_code`='doubao-seedance-2.0'"));
        assertTrue(privateSql.contains("WHERE `model_code`='doubao-seedance-2.0-fast'"));
        assertTrue(privateSql.contains("START TRANSACTION;"));
        assertTrue(privateSql.contains("COMMIT;"));
        assertFalse(privateSql.contains("GPT_IMAGE_2"));
        assertFalse(privateSql.contains("MINIMAX_H3"));
        assertFalse(privateSql.contains("KLING30"));
    }

    @Test
    void migrationAndInitShareOfficialMaterialLimits() {
        for (String sql : Set.of(migration, init, privateSql)) {
            assertTrue(sql.contains("\"referenceVideoMaxFileSizeMb\":200"));
            assertTrue(sql.contains("\"referenceImageMinDimensionPixels\":300"));
            assertTrue(sql.contains("\"referenceImageMaxDimensionPixels\":6000"));
            assertTrue(sql.contains("\"referenceVideoMinFps\":24"));
            assertTrue(sql.contains("\"referenceVideoMaxFps\":60"));
            assertTrue(sql.contains("\"maxReferenceMaterials\":50"));
            assertTrue(sql.contains("\"videoScenario\":\"edit\""));
            assertTrue(sql.contains("\"referenceVideoMinDurationSeconds\":4"));
        }
    }

    private static Set<String> dimensionKeys(JsonNode rule) {
        Map<String, Boolean> keys = new HashMap<>();
        rule.path("videoTokenEstimate").path("dimensions").fieldNames()
                .forEachRemaining(key -> keys.put(key, true));
        return keys.keySet();
    }

    private static void assertPrices(JsonNode rule, Map<String, Integer> expected) {
        Map<String, Integer> actual = new HashMap<>();
        for (JsonNode sku : rule.path("skus")) {
            actual.put(sku.path("skuCode").asText(), sku.path("outputPricePerMillion").asInt());
        }
        assertEquals(expected, actual);
    }

    private static JsonNode rule(String sql, String variable) throws Exception {
        Matcher matcher = Pattern.compile("(?m)^SET @" + Pattern.quote(variable) + " := '(.*)';\\r?$")
                .matcher(sql);
        assertTrue(matcher.find(), variable);
        return MAPPER.readTree(matcher.group(1).replace("\\\"", "\""));
    }

    private static Path findRepoFile(String relative) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }
}
