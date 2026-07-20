package com.aid.episode.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户剧集删除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserEpisodeDeleteRequest {

    /** 剧集ID */
    @NotNull(message = "剧集ID不能为空")
    private Long id;
}
