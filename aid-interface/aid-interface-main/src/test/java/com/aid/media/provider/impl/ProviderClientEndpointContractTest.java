package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.OpenAiCompatiblePayloadResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证各生成适配器实际消费后台配置的完整相对路径。 */
class ProviderClientEndpointContractTest {

    private static final String BASE = "https://proxy.example.test";

    @Test
    void seedanceUsesConfiguredSubmitAndQueryPaths() {
        AiModelConfigVo config = config("/tenant/ark/v9/video/tasks", "/tenant/ark/v9/video/tasks/%s");

        assertEquals(BASE + "/tenant/ark/v9/video/tasks",
                VolcengineVideoProviderClient.buildSubmitUrl(config));
        assertEquals(BASE + "/tenant/ark/v9/video/tasks/task%2Fa",
                VolcengineVideoProviderClient.buildQueryUrl(config, "task/a"));
    }

    @Test
    void seedreamUsesConfiguredSubmitPath() {
        AiModelConfigVo config = config("/tenant/ark/v9/images", null);

        assertEquals(BASE + "/tenant/ark/v9/images",
                VolcengineImageProviderClient.buildSubmitUrl(config));
    }

    @Test
    void klingUsesConfiguredSubmitAndQueryPaths() {
        AiModelConfigVo config = config("/tenant/kling/v8/create", "/tenant/kling/v8/tasks?id=%s");

        assertEquals(BASE + "/tenant/kling/v8/create", KlingVideoProviderClient.buildSubmitUrl(config));
        assertEquals(BASE + "/tenant/kling/v8/tasks?id=task%2Fa",
                KlingVideoProviderClient.buildQueryUrl(config, "task/a"));
    }

    @Test
    void minimaxUsesConfiguredVideoAndTtsPaths() {
        AiModelConfigVo video = config("/tenant/minimax/v8/video", "/tenant/minimax/v8/video/%s");
        AiModelConfigVo tts = config("/tenant/minimax/v8/tts", null);

        assertEquals(BASE + "/tenant/minimax/v8/video", MinimaxH3VideoProviderClient.buildSubmitUrl(video));
        assertEquals(BASE + "/tenant/minimax/v8/video/task%2Fa",
                MinimaxH3VideoProviderClient.buildQueryUrl(video, "task/a"));
        assertEquals(BASE + "/tenant/minimax/v8/tts", MinimaxTtsProviderClient.buildSubmitUrl(tts));
    }

    @Test
    void viduImageAndVideoUseConfiguredSubmitAndQueryPaths() {
        String submit = "/tenant/vidu/v8/create";
        String query = "/tenant/vidu/v8/tasks/%s/result";

        assertEquals(BASE + submit, ViduImageProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + "/tenant/vidu/v8/tasks/task%2Fa/result",
                ViduImageProviderClient.buildTaskUrl(BASE, query, "task/a"));
        assertEquals(BASE + submit, ViduVideoProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + "/tenant/vidu/v8/tasks/task%2Fa/result",
                ViduVideoProviderClient.buildTaskUrl(BASE, query, "task/a"));
    }

    @Test
    void viduScenarioComesFromModelConfigWithOpaqueProxyPath() {
        ViduVideoProviderClient client = new ViduVideoProviderClient();
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();

        AiModelConfigVo image = config("/tenant/vidu/v8/opaque-a", null);
        image.setGenerateMode("image_to_video");
        assertEquals("IMAGE_TO_VIDEO", client.resolveScenarioName(image, request));

        AiModelConfigVo frames = config("/tenant/vidu/v8/opaque-b", null);
        frames.setGenerateMode("start_end_to_video");
        assertEquals("START_END_TO_VIDEO", client.resolveScenarioName(frames, request));

        AiModelConfigVo lipSync = config("/tenant/vidu/v8/opaque-c", null);
        lipSync.setGenerateMode("video_to_video");
        lipSync.setCapabilityJson("{\"lipSync\":true}");
        assertEquals("LIP_SYNC", client.resolveScenarioName(lipSync, request));
    }

    @Test
    void dashscopeImageAndVideoUseConfiguredSubmitAndQueryPaths() {
        String submit = "/tenant/dash/v8/create";
        String query = "/tenant/dash/v8/tasks/%s";

        assertEquals(BASE + submit, DashscopeImageProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + "/tenant/dash/v8/tasks/task%2Fa",
                DashscopeImageProviderClient.buildTaskUrl(BASE, query, "task/a"));
        assertEquals(BASE + submit, DashscopeVideoProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + "/tenant/dash/v8/tasks/task%2Fa",
                DashscopeVideoProviderClient.buildTaskUrl(BASE, query, "task/a"));
    }

