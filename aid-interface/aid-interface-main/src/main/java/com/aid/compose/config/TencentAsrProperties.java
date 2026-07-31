package com.aid.compose.config;

import lombok.Data;

/**
 * 腾讯云录音文件识别配置（category=tencent_asr）。
 *
 * @author 视觉AID
 */
@Data
public class TencentAsrProperties {

    /** 自动字幕总开关。 */
    private Boolean enabled = false;

    /** 腾讯云 SecretId。 */
    private String secretId;

    /** 腾讯云 SecretKey。 */
    private String secretKey;

    /** ASR 接口地域。 */
    private String region = "ap-guangzhou";

    /** 识别引擎，默认中英大模型 2.0。 */
    private String engineModelType = "16k_zh_en_2.0";

    /** 单行字幕最大字数，腾讯云允许 6~40。 */
    private int sentenceMaxLength = 10;

    /** 是否开启说话人分离：0=关闭，1=开启。 */
    private int speakerDiarization = 0;

    /** 热词表 ID。 */
    private String hotwordId;

    /** 本次请求临时热词。 */
    private String hotwordList;

    /** 单个分镜识别总等待上限。 */
    private int timeoutSeconds = 180;

    /** 单个分镜完整识别尝试次数。 */
    private int maxAttempts = 2;
}
