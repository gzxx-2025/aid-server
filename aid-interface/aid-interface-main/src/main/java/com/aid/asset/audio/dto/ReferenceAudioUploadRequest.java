package com.aid.asset.audio.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 参考音频上传登记请求。
 * 文件本身走 {@code /api/user/oss/upload} 拿到相对路径后，再调本接口登记，
 * 由服务端探测时长、卡格式与配额并落库。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioUploadRequest {

    /** 所属项目ID */
    @NotNull(message = "项目不能空")
    private Long projectId;

    /** 所属剧集ID；电影项目可不传，剧集项目必传 */
    private Long episodeId;

    /** 音频名称（列表选择用） */
    @NotBlank(message = "名称不能空")
    @Size(max = 128, message = "名称过长")
    private String audioName;

    /** 音频地址（本站已上传资源的相对路径；@MediaUrl 剥离本站域名） */
    @MediaUrl
    @NotBlank(message = "音频不能空")
    @Size(max = 500, message = "音频地址过长")
    private String audioUrl;
}
