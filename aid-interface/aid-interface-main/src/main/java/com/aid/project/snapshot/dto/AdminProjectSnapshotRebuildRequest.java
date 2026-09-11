package com.aid.project.snapshot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 后台对已发布项目生成新的不可变发布快照 revision。 */
@Data
public class AdminProjectSnapshotRebuildRequest
{
    @NotNull(message = "项目ID不能为空")
    private Long id;

    @NotBlank(message = "重建原因不能为空")
    @Size(max = 200, message = "重建原因不能超过200个字符")
    private String reason;
}
