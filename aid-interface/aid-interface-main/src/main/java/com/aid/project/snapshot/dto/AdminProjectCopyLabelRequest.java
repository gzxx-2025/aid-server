package com.aid.project.snapshot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminProjectCopyLabelRequest
{
    @NotNull(message = "项目ID不能为空")
    @Positive(message = "项目ID必须大于0")
    private Long id;

    /** 传空字符串可恢复默认“复制”标签。 */
    @Size(max = 32, message = "复制标签过长")
    private String copyLabel;
}
