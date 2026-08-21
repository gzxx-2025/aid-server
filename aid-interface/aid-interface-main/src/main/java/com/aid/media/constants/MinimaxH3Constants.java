package com.aid.media.constants;

import java.util.Set;

/** MiniMax H3 视频 V2 协议常量。 */
public final class MinimaxH3Constants {

    public static final String PROVIDER_CODE = "minimax";
    public static final String PROTOCOL_VIDEO = "minimax-h3-video";
    public static final String REAL_MODEL_CODE = "MiniMax-H3";
    public static final String AUTH_PREFIX = "Bearer ";
    public static final int HTTP_TIMEOUT_MS = 120_000;

    public static final String MODEL_T2V = "minimax-h3-t2v";
    public static final String MODEL_I2V_FIRST = "minimax-h3-i2v-first";
    public static final String MODEL_I2V_LAST = "minimax-h3-i2v-last";
    public static final String MODEL_I2V_FIRST_LAST = "minimax-h3-i2v-first-last";
    public static final String MODEL_REFERENCE = "minimax-h3-reference";

    public static final Set<String> MODEL_CODES = Set.of(
        MODEL_T2V, MODEL_I2V_FIRST, MODEL_I2V_LAST, MODEL_I2V_FIRST_LAST, MODEL_REFERENCE);
    public static final Set<String> RESOLUTIONS = Set.of("768P", "2K");
    public static final Set<String> RATIOS = Set.of("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16");

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCEEDED";
    public static final String STATUS_FAILURE = "FAILED";

    public static final String CALLBACK_PATH = "/api/media/callback/minimax-h3";
    public static final String STRATEGY_CALLBACK_BASE_URL = "callbackBaseUrl";

    private MinimaxH3Constants() {
    }
}
