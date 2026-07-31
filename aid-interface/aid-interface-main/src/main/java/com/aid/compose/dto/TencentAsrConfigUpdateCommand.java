package com.aid.compose.dto;

import lombok.Data;

/** 腾讯云自动字幕后台配置更新命令。 */
@Data
public class TencentAsrConfigUpdateCommand {

    /** 自动字幕总开关。 */
    private Boolean enabled;

    /** 腾讯云 SecretId；脱敏值表示保持不变。 */
    private String secretId;

    /** 腾讯云 SecretKey；脱敏值表示保持不变。 */
    private String secretKey;

    /** 接口地域。 */
    private String region;

    /** 识别引擎。 */
    private String engineModelType;

    /** 单行字幕最大字数，范围 6~40。 */
    private Integer sentenceMaxLength;

    /** 说话人分离开关：0=关闭，1=开启。 */
    private Integer speakerDiarization;

    /** 腾讯云热词表 ID。 */
    private String hotwordId;

    /** 本次请求临时热词。 */
    private String hotwordList;

    /** 单分镜识别超时秒数。 */
    private Integer timeoutSeconds;

    /** 单分镜最大完整尝试次数。 */
    private Integer maxAttempts;
}
