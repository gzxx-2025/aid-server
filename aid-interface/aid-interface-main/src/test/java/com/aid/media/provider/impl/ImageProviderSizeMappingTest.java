package com.aid.media.provider.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.dto.MediaImageGenerateRequest;

class ImageProviderSizeMappingTest {

    @Test
    void viduUsesConfiguredAspectRatioWithResolutionTier() throws Exception {
        ViduImageProviderClient client = new ViduImageProviderClient();
        MediaImageGenerateRequest request = request("1080p", "16:9");

        Method method = ViduImageProviderClient.class.getDeclaredMethod(
                "buildSubmitBody", AiModelConfigVo.class, MediaImageGenerateRequest.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) method.invoke(client, null, request);

        assertEquals("1080p", body.get(ViduConstants.JSON_RESOLUTION));
        assertEquals("16:9", body.get(ViduConstants.JSON_ASPECT_RATIO));
    }

    @Test
    void openAiLetsAspectRatioOverrideStaleExplicitSize() throws Exception {
        OpenAiImageProviderClient client = new OpenAiImageProviderClient();
        MediaImageGenerateRequest request = request("1024x1024", "16:9");

        assertEquals("1536x864", invokeString(client, "resolveSize", request));
    }

    @Test
    void seedreamMapsTierAndRatioToOfficialSize() throws Exception {
        VolcengineImageProviderClient client = new VolcengineImageProviderClient();
        MediaImageGenerateRequest request = request("2K", "16:9");

        assertEquals("2816x1584", invokeString(client, "resolveSeedreamSize", request));
    }

    private MediaImageGenerateRequest request(String size, String aspectRatio) {
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setPrompt("测试");
        request.setSize(size);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("aspect_ratio", aspectRatio);
        request.setOptions(options);
        return request;
    }

    private String invokeString(Object target, String methodName,
                                MediaImageGenerateRequest request) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, MediaImageGenerateRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(target, request);
    }
}
