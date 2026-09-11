package com.aid.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 制作结果发布到已有音色库。 */
@Data
public class VoiceWorkbenchPublishRequest {
    @NotNull private Long taskId;
    private Long targetModelId;
    @NotBlank @Size(max = 100) private String name;
    @NotBlank private String publishStatus;
    @NotBlank private String language;
    @NotBlank private String gender;
    @NotBlank private String ageRange;
}
