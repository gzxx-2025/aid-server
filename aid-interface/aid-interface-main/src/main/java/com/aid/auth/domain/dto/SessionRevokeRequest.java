package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话移除请求。
 *
 * @author 视觉AID
 */
@Data
public class SessionRevokeRequest {

    /** 会话公开标识 */
    @NotBlank(message = "会话标识不能为空")
    @Size(min = 24, max = 24, message = "会话标识格式错误")
    private String sessionId;
}
