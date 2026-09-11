package com.aid.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户项目公开请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectPublishRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;

    /** 项目描述 */
    @NotBlank(message = "请填写项目描述")
    @Size(max = 500, message = "项目描述过长")
    private String projectDesc;

    /** 项目封面图（仅允许上传后的本站资源地址，入库时自动剥离域名） */
    @NotBlank(message = "请上传封面图")
    @Size(max = 500, message = "封面地址过长")
    private String coverUrl;
}
