package com.aid.media.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaBatchGenerateRequest;
import com.aid.media.enums.MediaType;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.service.IAiModelConfigService;

class MediaGenerationServiceImplValidationTest {

    @Test
    void acceptsMatchingAudioModel() {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        AiModelConfigVo model = model("audio");

        AiModelConfigVo result = ReflectionTestUtils.invokeMethod(
                service, "requireModelType", model, MediaType.AUDIO);

        assertEquals(model, result);
    }

    @Test
    void rejectsImageModelForAudioGeneration() {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        AiModelConfigVo model = model("image");

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireModelType", model, MediaType.AUDIO));
    }

    @Test
    void tailAliasesCountOnlyTheFirstNonBlankValueBeforeMinValidation() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setImageUrl("https://cdn.test/first.png");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("lastFrameImageUrl", " ");
        options.put("endImageUrl", "https://cdn.test/last.png");
        options.put("end_image_url", "https://cdn.test/duplicate-tail.png");
        request.setOptions(options);
        AiModelConfigVo model = model("video");
        model.setCapabilityJson("{\"minReferenceImages\":2}");

        int actual = MediaGenerationServiceImpl.countVideoRequestReferenceImages(request);

        assertEquals(2, actual);
        MediaGenerationServiceImpl.validateMinReferenceImages(model, actual);

