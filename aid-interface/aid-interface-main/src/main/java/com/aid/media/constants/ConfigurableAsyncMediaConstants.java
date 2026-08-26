package com.aid.media.constants;

import java.util.Set;

/** 可配置异步图片与视频任务协议常量。 */
public final class ConfigurableAsyncMediaConstants {

    public static final String PROTOCOL_IMAGE = "configurable-async-image";
    public static final String PROTOCOL_VIDEO = "configurable-async-video";

    public static final String OPERATION_PLACEHOLDER = "{operation}";
    public static final String OPERATION_GENERATIONS = "generations";
    public static final String OPERATION_EDITS = "edits";

    public static final String CAPABILITY_RESULT_DOWNLOAD_REQUIRES_AUTH = "resultDownloadRequiresAuth";
    public static final String CAPABILITY_FORCE_GENERATE_AUDIO = "forceGenerateAudio";
    public static final String CAPABILITY_SUPPORTS_AUDIO = "supportsAudio";
    public static final String CAPABILITY_UPSTREAM_RESOLUTION = "upstreamResolution";
    public static final String CAPABILITY_MAX_REFERENCE_VIDEOS = "maxReferenceVideos";
    public static final String CAPABILITY_MAX_PROMPT_CHARACTERS = "maxPromptCharacters";

    public static final int HTTP_TIMEOUT_MS = 120_000;
    public static final int IMAGE_DOWNLOAD_TIMEOUT_MS = 60_000;
    public static final int MAX_REFERENCE_IMAGE_BYTES = 50 * 1024 * 1024;

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final Set<String> PROCESSING_STATES = Set.of("queued", "in_progress");
    public static final Set<String> SUCCEEDED_STATES = Set.of("completed");
    public static final Set<String> FAILED_STATES = Set.of("failed");

    private ConfigurableAsyncMediaConstants() {
    }
}