    @Test
    void agnesImageAndVideoUseConfiguredPaths() {
        String submit = "/tenant/agnes/v8/create";
        String query = "/tenant/agnes/v8/tasks?video_id=%s";

        assertEquals(BASE + submit, AgnesImageProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + submit, AgnesVideoProviderClient.buildApiUrl(BASE, submit));
        assertEquals(BASE + "/tenant/agnes/v8/tasks?video_id=task%2Fa",
                AgnesVideoProviderClient.buildTaskUrl(BASE, query, "task/a"));
    }

    @Test
    void volcengineTtsAndOpenAiCompatibleUseConfiguredSubmitPaths() {
        AiModelConfigVo tts = config("/tenant/speech/v8/tts", null);

        assertEquals(BASE + "/tenant/speech/v8/tts", VolcengineTtsProviderClient.buildSubmitUrl(tts));
        assertEquals(BASE + "/tenant/openai/v8/responses?route=a+b",
                OpenAiCompatiblePayloadResolver.buildApiUrl(
                        BASE, "/tenant/openai/v8/responses", "{\"route\":\"a b\"}"));
    }

    @Test
    void openAiImageUsesConfiguredProxyPrefixForBothOperations() {
        AiModelConfigVo templated = config("/tenant/openai/v8/images/{operation}", null);
        assertEquals(BASE + "/tenant/openai/v8/images/generations",
                OpenAiImageProviderClient.buildApiUrl(templated, false));
        assertEquals(BASE + "/tenant/openai/v8/images/edits",
                OpenAiImageProviderClient.buildApiUrl(templated, true));

        AiModelConfigVo legacy = config("/tenant/openai/v8/images/generations", null);
        assertEquals(BASE + "/tenant/openai/v8/images/edits",
                OpenAiImageProviderClient.buildApiUrl(legacy, true));
    }

    @Test
    void geminiImageAndTextUseTheControlledModelPathTemplate() {
        String template = "/tenant/gemini/v8/models/{model}:generateContent";

        assertEquals(BASE + "/tenant/gemini/v8/models/gemini%2Ftext:generateContent",
                GeminiTextProviderClient.buildGenerateContentUrl(BASE, template, "gemini/text"));
        assertEquals(BASE + "/tenant/gemini/v8/models/gemini%2Fimage:generateContent",
                GeminiImageProviderClient.buildGenerateContentUrl(BASE, template, "gemini/image"));
        assertEquals(BASE + "/v1beta/models/gemini-test:generateContent",
                GeminiTextProviderClient.buildGenerateContentUrl(
                        BASE, "/v1beta/models/", "gemini-test"));
    }

    @Test
    void everyRuntimeBuilderRejectsUnsafeConfiguredPaths() {
        AiModelConfigVo unsafe = config("/tenant/%2e%2e/escape", "/tenant/tasks/%s#fragment");

        assertThrows(IllegalArgumentException.class,
                () -> VolcengineImageProviderClient.buildSubmitUrl(unsafe));
        assertThrows(ServiceException.class,
                () -> KlingVideoProviderClient.buildSubmitUrl(unsafe));
        assertThrows(ServiceException.class,
                () -> MinimaxH3VideoProviderClient.buildSubmitUrl(unsafe));
        assertThrows(IllegalArgumentException.class,
                () -> ViduImageProviderClient.buildApiUrl(BASE, unsafe.getApiSuffix()));
        assertThrows(IllegalArgumentException.class,
                () -> DashscopeVideoProviderClient.buildTaskUrl(BASE, unsafe.getTaskQuerySuffix(), "task"));
        assertThrows(IllegalArgumentException.class,
                () -> AgnesVideoProviderClient.buildTaskUrl(BASE, unsafe.getTaskQuerySuffix(), "task"));
        assertThrows(IllegalArgumentException.class,
                () -> VolcengineTtsProviderClient.buildSubmitUrl(unsafe));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiCompatiblePayloadResolver.buildApiUrl(BASE, unsafe.getApiSuffix(), null));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiImageProviderClient.buildApiUrl(unsafe, false));
    }

    private AiModelConfigVo config(String apiSuffix, String taskQuerySuffix) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setBaseUrl(BASE);
        config.setApiSuffix(apiSuffix);
        config.setTaskQuerySuffix(taskQuerySuffix);
        return config;
    }
}
