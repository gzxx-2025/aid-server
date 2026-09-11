package com.aid.project.snapshot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectSnapshotCopyRequest
{
    @NotNull(message = "项目ID不能为空")
    @Positive(message = "项目ID必须大于0")
    private Long id;

    /** 客户端为一次用户操作生成并在重试时复用的幂等键。 */
    @NotBlank(message = "请求标识不能为空")
    @Size(max = 64, message = "请求标识过长")
    @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "请求标识格式错误")
    private String requestId;

    @Size(max = 100, message = "项目名称过长")
    private String projectName;
}
