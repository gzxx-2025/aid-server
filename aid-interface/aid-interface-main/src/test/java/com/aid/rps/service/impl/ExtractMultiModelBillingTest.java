package com.aid.rps.service.impl;

import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskBillingSnapshotService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.model.ExistingAssetLib;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class ExtractMultiModelBillingTest {

    private static final Long TASK_ID = 100L;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(AidMediaTask.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @Test
    void shouldAggregateCurrentRunUsageByModel() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        ReflectionTestUtils.setField(service, "billingSnapshotService",
                mock(IAidExtractTaskBillingSnapshotService.class));

        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setBillingSnapshotJson(JSONUtil.toJsonStr(Map.of(
                "batchType", "asset_extract_multi_model",
                "usageStartMediaTaskId", 11L,
                "items", List.of())));
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);

        AidMediaTask flash = mediaTask(12L, "flash", "SUCCEEDED", 100, 20);
        AidMediaTask pro = mediaTask(13L, "pro", "SUCCEEDED", null, null);
        when(mediaTaskMapper.selectList(any())).thenReturn(List.of(flash, pro));

        Map<String, Object> usage = service.aggregateTokenUsage(TASK_ID);

        assertEquals(Boolean.TRUE, usage.get("aggregation_complete"));
        assertEquals(100, usage.get("input_tokens"));
        assertEquals(20, usage.get("output_tokens"));
        @SuppressWarnings("unchecked")
        Map<String, Object> modelUsages = (Map<String, Object>) usage.get("model_usages");
        @SuppressWarnings("unchecked")
        Map<String, Object> flashUsage = (Map<String, Object>) modelUsages.get("flash");
        @SuppressWarnings("unchecked")
        Map<String, Object> proUsage = (Map<String, Object>) modelUsages.get("pro");
        assertEquals(1, flashUsage.get("successful_call_count"));
        assertEquals(1, flashUsage.get("usage_call_count"));
        assertEquals(1, proUsage.get("successful_call_count"));
        assertEquals(0, proUsage.get("usage_call_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callUsages = (List<Map<String, Object>>) usage.get("call_usages");
        assertEquals(2, callUsages.size());
        assertEquals(12L, callUsages.get(0).get("media_task_id"));
        assertEquals(13L, callUsages.get(1).get("media_task_id"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mediaTaskMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<?> query = queryCaptor.getValue();
        assertTrue(query.getSqlSegment().contains("id >"));
        assertTrue(query.getParamNameValuePairs().containsValue(11L));
    }

    @Test
    void shouldTreatEmptyCurrentRunAsCompleteZeroUsage() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        ReflectionTestUtils.setField(service, "billingSnapshotService",
                mock(IAidExtractTaskBillingSnapshotService.class));

        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setBillingSnapshotJson(batchSnapshot(List.of()));
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);
        when(mediaTaskMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> usage = service.aggregateTokenUsage(TASK_ID);

        assertEquals(Boolean.TRUE, usage.get("aggregation_complete"));
        assertEquals(0, usage.get("successful_call_count"));
        assertEquals(0, usage.get("usage_call_count"));
        assertEquals(Map.of(), usage.get("model_usages"));
    }

    @Test
    void shouldMarkAggregationIncompleteWhileAnyMediaCallIsNonTerminal() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        ReflectionTestUtils.setField(service, "billingSnapshotService",
                mock(IAidExtractTaskBillingSnapshotService.class));

        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setBillingSnapshotJson(batchSnapshot(List.of()));
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);
        when(mediaTaskMapper.selectList(any())).thenReturn(List.of(
                mediaTask(12L, "flash", "PENDING", null, null)));

        Map<String, Object> usage = service.aggregateTokenUsage(TASK_ID);

        assertEquals(Boolean.FALSE, usage.get("aggregation_complete"));
    }

    @Test
    void shouldAggregateSuccessfulAndFailedUsageCallsWithoutLosingTheirOverlap() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        ReflectionTestUtils.setField(service, "billingSnapshotService",
                mock(IAidExtractTaskBillingSnapshotService.class));

        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setBillingSnapshotJson(batchSnapshot(List.of()));
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);
        when(mediaTaskMapper.selectList(any())).thenReturn(List.of(
                mediaTask(1L, "flash", "SUCCEEDED", null, null),
                mediaTask(2L, "flash", "SUCCEEDED", 100, 20),
                mediaTask(3L, "flash", "FAILED", 30, 5)));

        Map<String, Object> usage = service.aggregateTokenUsage(TASK_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> modelUsages = (Map<String, Object>) usage.get("model_usages");
        @SuppressWarnings("unchecked")
        Map<String, Object> flashUsage = (Map<String, Object>) modelUsages.get("flash");

        assertEquals(2, flashUsage.get("successful_call_count"));
        assertEquals(2, flashUsage.get("usage_call_count"));
        assertEquals(1, flashUsage.get("successful_usage_call_count"));
    }

    @Test
    void shouldRefundUncalledGroupsAndProrateSuccessfulFixedCalls() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        ReflectionTestUtils.setField(service, "billingAmountCalculator", mock(BillingAmountCalculator.class));

        String snapshot = batchSnapshot(List.of(
                item("flash", "8", 4, "2", fixedSnapshot()),
                item("pro", "3", 1, "3", fixedSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(1, 0, 0, 0, 0)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("11"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("2")));
        assertFalse(result.tokenOverageAllowed());
    }

    @Test
    void shouldKeepOldUsagePayloadOnConservativePreHoldFallback() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        ReflectionTestUtils.setField(service, "billingAmountCalculator", mock(BillingAmountCalculator.class));
        String snapshot = batchSnapshot(List.of(item("flash", "8", 4, "2", fixedSnapshot())));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("8"), Map.of("input_tokens", 10));

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("8")));
    }

    @Test
    void shouldRejectCompleteMarkerWithoutModelUsageProtocol() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        ReflectionTestUtils.setField(service, "billingAmountCalculator", mock(BillingAmountCalculator.class));
        String snapshot = batchSnapshot(List.of(item("flash", "8", 4, "2", fixedSnapshot())));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("8"), Map.of("aggregation_complete", true));

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("8")));
        assertFalse(JSONUtil.parseObj(result.settledSnapshotJson()).getBool("aggregation_complete"));
    }

    @Test
    void shouldAllowExplicitEmptyModelUsageAsZeroCalls() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        ReflectionTestUtils.setField(service, "billingAmountCalculator", mock(BillingAmountCalculator.class));
        String snapshot = batchSnapshot(List.of(item("flash", "8", 4, "2", fixedSnapshot())));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("8"), completeUsage(Map.of()));

        assertEquals(0, result.actualAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void shouldDeferSettlementForExplicitIncompleteAggregation() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        IAidExtractTaskBillingSnapshotService snapshotService =
                mock(IAidExtractTaskBillingSnapshotService.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "billingSnapshotService", snapshotService);

        String snapshot = batchSnapshot(List.of(item("flash", "8", 4, "2", fixedSnapshot())));
        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setBillingStatus("FROZEN");
        task.setFrozenAmount(new BigDecimal("8"));
        task.setBillingSnapshotJson(snapshot);
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);
        when(snapshotService.getSnapshotJson(TASK_ID, "FROZEN")).thenReturn(null);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("aggregation_complete", false);
        usage.put("model_usages", Map.of());

        assertFalse(service.settleBilling(TASK_ID, 7L, usage));
        verify(taskService, never()).getBaseMapper();
    }

    @Test
    void shouldChargeUnionOfSuccessfulAndUsageBearingFixedCalls() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);
        String snapshot = batchSnapshot(List.of(item("flash", "6", 3, "2", fixedSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(1, 1, 0, 100, 10)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("6"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("4")));
        assertFalse(result.tokenOverageAllowed());
        verifyNoInteractions(calculator);
    }

    @Test
    void shouldKeepFixedPricingEvenWhenPositiveTokenUsageExists() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);
        String snapshot = batchSnapshot(List.of(item("flash", "6", 3, "2", fixedSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(2, 2, 2, 100, 50)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("6"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("4")));
        assertFalse(result.tokenOverageAllowed());
        verifyNoInteractions(calculator);
    }

    @Test
    void shouldUseCallUnionForTokenPricingWhenProviderReturnsNoTokenCounts() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);
        String snapshot = batchSnapshot(List.of(item("flash", "6", 3, "2", tokenSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(1, 1, 0, 0, 0)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("6"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("4")));
        assertFalse(result.tokenOverageAllowed());
        verifyNoInteractions(calculator);
    }

    @Test
    void shouldAddPerCallFallbackForSuccessfulTokenCallsMissingUsage() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);
        BillingCalcResult calculated = BillingCalcResult.sku(
                "token", "token", new BigDecimal("3"), tokenSnapshot());
        when(calculator.calculateSettleAmount(any(), any(), any())).thenReturn(calculated);
        String snapshot = batchSnapshot(List.of(item("flash", "8", 4, "2", tokenSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(2, 1, 1, 100, 50)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("8"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("5")));
        assertFalse(result.tokenOverageAllowed());
    }

    @Test
    void shouldAllowTokenOverageOnlyWithCompleteSuccessfulUsage() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);

        BillingSnapshot settled = tokenSnapshot();
        BillingCalcResult calculated = BillingCalcResult.sku(
                "token", "token", new BigDecimal("6"), settled);
        when(calculator.calculateSettleAmount(any(), any(), any())).thenReturn(calculated);

        String snapshot = batchSnapshot(List.of(
                item("flash", "5", 2, "2.5", tokenSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(2, 2, 2, 100, 50)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("5"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("6")));
        assertTrue(result.tokenOverageAllowed());
    }

    @Test
    void shouldSettleTieredTokenUsagePerMediaCallInsteadOfAggregatingTokens() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);
        when(calculator.calculateSettleAmount(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = invocation.getArgument(2, Map.class);
            int inputTokens = (Integer) usage.get("input_tokens");
            BillingSnapshot settled = tokenSnapshot();
            settled.setSkuCode(inputTokens < 500 ? "LOW" : "HIGH");
            settled.setSkuName(inputTokens < 500 ? "低档" : "高档");
            BigDecimal amount = inputTokens < 500 ? new BigDecimal("2") : new BigDecimal("8");
            return BillingCalcResult.sku(settled.getSkuCode(), settled.getSkuName(), amount, settled);
        });

        String snapshot = batchSnapshot(List.of(perCallItem(
                "flash", "10", tokenSnapshot(), List.of("5", "5"))));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(2, 2, 2, 1100, 100)));
        usage.put("call_usages", List.of(
                callUsage(21L, "flash", true, true, 100, 20),
                callUsage(22L, "flash", true, true, 1000, 80)));

        ExtractBillingServiceImpl.MultiModelSettleResult result = service.calculateMultiModelSettle(
                snapshot, new BigDecimal("10"), usage);

        assertEquals(0, result.actualAmount().compareTo(new BigDecimal("10")));
        assertFalse(result.tokenOverageAllowed());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> usageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(calculator, times(2)).calculateSettleAmount(any(), any(), usageCaptor.capture());
        assertEquals(100, usageCaptor.getAllValues().get(0).get("input_tokens"));
        assertEquals(1000, usageCaptor.getAllValues().get(1).get("input_tokens"));
    }

    @Test
    void shouldEstimateEverySceneAndPropCallIndependently() {
        AssetExtractServiceImpl service = new AssetExtractServiceImpl();
        IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        IAidComicProjectService projectService = mock(IAidComicProjectService.class);
        AssetExtractHelper helper = mock(AssetExtractHelper.class);
        BillingAmountCalculator calculator = mock(BillingAmountCalculator.class);
        ReflectionTestUtils.setField(service, "aiModelConfigService", modelConfigService);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "projectService", projectService);
        ReflectionTestUtils.setField(service, "helper", helper);
        ReflectionTestUtils.setField(service, "billingAmountCalculator", calculator);

        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("tiered");
        config.setBillingMode("SKU");
        when(modelConfigService.selectByModelCode("tiered")).thenReturn(config);
        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setProjectId(1L);
        task.setEpisodeId(0L);
        task.setUserId(7L);
        task.setInputSnapshot("{\"agentCodes\":{\"scene\":\"scene_agent\",\"prop\":\"prop_agent\"}}");
        when(taskService.selectAidExtractTaskById(TASK_ID)).thenReturn(task);
        AidComicProject project = new AidComicProject();
        project.setId(1L);
        project.setProjectType("movie");
        when(projectService.selectAidComicProjectById(1L)).thenReturn(project);
        when(helper.loadExistingAssets(1L, null)).thenReturn(new ExistingAssetLib());
        when(helper.loadScriptContent(1L, 0L, 7L)).thenReturn("script");
        when(helper.chunkContent("script", 3000)).thenReturn(List.of("chunk-1", "chunk-2"));
        when(helper.loadPromptByName("scene_agent")).thenReturn("scene prompt");
        when(helper.loadPromptByName("prop_agent")).thenReturn("prop prompt");
        when(helper.estimateLlmInputCharsWithInputs(any(), any(), eq("tiered")))
                .thenReturn(100, 200, 300, 400);
        when(calculator.calculatePreHoldAmount(eq(config), any())).thenAnswer(invocation -> {
            BillingSnapshot snapshot = tokenSnapshot();
            snapshot.setMeterType("TOKEN");
            return BillingCalcResult.sku("tier", "tier", BigDecimal.ONE, snapshot);
        });

        Object estimate = ReflectionTestUtils.invokeMethod(service, "estimateExtractCost",
                TASK_ID, "tiered", List.of("scene", "prop"));
        BigDecimal amount = ReflectionTestUtils.invokeMethod(estimate, "amount");
        List<?> calls = ReflectionTestUtils.invokeMethod(estimate, "callEstimates");

        assertEquals(0, amount.compareTo(new BigDecimal("4")));
        assertEquals(4, calls.size());
        verify(calculator, times(4)).calculatePreHoldAmount(eq(config), any());
    }

    @Test
    void shouldFillLegacyAndPartialModelMapsWithoutOverwritingModernValues() {
        AssetExtractServiceImpl service = new AssetExtractServiceImpl();
        IAidExtractTaskService taskService = mock(IAidExtractTaskService.class);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);

        AidExtractTask legacy = new AidExtractTask();
        legacy.setInputSnapshot("character,scene,prop");
        when(taskService.selectAidExtractTaskById(1L)).thenReturn(legacy);
        Map<String, String> legacyModels = service.resolveExtractModelCodesByTypeFromSnapshot(1L, "legacy");
        assertEquals(Map.of("character", "legacy", "scene", "legacy", "prop", "legacy"), legacyModels);

        AidExtractTask modern = new AidExtractTask();
        modern.setInputSnapshot("{\"extractTypes\":[\"scene\",\"prop\"],"
                + "\"modelCodes\":{\"scene\":\"flash\"}}");
        when(taskService.selectAidExtractTaskById(2L)).thenReturn(modern);
        Map<String, String> modernModels = service.resolveExtractModelCodesByTypeFromSnapshot(2L, "pro");
        assertEquals("flash", modernModels.get("scene"));
        assertEquals("pro", modernModels.get("prop"));
    }

    @Test
    void shouldRouteStaleMultiModelBillingThroughUsageAwareSettlement() {
        ExtractBillingServiceImpl service = spy(new ExtractBillingServiceImpl());
        IAidExtractTaskBillingSnapshotService snapshotService =
                mock(IAidExtractTaskBillingSnapshotService.class);
        ReflectionTestUtils.setField(service, "billingSnapshotService", snapshotService);

        String snapshot = batchSnapshot(List.of(item("flash", "2", 1, "2", fixedSnapshot())));
        Map<String, Object> usage = completeUsage(Map.of(
                "flash", modelUsage(1, 0, 0, 0, 0)));
        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setUserId(7L);
        task.setBillingSnapshotJson(snapshot);
        when(snapshotService.getSnapshotJson(TASK_ID, "FROZEN")).thenReturn(null);
        doReturn(usage).when(service).aggregateTokenUsage(TASK_ID);
        doReturn(true).when(service).settleBilling(TASK_ID, 7L, usage);

        assertTrue(service.settleStaleBilling(task));
        verify(service).settleBilling(TASK_ID, 7L, usage);
        verify(service, never()).settleBilling(eq(TASK_ID), eq(7L));
    }

    @Test
    void shouldRestoreLegacyInlineSuccessSnapshotAsSettled() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();

        assertEquals("SETTLED", service.resolvePriorSnapshotStage(
                "SUCCESS", "{\"meterType\":\"TOKEN\"}", "{\"meterType\":\"TOKEN\"}"));
    }

    @Test
    void shouldPreferExplicitPriorSnapshotStage() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();
        String snapshotRef = JSONUtil.toJsonStr(Map.of(
                "snapshotTable", "aid_extract_task_billing_snapshot",
                "snapshotStage", "FROZEN"));

        assertEquals("FROZEN", service.resolvePriorSnapshotStage(
                "SUCCESS", snapshotRef, "{\"meterType\":\"TOKEN\"}"));
    }

    @Test
    void shouldOnlyStartResumeRollbackBeforeWorkerClaim() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();

        assertTrue(service.canTransitionResumeRollbackToRequired(
                "PREPARED", "PENDING", false));
        assertTrue(service.canTransitionResumeRollbackToRequired(
                "FUNDS_FROZEN", "QUEUED", false));
        assertTrue(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_INTENT", "PENDING", false));
        assertFalse(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_CONFIRMED", "PENDING", false));
        assertTrue(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_CONFIRMED", "QUEUED", true));

        // worker 已领取或已经执行回同名终态时，首次请求不得再恢复旧周期并退款。
        assertFalse(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_INTENT", "PROCESSING", false));
        assertFalse(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_INTENT", "PARTIAL_FAILED", false));
        assertFalse(service.canTransitionResumeRollbackToRequired(
                "DISPATCH_CONFIRMED", "CANCELLED", true));
    }

    @Test
    void shouldAcceptPriorStatusOnlyAfterRollbackRequiredPersisted() {
        ExtractBillingServiceImpl service = new ExtractBillingServiceImpl();

        assertTrue(service.isIdempotentResumeRollbackRequest(
                "ROLLBACK_REQUIRED", "PARTIAL_FAILED", "PARTIAL_FAILED"));
        assertTrue(service.isIdempotentResumeRollbackRequest(
                "ROLLBACK_REQUIRED", "CANCELLED", "CANCELLED"));
        assertFalse(service.isIdempotentResumeRollbackRequest(
                "DISPATCH_INTENT", "PARTIAL_FAILED", "PARTIAL_FAILED"));
        assertFalse(service.isIdempotentResumeRollbackRequest(
                "ROLLBACK_REQUIRED", "PROCESSING", "PARTIAL_FAILED"));
    }

    private static AidMediaTask mediaTask(Long id, String modelCode, String status,
                                          Integer inputTokens, Integer outputTokens) {
        AidMediaTask task = new AidMediaTask();
        task.setId(id);
        task.setModelName(modelCode);
        task.setStatus(status);
        if (inputTokens != null || outputTokens != null) {
            BillingSnapshot snapshot = new BillingSnapshot();
            snapshot.setActualInputTokens(inputTokens);
            snapshot.setActualOutputTokens(outputTokens);
            task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        }
        return task;
    }

    private static String batchSnapshot(List<Map<String, Object>> items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("batchType", "asset_extract_multi_model");
        root.put("items", items);
        return JSONUtil.toJsonStr(root);
    }

    private static Map<String, Object> item(String modelCode, String preHoldAmount,
                                            int expectedCallCount, String unitPreHoldAmount,
                                            BillingSnapshot snapshot) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("modelCode", modelCode);
        item.put("preHoldAmount", new BigDecimal(preHoldAmount));
        item.put("expectedCallCount", expectedCallCount);
        item.put("unitPreHoldAmount", new BigDecimal(unitPreHoldAmount));
        item.put("snapshot", snapshot);
        return item;
    }

    private static Map<String, Object> perCallItem(String modelCode, String preHoldAmount,
                                                   BillingSnapshot snapshot,
                                                   List<String> callAmounts) {
        Map<String, Object> item = item(modelCode, preHoldAmount, callAmounts.size(),
                new BigDecimal(preHoldAmount).divide(BigDecimal.valueOf(callAmounts.size())).toPlainString(),
                snapshot);
        List<Map<String, Object>> calls = callAmounts.stream().map(amount -> {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("preHoldAmount", new BigDecimal(amount));
            return call;
        }).toList();
        item.put("callEstimates", calls);
        item.put("billingGranularity", "PER_CALL");
        return item;
    }

    private static Map<String, Object> callUsage(Long mediaTaskId, String modelCode,
                                                  boolean successful, boolean hasUsage,
                                                  int inputTokens, int outputTokens) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("media_task_id", mediaTaskId);
        call.put("model_code", modelCode);
        call.put("successful", successful);
        call.put("has_usage", hasUsage);
        call.put("input_tokens", inputTokens);
        call.put("output_tokens", outputTokens);
        return call;
    }

    private static Map<String, Object> completeUsage(Map<String, Object> modelUsages) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("aggregation_complete", true);
        usage.put("model_usages", modelUsages);
        return usage;
    }

    private static Map<String, Object> modelUsage(int successfulCalls, int usageCalls,
                                                  int successfulUsageCalls,
                                                  int inputTokens, int outputTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("successful_call_count", successfulCalls);
        usage.put("usage_call_count", usageCalls);
        usage.put("successful_usage_call_count", successfulUsageCalls);
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        return usage;
    }

    private static BillingSnapshot fixedSnapshot() {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setMeterType("TOKEN");
        snapshot.setBillingMode("FIXED");
        return snapshot;
    }

    private static BillingSnapshot tokenSnapshot() {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setMeterType("TOKEN");
        snapshot.setBillingMode("SKU");
        snapshot.setBillingRuleJson("{}");
        return snapshot;
    }
}
