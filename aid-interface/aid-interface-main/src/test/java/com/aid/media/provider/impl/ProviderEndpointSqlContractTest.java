package com.aid.media.provider.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证新装与独立升级脚本的供应商端点配置契约。 */
class ProviderEndpointSqlContractTest {

    private static String initSql;
    private static String beta6Sql;
    private static String operationSql;
    private static String seedance25OperationSql;

    @BeforeAll
    static void loadSql() throws Exception {
        initSql = Files.readString(findRepoFile("sql/aid-init.sql"), StandardCharsets.UTF_8);
        beta6Sql = Files.readString(findRepoFile("sql/v1.0.0-beta.6.sql"), StandardCharsets.UTF_8);
        operationSql = Files.readString(findRepoFile(
                "sql/private/v1.0.0-beta.6-provider-endpoint-operations.sql"), StandardCharsets.UTF_8);
        seedance25OperationSql = Files.readString(findRepoFile(
                "sql/private/v1.0.0-beta.6-seedance25-operations.sql"), StandardCharsets.UTF_8);
    }

    @Test
    void freshInstallRowsUseFinalEndpointValues() {
        for (String line : initSql.lines().toList()) {
            if (line.startsWith("INSERT INTO `aid_ai_model`") || line.startsWith("(@seedance_provider_id")) {
                assertFalse(line.contains("SDK:generateImages"), line);
                assertFalse(line.contains("SDK:createContentGenerationTask"), line);
                assertFalse(line.contains("'/v1beta/models/'"), line);
            }
        }
        assertTrue(initSql.contains("'gpt-image-2','gpt-image-2','GPT Image 2'"));
        assertTrue(initSql.contains("'/v1/images/{operation}','openai-image'"));
        assertTrue(initSql.contains("'jimeng-video-3.0-pro','jimeng-video-3.0-pro'"));
        assertTrue(initSql.contains(",1.0000,'/','jimeng-video'"));
        assertTrue(initSql.contains(",1.0000,'/','jimeng-image'"));
    }

    @Test
    void standaloneScriptIsEndpointOnlyAndRepeatable() {
        assertTrue(operationSql.contains("MODIFY COLUMN `api_suffix` varchar(500)"));
        assertTrue(operationSql.contains("NULLIF(TRIM(m.`api_suffix`),'') IS NULL"));
        assertTrue(operationSql.contains("m.`api_suffix`='SDK:createContentGenerationTask'"));
        assertTrue(operationSql.contains("m.`api_suffix`='SDK:generateImages'"));
        assertTrue(operationSql.contains("'/v1/images/{operation}'"));
        assertTrue(operationSql.contains("START TRANSACTION;"));
        assertTrue(operationSql.contains("COMMIT;"));
        assertFalse(operationSql.contains("billing_rule_json"));
        assertFalse(operationSql.contains("SEEDANCE25_"));
        assertFalse(operationSql.contains("INSERT INTO `aid_ai_model`"));
    }

    @Test
    void beta6UpsertsPreserveCustomGatewayAndEndpointPaths() {
        assertFalse(beta6Sql.contains("`base_url`=VALUES(`base_url`)"));
        assertFalse(beta6Sql.contains("`task_query_suffix`=VALUES(`task_query_suffix`)"));
        assertFalse(beta6Sql.contains("`api_suffix`=VALUES(`api_suffix`)"));
        assertTrue(beta6Sql.contains("WHEN NULLIF(TRIM(`base_url`),'') IS NULL THEN VALUES(`base_url`)"));
        assertTrue(beta6Sql.contains("WHEN NULLIF(TRIM(`task_query_suffix`),'') IS NULL THEN VALUES(`task_query_suffix`)"));
        assertTrue(beta6Sql.contains("WHEN NULLIF(TRIM(`api_suffix`),'') IS NULL OR `api_suffix` LIKE 'SDK:%'"));
        assertFalse(seedance25OperationSql.contains("`api_suffix`=VALUES(`api_suffix`)"));
        assertTrue(seedance25OperationSql.contains(
                "WHEN NULLIF(TRIM(`api_suffix`),'') IS NULL OR `api_suffix` LIKE 'SDK:%'"));
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
