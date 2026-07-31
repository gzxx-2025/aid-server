package com.aid.script.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户剧本删除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserScriptDeleteRequest {

    /** 剧本ID */
    @NotNull(message = "剧本ID不能为空")
    private Long id;
}
