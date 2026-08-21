package com.aid.media.provider;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KlingProviderContractTest {

    @Test
    void proxySubmitPathsDoNotChangeControlledScenarioBodies() {
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_TURBO_I2V, "/proxy/kling/v9/turbo"),
            request("首帧运动", "https://cdn.test/first.png", Map.of())));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_STANDARD_I2V, "/proxy/kling/v9/standard"),
            request("首帧运动", "https://cdn.test/first.png", Map.of())));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_T2V, "/proxy/kling/v9/omni"),
            request("纯文本生成", null, Map.of())));
    }

    @Test
    void turboUsesDedicatedEndpointAndRejectsUnsupportedAudio() {
        MediaVideoGenerateRequest valid = request("首帧运动", "https://cdn.test/first.png", Map.of());
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_TURBO_I2V, KlingConstants.PATH_TURBO_I2V), valid);
        assertFalse(((Map<?, ?>) body.get("settings")).containsKey("audio"));

        MediaVideoGenerateRequest invalid = request("首帧运动", "https://cdn.test/first.png", Map.of("audio", false));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_TURBO_I2V, KlingConstants.PATH_TURBO_I2V), invalid));
    }

    @Test
    void standardFirstFrameMustNotCreateUnsentImageId() {
        MediaVideoGenerateRequest request = request("图片1走向镜头", "https://cdn.test/first.png", Map.of("audio", false));
        Map<String, Object> body = KlingVideoRequestBuilder.build(config(KlingConstants.SCENARIO_STANDARD_I2V,
            KlingConstants.PATH_STANDARD_I2V), request);
        List<?> contents = (List<?>) body.get("contents");
        Map<?, ?> first = (Map<?, ?>) contents.get(1);
        assertFalse(first.containsKey("id"));
        assertEquals("图片1走向镜头", ((Map<?, ?>) contents.get(0)).get("text"));
        assertEquals("off", ((Map<?, ?>) body.get("settings")).get("audio"));
    }

    @Test
    void standardMultiAcceptsFirstLastAndSubjectOnStandardEndpoint() {
        MediaVideoGenerateRequest request = request("首尾帧转场", "https://cdn.test/first.png",
            Map.of("lastFrameImageUrl", "https://cdn.test/last.png", "audioMode", "native",
                "elements", List.of(Map.of("elementId", "subject-1", "id", "element_1"))));
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_STANDARD_MULTI, KlingConstants.PATH_STANDARD_I2V), request);
        List<?> contents = (List<?>) body.get("contents");
        assertEquals("last_frame", ((Map<?, ?>) contents.get(2)).get("type"));
        assertEquals("element", ((Map<?, ?>) contents.get(3)).get("type"));
    }

    @Test
    void standardLastFrameDoesNotPreserveUnsentReferenceId() {
        MediaVideoGenerateRequest request = request("@image_2 转场", "https://cdn.test/first.png",
            Map.of("lastFrameImageUrl", "https://cdn.test/last.png"));

        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_STANDARD_MULTI, KlingConstants.PATH_STANDARD_I2V), request);
        List<?> contents = (List<?>) body.get("contents");

        assertFalse(((Map<?, ?>) contents.get(2)).containsKey("id"));
        assertEquals("image_2 转场", ((Map<?, ?>) contents.get(0)).get("text"));
    }

    @Test
    void omniTextToVideoUsesAspectRatioDefault() {
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_T2V, KlingConstants.PATH_OMNI),
            request("纯文本生成", null, Map.of()));
        assertEquals("16:9", ((Map<?, ?>) body.get("settings")).get("aspect_ratio"));
    }

    @Test
    void omniImageToVideoAcceptsOnlyFirstFrame() {
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_I2V, KlingConstants.PATH_OMNI),
            request("首帧生成", "https://cdn.test/first.png", Map.of()));
        List<?> contents = (List<?>) body.get("contents");
        assertEquals("first_frame", ((Map<?, ?>) contents.get(1)).get("type"));
        assertEquals("image_1", ((Map<?, ?>) contents.get(1)).get("id"));
    }

    @Test
    void omniFirstLastRequiresBothFrames() {
        MediaVideoGenerateRequest valid = request("首尾帧生成", "https://cdn.test/first.png",
            Map.of("lastFrameImageUrl", "https://cdn.test/last.png"));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FIRST_LAST, KlingConstants.PATH_OMNI), valid));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FIRST_LAST, KlingConstants.PATH_OMNI),
            request("缺少尾帧", "https://cdn.test/first.png", Map.of())));
    }

    @Test
    void omniFirstLastMapsBothPrivateImagePlaceholders() {
        MediaVideoGenerateRequest request = request("\u56fe\u72471[A] \u5230 \u56fe\u72472[B]",
            "https://cdn.test/first.png", Map.of("lastFrameImageUrl", "https://cdn.test/last.png"));

        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FIRST_LAST, KlingConstants.PATH_OMNI), request);
        String prompt = String.valueOf(((Map<?, ?>) ((List<?>) body.get("contents")).get(0)).get("text"));

        assertEquals("@image_1 \u5230 @image_2", prompt);
    }

    @Test
    void omniReferenceMapsOnlyActuallyDispatchedReferenceImages() {
        MediaVideoGenerateRequest request = request("图片1与图片2", "https://cdn.test/first.png",
            Map.of("referenceImages", List.of("https://cdn.test/a.png", "https://cdn.test/b.png")));
        Map<String, Object> body = KlingVideoRequestBuilder.build(config(KlingConstants.SCENARIO_OMNI_REFERENCE,
            KlingConstants.PATH_OMNI), request);
        String prompt = String.valueOf(((Map<?, ?>) ((List<?>) body.get("contents")).get(0)).get("text"));
        assertEquals("@image_2与@image_3", prompt);
    }

    @Test
    void omniEditAndFeatureVideoUseSafeDefaults() {
        MediaVideoGenerateRequest edit = request("编辑视频", null, Map.of("baseVideoUrl", "https://cdn.test/base.mp4"));
        Map<?, ?> editSettings = (Map<?, ?>) KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), edit).get("settings");
        assertEquals(false, editSettings.get("multi_shot"));
        assertEquals("off", editSettings.get("audio"));

        MediaVideoGenerateRequest feature = request("参考视频", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "elements", List.of(Map.of("elementId", "e1", "elementType", "video_character_elements"))));
        Map<?, ?> featureSettings = (Map<?, ?>) KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), feature).get("settings");
        assertEquals(true, featureSettings.get("multi_shot"));
        assertEquals("off", featureSettings.get("audio"));
    }

    @Test
    void sharedVideoAliasesResolveOnlyForTheSelectedScenario() {
        MediaVideoGenerateRequest feature = request("feature", null,
            Map.of("videoUrl", "https://cdn.test/feature.mp4"));
        List<?> featureContents = (List<?>) KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), feature).get("contents");
        assertEquals("feature_video", ((Map<?, ?>) featureContents.get(1)).get("type"));

        MediaVideoGenerateRequest edit = request("edit", null,
            Map.of("video_url", "https://cdn.test/base.mp4"));
        List<?> editContents = (List<?>) KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), edit).get("contents");
        assertEquals("base_video", ((Map<?, ?>) editContents.get(1)).get("type"));

        MediaVideoGenerateRequest conflict = request("conflict", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "baseVideoUrl", "https://cdn.test/base.mp4"));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), conflict));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), conflict));
    }

    @Test
    void referenceScenarioAllowsSubjectOnlyButRejectsFirstOnly() {
        MediaVideoGenerateRequest subjectOnly = request("让@element_1转身", null,
            Map.of("elements", List.of(Map.of("elementId", "e1", "id", "element_1",
                "elementType", "multi_image_elements"))));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), subjectOnly));

        MediaVideoGenerateRequest firstOnly = request("首帧运动", "https://cdn.test/first.png", Map.of());
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), firstOnly));
    }

    @Test
    void rejectsOfficialCombinationOverflowAndGlobalIdCollision() {
        List<Map<String, String>> fourMultiSubjects = List.of(
            element("a", "element_1", "multi_image_elements"), element("b", "element_2", "multi_image_elements"),
            element("c", "element_3", "multi_image_elements"), element("d", "element_4", "multi_image_elements"));
        MediaVideoGenerateRequest overflow = request("参考", null,
            Map.of("referenceImages", List.of("1", "2", "3", "4"), "elements", fourMultiSubjects));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), overflow));

        MediaVideoGenerateRequest collision = request("参考", "https://cdn.test/first.png",
            Map.of("referenceImages", List.of("https://cdn.test/ref.png"),
                "elements", List.of(element("e1", "image_1", "multi_image_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), collision));
    }

    @Test
    void omniReferenceCountsEveryDispatchedFrameAgainstImageLimit() {
        MediaVideoGenerateRequest boundary = request("参考", "https://cdn.test/first.png",
            Map.of("lastFrameImageUrl", "https://cdn.test/last.png",
                "referenceImages", List.of("1", "2", "3", "4", "5")));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), boundary));

        MediaVideoGenerateRequest overflow = request("参考", "https://cdn.test/first.png",
            Map.of("lastFrameImageUrl", "https://cdn.test/last.png",
                "referenceImages", List.of("1", "2", "3", "4", "5", "6")));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_REFERENCE, KlingConstants.PATH_OMNI), overflow));
    }

    @Test
    void omniFeatureVideoAllowsReferenceImagesWithMultiImageSubjectsAtBoundary() {
        MediaVideoGenerateRequest boundary = request("@image_1 与 @element_1", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "referenceImages", List.of("https://cdn.test/one.png", "https://cdn.test/two.png"),
                "elements", List.of(element("a", "element_1", "multi_image_elements"),
                    element("b", "element_2", "multi_image_elements"))));
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), boundary);
        List<?> contents = (List<?>) body.get("contents");
        assertEquals(2, contents.stream().filter(item -> "refer_image".equals(((Map<?, ?>) item).get("type"))).count());
        assertEquals(2, contents.stream().filter(item -> "element".equals(((Map<?, ?>) item).get("type"))).count());

        MediaVideoGenerateRequest overflow = request("参考", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "referenceImages", List.of("1", "2", "3"),
                "elements", List.of(element("a", "element_1", "multi_image_elements"),
                    element("b", "element_2", "multi_image_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), overflow));
    }

    @Test
    void omniFeatureVideoEnforcesVideoCharacterMutualExclusionAndLimit() {
        MediaVideoGenerateRequest valid = request("@element_1", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "elements", List.of(element("a", "element_1", "video_character_elements"))));
        assertDoesNotThrow(() -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), valid));

        MediaVideoGenerateRequest mixedImages = request("参考", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "referenceImages", List.of("https://cdn.test/one.png"),
                "elements", List.of(element("a", "element_1", "video_character_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), mixedImages));

        MediaVideoGenerateRequest mixedSubjects = request("参考", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "elements", List.of(element("a", "element_1", "video_character_elements"),
                    element("b", "element_2", "multi_image_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), mixedSubjects));

        MediaVideoGenerateRequest tooManyCharacters = request("参考", null,
            Map.of("featureVideoUrl", "https://cdn.test/feature.mp4",
                "elements", List.of(element("a", "element_1", "video_character_elements"),
                    element("b", "element_2", "video_character_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO, KlingConstants.PATH_OMNI), tooManyCharacters));
    }

    @Test
    void omniEditSupportsTheSameReferenceVideoCombinationsWithEditSettings() {
        MediaVideoGenerateRequest boundary = request("@image_1 与 @element_1", null,
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4", "audioMode", "original",
                "referenceImages", List.of("https://cdn.test/one.png", "https://cdn.test/two.png"),
                "elements", List.of(element("a", "element_1", "multi_image_elements"),
                    element("b", "element_2", "multi_image_elements"))));
        Map<String, Object> body = KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), boundary);
        List<?> contents = (List<?>) body.get("contents");
        assertEquals(2, contents.stream().filter(item -> "refer_image".equals(((Map<?, ?>) item).get("type"))).count());
        assertEquals(false, ((Map<?, ?>) body.get("settings")).get("multi_shot"));
        assertEquals("original", ((Map<?, ?>) body.get("settings")).get("audio"));

        MediaVideoGenerateRequest invalid = request("参考", null,
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4",
                "referenceImages", List.of("https://cdn.test/one.png"),
                "elements", List.of(element("a", "element_1", "video_character_elements"))));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), invalid));

        MediaVideoGenerateRequest invalidAspect = request("编辑", null,
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4", "aspectRatio", "16:9"));
        assertThrows(ServiceException.class, () -> KlingVideoRequestBuilder.build(
            config(KlingConstants.SCENARIO_OMNI_EDIT, KlingConstants.PATH_OMNI), invalidAspect));
    }

    @Test
    void statusAndErrorCodesNeverPromoteTransientFailuresToTerminal() {
        assertEquals(KlingConstants.TASK_STATUS_PROCESSING, KlingStatusMapper.normalize("submitted"));
        assertEquals(KlingConstants.TASK_STATUS_PROCESSING, KlingStatusMapper.normalize("processing"));
        assertFalse(KlingStatusMapper.isTerminal("processing"));
        assertTrue(KlingStatusMapper.isTerminal("failed"));
        assertTrue(KlingErrorClassifier.isRetryable(429, 1302));
        assertTrue(KlingErrorClassifier.isRetryable(429, 1303));
        assertTrue(KlingErrorClassifier.isRetryable(503, 5001));
        assertTrue(KlingErrorClassifier.isContentRejected(1300));
        assertTrue(KlingErrorClassifier.isContentRejected(1301));
        assertEquals("输入内容未通过安全校验", KlingErrorClassifier.safeMessage(400, 1300));
        assertEquals("上游账户或权限不可用", KlingErrorClassifier.safeMessage(429, 1101));
        assertEquals("上游账户或权限不可用", KlingErrorClassifier.safeMessage(403, 1304));
        assertFalse(KlingErrorClassifier.safeMessage(401, 1000).contains("Bearer"));
    }

    @Test
    void webhookSignatureRequiresFreshOfficialFormatAndValidSecret() {
        String secret = "whsec_" + Base64.getEncoder().encodeToString("secret-key".getBytes(StandardCharsets.UTF_8));
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"id\":\"task-1\",\"status\":\"processing\"}";
        String signature = KlingCallbackSignatureUtil.sign("webhook-1." + timestamp + "." + body, secret);
        assertTrue(KlingCallbackSignatureUtil.hasValidSecret(secret));
        assertTrue(KlingCallbackSignatureUtil.verify("webhook-1", timestamp, "v1,bad v1," + signature, body, secret));
        assertFalse(KlingCallbackSignatureUtil.verify("webhook-1", timestamp, "v1,bad", body, secret));
        assertFalse(KlingCallbackSignatureUtil.hasValidSecret("whsec_not-base64***"));
    }

    @Test
    void webhookVerificationRejectsBareBase64RotationEntryAndExtremeTimestamp() {
        String validSecret = "whsec_" + Base64.getEncoder().encodeToString("valid-secret".getBytes(StandardCharsets.UTF_8));
        String bareSecret = Base64.getEncoder().encodeToString("bare-secret".getBytes(StandardCharsets.UTF_8));
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"id\":\"task-1\",\"status\":\"processing\"}";
        String bareSignature = KlingCallbackSignatureUtil.sign("webhook-1." + timestamp + "." + body, bareSecret);

        assertFalse(KlingCallbackSignatureUtil.verify("webhook-1", timestamp, "v1," + bareSignature,
            body, validSecret + "," + bareSecret));
        assertFalse(KlingCallbackSignatureUtil.verify("webhook-1", String.valueOf(Long.MIN_VALUE),
            "v1,unused", body, validSecret));
        assertFalse(KlingCallbackSignatureUtil.verify("webhook-1", String.valueOf(Long.MAX_VALUE),
            "v1,unused", body, validSecret));
    }

    @Test
    void officialWebhookSignatureVectorMatchesExactly() {
        String secret = "whsec_dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0MTI=";
        String rawBody = "{\"id\":\"1234567890\",\"status\":\"succeeded\",\"message\":\"\","
            + "\"create_time\":1781080778802,\"update_time\":1781080794151}";
        String signed = "9876543210.1781080794." + rawBody;
        assertEquals("UsKlJP00XoQyOn410NM9xv34sP+Gl0jnOO9Lcpr7NJ4=",
            KlingCallbackSignatureUtil.sign(signed, secret));
    }

    @Test
    void callbackSubmissionRequiresUrlSecretAndCallbackFirstTogether() {
        AiModelConfigVo model = config(KlingConstants.SCENARIO_OMNI_T2V, KlingConstants.PATH_OMNI);
        model.setSupportsCallback(true);
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"callbackBaseUrl\":\"https://aid.test/api/media/callback/kling\"}");
        assertNull(KlingCallbackSupport.resolveCallbackUrlForSubmission(model));
        model.setApiSecret("whsec_invalid***");
        assertNull(KlingCallbackSupport.resolveCallbackUrlForSubmission(model));
        model.setApiSecret("whsec_" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertEquals("https://aid.test/api/media/callback/kling", KlingCallbackSupport.resolveCallbackUrlForSubmission(model));
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"callbackBaseUrl\":"
            + "\"http://aid.test/api/media/callback/kling\"}");
        assertNull(KlingCallbackSupport.resolveCallbackUrlForSubmission(model));
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"callbackBaseUrl\":"
            + "\"https://aid.test/api/media/callback/provider\"}");
        assertNull(KlingCallbackSupport.resolveCallbackUrlForSubmission(model));
    }

    private AiModelConfigVo config(String scenario, String path) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setApiSuffix(path);
        config.setCapabilityJson("{\"klingScenario\":\"" + scenario + "\"}");
        return config;
    }

    private MediaVideoGenerateRequest request(String prompt, String image, Map<String, Object> options) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt(prompt);
        request.setImageUrl(image);
        request.setOptions(options);
        request.setDurationSeconds(5);
        return request;
    }

    private Map<String, String> element(String id, String referenceId, String type) {
        return Map.of("elementId", id, "id", referenceId, "elementType", type);
    }
}
