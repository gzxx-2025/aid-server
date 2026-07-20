package com.aid.prompt.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 提示词文件内容VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PromptFileContentVO {

    /** 中文提示词内容 */
    private String zhContent;

    /** 英文提示词内容 */
    private String enContent;
}