        options.put("endImageUrl", " ");
        options.put("end_image_url", " ");
        int missingTail = MediaGenerationServiceImpl.countVideoRequestReferenceImages(request);
        assertEquals(1, missingTail);
        assertThrows(ServiceException.class,
            () -> MediaGenerationServiceImpl.validateMinReferenceImages(model, missingTail));
    }

    @Test
    void singleKlingFirstLastRejectsGenericReferencesBeforeTaskCreation() {
        MediaGenerationServiceImpl service = mediaServiceWithModel(
            klingModel("kling-first-last", KlingConstants.SCENARIO_OMNI_FIRST_LAST, 2));
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest("kling-first-last");
        request.setOptions(Map.of("referenceImages", List.of(
            "https://cdn.test/a.png", "https://cdn.test/b.png")));

        assertThrows(ServiceException.class, () -> service.generateVideo(request));
        verifyNoInteractions(mapper);
    }

    @Test
    void batchKlingStandardI2vRejectsGenericReferenceBeforePreparation() {
        MediaGenerationServiceImpl service = mediaServiceWithModel(
            klingModel("kling-standard", KlingConstants.SCENARIO_STANDARD_I2V, 1));
        MediaVideoGenerateRequest request = videoRequest("kling-standard");
        request.setOptions(Map.of("referenceImages", List.of("https://cdn.test/not-first.png")));
        MediaBatchGenerateRequest.BatchGenerateItem item = new MediaBatchGenerateRequest.BatchGenerateItem();
        item.setMediaType(MediaType.VIDEO.name());
        item.setVideoRequest(request);

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
            service, "buildPreparedBatchUnit", item, "batch-1", 7L, 0,
            new MediaBatchGenerateRequest()));
    }

    @Test
    void klingReferenceMinimumUsesBuilderDedupAndPreferredListFallback() {
        AiModelConfigVo model = klingModel(
            "kling-reference", KlingConstants.SCENARIO_OMNI_REFERENCE, 2);
        MediaVideoGenerateRequest request = videoRequest("kling-reference");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("referenceImages", List.of(
            "https://cdn.test/a.png", "https://cdn.test/a.png", " "));
        options.put("images", List.of("https://cdn.test/b.png", "https://cdn.test/c.png"));
        request.setOptions(options);

        assertThrows(ServiceException.class,
            () -> MediaGenerationServiceImpl.validateVideoRequestReferenceInputs(model, request));
    }

    @Test
    void singleKlingFeatureVideoRejectsInvalidSettingsBeforeTaskCreation() {
        MediaGenerationServiceImpl service = mediaServiceWithModel(
            klingModel("kling-feature", KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, 0));
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest("kling-feature");
        request.setOptions(Map.of(
            "featureVideoUrl", "https://cdn.test/feature.mp4",
            "multiShot", false));

        ServiceException failure = assertThrows(ServiceException.class, () -> service.generateVideo(request));

        assertEquals("可灵参数无效", failure.getMessage());
        verifyNoInteractions(mapper);
    }

    @Test
    void batchKlingTurboRejectsUnsupportedOptionsBeforePreparation() {
        MediaGenerationServiceImpl service = mediaServiceWithModel(
            klingModel("kling-turbo", KlingConstants.SCENARIO_TURBO_I2V, 1));
        MediaVideoGenerateRequest request = videoRequest("kling-turbo");
        request.setImageUrl("https://cdn.test/first.png");
        request.setOptions(Map.of("multiShot", true));
        MediaBatchGenerateRequest.BatchGenerateItem item = new MediaBatchGenerateRequest.BatchGenerateItem();
        item.setMediaType(MediaType.VIDEO.name());
        item.setVideoRequest(request);

        ServiceException failure = assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
            service, "buildPreparedBatchUnit", item, "batch-1", 7L, 0,
            new MediaBatchGenerateRequest()));

        assertEquals("可灵参数无效", failure.getMessage());
    }

    @Test
    void singleKlingPromptLimitRejectsBeforeTaskCreationWithoutMutatingPrompt() {
        MediaGenerationServiceImpl service = mediaServiceWithModel(
            klingModel("kling-standard", KlingConstants.SCENARIO_STANDARD_I2V, 1));
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest("kling-standard");
        request.setImageUrl("https://cdn.test/first.png");
        String oversizedPrompt = "a".repeat(2501);
        request.setPrompt(oversizedPrompt);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.generateVideo(request));

        assertEquals("提示词无效", failure.getMessage());
        assertEquals(oversizedPrompt, request.getPrompt());
        verifyNoInteractions(mapper);
    }

    @Test
    void minimaxH3ReferenceRejectsMissingMediaInPrefreezeContractValidation() {
        AiModelConfigVo model = minimaxH3Model(MinimaxH3Constants.MODEL_REFERENCE, 9);
        MediaVideoGenerateRequest request = videoRequest(MinimaxH3Constants.MODEL_REFERENCE);

        assertThrows(ServiceException.class,
            () -> MediaGenerationServiceImpl.validateVideoProviderContract(model, request));
    }

    @Test
    void minimaxH3FirstFrameRejectsReferenceMixInPrefreezeContractValidation() {
        AiModelConfigVo model = minimaxH3Model(MinimaxH3Constants.MODEL_I2V_FIRST, 1);
        MediaVideoGenerateRequest request = videoRequest(MinimaxH3Constants.MODEL_I2V_FIRST);
        request.setImageUrl("https://cdn.test/first.png");
        request.setOptions(Map.of("referenceVideos", List.of("https://cdn.test/reference.mp4")));

        assertThrows(ServiceException.class,
            () -> MediaGenerationServiceImpl.validateVideoProviderContract(model, request));
    }

    @Test
    void minimaxH3PrefreezeValidationUsesCleanPromptWithoutMutatingAuditRequest() {
        AiModelConfigVo model = minimaxH3Model(MinimaxH3Constants.MODEL_REFERENCE, 9);
        MediaVideoGenerateRequest request = videoRequest(MinimaxH3Constants.MODEL_REFERENCE);
        request.setOptions(Map.of("images", List.of("https://cdn.test/reference.png")));
        String originalPrompt = "参考@图片1[角色]生成镜头\n---参考图映射---\n图1=" + "x".repeat(7000);
        request.setPrompt(originalPrompt);

        MediaGenerationServiceImpl.validateVideoProviderContract(model, request);

        assertEquals(originalPrompt, request.getPrompt());
    }

    @Test
    void singleMinimaxH3ValidationFailureDoesNotMutatePromptBeforeTaskCreation() {
        AiModelConfigVo model = minimaxH3Model(MinimaxH3Constants.MODEL_T2V, 0);
        MediaGenerationServiceImpl service = mediaServiceWithModel(model);
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest(MinimaxH3Constants.MODEL_T2V);
        request.setOptions(Map.of("resolution", "4K"));
        String originalPrompt = "生成@图片1[角色]镜头\n---参考图映射---\n图1=https://cdn.test/reference.png";
        request.setPrompt(originalPrompt);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.generateVideo(request));

        assertEquals("分辨率不支持", failure.getMessage());
        assertEquals(originalPrompt, request.getPrompt());
        verifyNoInteractions(mapper);
    }

    @Test
    void seedanceEditRejectsIllegalOutputFormatBeforeTaskCreation() {
        AiModelConfigVo model = seedanceEditModel();
        MediaGenerationServiceImpl service = mediaServiceWithModel(model);
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest(model.getModelCode());
        request.setAspectRatio("adaptive");
        request.setDurationSeconds(-1);
        request.setOptions(Map.of("resolution", "720P",
                "referenceVideoUrl", "https://cdn.test/edit.mov", "output_format", "avi"));

        ServiceException failure = assertThrows(ServiceException.class, () -> service.generateVideo(request));

        assertEquals("输出格式无效", failure.getMessage());
        verifyNoInteractions(mapper);
    }

    @Test
    void seedanceTextRejectsRawVideoBeforeTaskCreation() {
        AiModelConfigVo model = seedanceTextModel();
        MediaGenerationServiceImpl service = mediaServiceWithModel(model);
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        MediaVideoGenerateRequest request = videoRequest(model.getModelCode());
        request.setAspectRatio("adaptive");
        request.setDurationSeconds(-1);
        request.setOptions(Map.of("resolution", "720P",
                "referenceVideoUrl", "https://cdn.test/ignored.mp4"));

        ServiceException failure = assertThrows(ServiceException.class, () -> service.generateVideo(request));

        assertEquals("文生视频不接收素材", failure.getMessage());
        verifyNoInteractions(mapper);
    }

    private MediaGenerationServiceImpl mediaServiceWithModel(AiModelConfigVo model) {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        IAiModelConfigService modelService = mock(IAiModelConfigService.class);
        when(modelService.selectByModelCode(model.getModelCode())).thenReturn(model);
        ReflectionTestUtils.setField(service, "aiModelConfigService", modelService);
        return service;
    }

    private MediaVideoGenerateRequest videoRequest(String modelCode) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName(modelCode);
        request.setPrompt("测试提示词");
        request.setUserId(7L);
        return request;
    }

    private AiModelConfigVo klingModel(String modelCode, String scenario, int minReferenceImages) {
        AiModelConfigVo model = model("video");
        model.setModelCode(modelCode);
        model.setProviderCode(KlingConstants.PROVIDER_CODE);
        model.setCapabilityJson("{\"klingScenario\":\"" + scenario
            + "\",\"minReferenceImages\":" + minReferenceImages + "}");
        model.setApiSuffix(KlingConstants.SCENARIO_TURBO_I2V.equals(scenario)
            ? KlingConstants.PATH_TURBO_I2V
            : scenario.startsWith("standard_")
                ? KlingConstants.PATH_STANDARD_I2V : KlingConstants.PATH_OMNI);
        return model;
    }

    private AiModelConfigVo minimaxH3Model(String modelCode, int maxReferenceImages) {
        AiModelConfigVo model = model("video");
        model.setModelCode(modelCode);
        model.setProviderCode(MinimaxH3Constants.PROVIDER_CODE);
        model.setProtocol(MinimaxH3Constants.PROTOCOL_VIDEO);
        model.setCapabilityJson("{\"maxReferenceImages\":" + maxReferenceImages
            + ",\"supportsReferenceAudio\":true,\"maxReferenceAudios\":3}");
        return model;
    }

    private AiModelConfigVo seedanceEditModel() {
        AiModelConfigVo model = model("video");
        model.setModelCode("doubao-seedance-2.5-edit");
        model.setProviderCode(VolcengineConstants.PROVIDER_CODE);
        model.setProtocol(VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO);
        model.setDefaultSizeCode("720P");
        model.setDefaultAspectRatio("adaptive");
        model.setDefaultDurationSeconds(-1);
        model.setCapabilityJson("{\"videoScenario\":\"edit\",\"sizeOptions\":[\"480P\",\"720P\"],"
                + "\"aspectRatioOptions\":[\"adaptive\",\"16:9\"],\"durationOptions\":[-1],"
                + "\"supportsVideoInput\":true,\"maxReferenceVideos\":10,"
                + "\"supportsReferenceAudio\":true,\"maxReferenceAudios\":10,"
                + "\"defaultOutputFormat\":\"mov\"}");
        return model;
    }

    private AiModelConfigVo seedanceTextModel() {
        AiModelConfigVo model = model("video");
        model.setModelCode("doubao-seedance-2.5-text");
        model.setProviderCode(VolcengineConstants.PROVIDER_CODE);
        model.setProtocol(VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO);
        model.setDefaultSizeCode("720P");
        model.setDefaultAspectRatio("adaptive");
        model.setDefaultDurationSeconds(-1);
        model.setCapabilityJson("{\"videoScenario\":\"text\",\"sizeOptions\":[\"480P\",\"720P\"],"
                + "\"aspectRatioOptions\":[\"adaptive\"],\"durationOptions\":[-1,4,5,6,30],"
                + "\"maxReferenceImages\":0,\"maxReferenceVideos\":0,\"maxReferenceAudios\":0,"
                + "\"defaultOutputFormat\":\"mp4\"}");
        return model;
    }

    private AiModelConfigVo model(String modelType) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("test-model");
        model.setModelType(modelType);
        return model;
    }
}
