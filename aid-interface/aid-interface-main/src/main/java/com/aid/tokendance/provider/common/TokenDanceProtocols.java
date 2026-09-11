package com.aid.tokendance.provider.common;

import java.util.Set;

/** TokenDance 供应商与内部协议标识。 */
public final class TokenDanceProtocols
{
    public static final String PROVIDER_CODE = "tokendance";
    public static final String PREFIX = "tokendance:";

    public static final String OPENAI_CHAT_COMPLETIONS = PREFIX + "openai:chat-completions";
    public static final String OPENAI_RESPONSES = PREFIX + "openai:responses";
    public static final String ANTHROPIC_MESSAGES = PREFIX + "anthropic:messages";
    public static final String ARK_IMAGE_GENERATIONS = PREFIX + "ark:image-generations";
    public static final String OPENAI_IMAGE_GENERATIONS = PREFIX + "openai:image-generations";
    public static final String SEEDANCE_GENERATIONS = PREFIX + "seedance:generations";
    public static final String KLING_TEXT_TO_VIDEO = PREFIX + "kling:text2video";
    public static final String KLING_IMAGE_TO_VIDEO = PREFIX + "kling:image2video";
    public static final String KLING_OMNI_VIDEO = PREFIX + "kling:omni-video";
    public static final String WAN3_VIDEO_SYNTHESIS = PREFIX + "wan3:video-synthesis";
    public static final String HAPPYHORSE_VIDEO_SYNTHESIS = PREFIX + "happyhorse:video-synthesis";
    public static final String MINIMAX_VIDEO_GENERATION_V2 = PREFIX + "minimax:video_generation_v2";

    public static final Set<String> TEXT_PROTOCOLS = Set.of(
            OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES);
    public static final Set<String> IMAGE_PROTOCOLS = Set.of(
            ARK_IMAGE_GENERATIONS, OPENAI_IMAGE_GENERATIONS);
    public static final Set<String> VIDEO_PROTOCOLS = Set.of(
            SEEDANCE_GENERATIONS, KLING_TEXT_TO_VIDEO, KLING_IMAGE_TO_VIDEO,
            KLING_OMNI_VIDEO, WAN3_VIDEO_SYNTHESIS, HAPPYHORSE_VIDEO_SYNTHESIS,
            MINIMAX_VIDEO_GENERATION_V2);

    private TokenDanceProtocols()
    {
    }

    public static boolean isTokenDance(String providerCode)
    {
        return providerCode != null && PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    public static boolean matches(String expected, String actual)
    {
        return actual != null && expected.equalsIgnoreCase(actual.trim());
    }
}
