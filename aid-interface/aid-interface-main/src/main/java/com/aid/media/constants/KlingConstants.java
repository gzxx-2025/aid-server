package com.aid.media.constants;

import java.util.Set;

/** 可灵 3.0 新版视频 API 常量。 */
public final class KlingConstants {

    private KlingConstants() {
    }

    public static final String PROVIDER_CODE = "kling";
    public static final String PROTOCOL_VIDEO = "kling-video";
    public static final String PATH_TURBO_I2V = "/image-to-video/kling-3.0-turbo";
    public static final String PATH_STANDARD_I2V = "/image-to-video/kling-3.0";
    public static final String PATH_OMNI = "/omni-video/kling-3.0-omni";
    public static final String AUTH_PREFIX = "Bearer ";
    public static final int HTTP_TIMEOUT_MS = 120_000;

    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    public static final String CALLBACK_HEADER_ID = "webhook-id";
    public static final String CALLBACK_HEADER_TIMESTAMP = "webhook-timestamp";
    public static final String CALLBACK_HEADER_SIGNATURE = "webhook-signature";
    public static final String WEBHOOK_SECRET_PREFIX = "whsec_";
    public static final long CALLBACK_MAX_SKEW_SECONDS = 5L * 60L;
    public static final String STRATEGY_CALLBACK_BASE_URL = "callbackBaseUrl";

    public static final String SCENARIO_TURBO_I2V = "turbo_i2v";
    public static final String SCENARIO_STANDARD_I2V = "standard_i2v";
    public static final String SCENARIO_STANDARD_MULTI = "standard_multi";
    public static final String SCENARIO_OMNI_T2V = "omni_t2v";
    public static final String SCENARIO_OMNI_I2V = "omni_i2v";
    public static final String SCENARIO_OMNI_FIRST_LAST = "omni_first_last";
    public static final String SCENARIO_OMNI_REFERENCE = "omni_reference";
    public static final String SCENARIO_OMNI_FEATURE_VIDEO = "omni_feature_video";
    public static final String SCENARIO_OMNI_EDIT = "omni_edit";
    public static final Set<String> SCENARIOS = Set.of(
        SCENARIO_TURBO_I2V, SCENARIO_STANDARD_I2V, SCENARIO_STANDARD_MULTI,
        SCENARIO_OMNI_T2V, SCENARIO_OMNI_I2V, SCENARIO_OMNI_FIRST_LAST,
        SCENARIO_OMNI_REFERENCE, SCENARIO_OMNI_FEATURE_VIDEO, SCENARIO_OMNI_EDIT);
}
