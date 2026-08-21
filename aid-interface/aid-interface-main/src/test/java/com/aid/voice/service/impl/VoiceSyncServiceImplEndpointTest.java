package com.aid.voice.service.impl;

import com.aid.domain.vo.AiModelConfigVo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 MiniMax 音色查询保留配置的代理路径前缀。 */
class VoiceSyncServiceImplEndpointTest {

    @Test
    void derivesVoiceListFromConfiguredTtsSubmitPath() {
        AiModelConfigVo sync = config("/tenant/minimax/v9/t2a_v2");
        AiModelConfigVo async = config("/tenant/minimax/v9/t2a_async_v2");

        assertEquals("https://proxy.example.test/tenant/minimax/v9/get_voice",
            VoiceSyncServiceImpl.buildMinimaxVoiceListUrl(sync));
        assertEquals("https://proxy.example.test/tenant/minimax/v9/get_voice",
            VoiceSyncServiceImpl.buildMinimaxVoiceListUrl(async));
    }

    @Test
    void rejectsUnsafeOrUnrelatedConfiguredPaths() {
        assertThrows(IllegalArgumentException.class,
            () -> VoiceSyncServiceImpl.buildMinimaxVoiceListUrl(
                config("https://evil.example/v1/t2a_v2")));
        assertThrows(IllegalArgumentException.class,
            () -> VoiceSyncServiceImpl.buildMinimaxVoiceListUrl(
                config("/tenant/minimax/v9/unknown")));
    }

    private AiModelConfigVo config(String apiSuffix) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setBaseUrl("https://proxy.example.test");
        config.setApiSuffix(apiSuffix);
        config.setModelCode("speech-test");
        return config;
    }
}
