package com.aid.script.dto;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户上传剧本文件请求DTO（multipart/form-data 表单绑定）
 * 文件走 multipart 文件 part；{@code projectId} / {@code episodeId} 为表单字段（同在请求体内，
 * 非 URL query），通过 {@code @ModelAttribute} 绑定到本对象。
 *
 * @author 视觉AID
 */
@Data
public class UserScriptUploadRequest {

    /** 项目ID（用于判断电影/剧集） */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 集数ID：电影传0；剧集传具体集ID=单集上传；不传/传0=整篇导入 */
    private Long episodeId;

    /** 剧本文件（仅 .txt） */
    private MultipartFile file;
}
