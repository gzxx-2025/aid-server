package com.aid.voice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 后台音色制作与报价的结构化输入。 */
@Data
public class VoiceWorkbenchRequest {
    @NotNull private Long modelId;
    @NotNull private String operation;
    @Size(max = 128) private String voiceCode;
    @Size(max = 10000) private String text;
    @Size(max = 128) private String sourceFileId;
    @Size(max = 2000) private String description;
    private boolean rightsConfirmed;
    private boolean chargeConfirmed;
    @Size(max = 128) private String idempotencyKey;
    @Size(max = 128) private String quoteRef;
    private String audioFormat;
    private Integer sampleRate;
    private Integer speechRate;
    private Integer loudnessRate;
    private Integer pitch;
    private String emotion;
    private Boolean optimizeTextPreview;
}
