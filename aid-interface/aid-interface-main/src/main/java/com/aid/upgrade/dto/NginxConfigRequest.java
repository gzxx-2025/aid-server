package com.aid.upgrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/** 受管Nginx配置及并发修改校验参数。 */
@Data
public class NginxConfigRequest {
    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    @Schema(description = "读取配置时返回的修订指纹")
    private String expectedRevision;
    @Size(max = 255)
    @Schema(description = "Nginx可访问的HTTP(S)后端源地址")
    private String backendOrigin;
    @Size(max = 5)
    @Schema(description = "上传请求大小上限，MB")
    private String maxBodyMb;
    @Size(max = 4)
    @Schema(description = "上游读取超时，秒")
    private String readTimeoutSeconds;
    @Size(max = 3)
    @Schema(description = "上游连接超时，秒")
    private String connectTimeoutSeconds;
    @Size(max = 2048)
    @Schema(description = "白名单高级指令，单行分号分隔")
    private String extraDirectives;
}
