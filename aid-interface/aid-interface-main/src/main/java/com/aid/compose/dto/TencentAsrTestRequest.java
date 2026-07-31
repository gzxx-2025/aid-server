package com.aid.compose.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/** 腾讯云语音识别测试请求。 */
@Data
public class TencentAsrTestRequest {

    /** 待识别视频，仅支持腾讯云录音文件识别可直接读取的视频格式。 */
    @NotNull(message = "请选择测试视频")
    private MultipartFile file;
}
