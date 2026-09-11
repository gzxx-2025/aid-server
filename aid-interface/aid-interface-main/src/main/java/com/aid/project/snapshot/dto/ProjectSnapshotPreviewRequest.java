package com.aid.project.snapshot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ProjectSnapshotPreviewRequest
{
    @NotNull(message = "项目ID不能为空")
    @Positive(message = "项目ID必须大于0")
    private Long id;
}
