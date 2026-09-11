package com.aid.tokendance.provider.video;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;

import java.util.Collections;
import java.util.Map;

/** TokenDance 视频协议的非 Spring 策略。 */
interface TokenDanceVideoProtocolStrategy
{
    String protocol();

    String submitPath();

    String queryTemplate();

    TokenDanceVideoResponseMapper.Shape responseShape();

    Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request);

    default void normalizeRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
    }

    default void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        ReferencePromptSanitizer.sanitizeInPlace(request,
                inputs.referenceImages.size()
                        + (inputs.firstFrame == null ? 0 : 1)
                        + (inputs.lastFrame == null ? 0 : 1),
                inputs.referenceAudios.size());
    }

    default Map<String, String> protocolHeaders()
    {
        return Collections.emptyMap();
    }
}
