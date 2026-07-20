package com.aid.aid.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 提示词历史版本项VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PromptVersionItemVO {

    /** 版本号 */
    private Integer version;

    /** 版本描述 */
    private String description;

    /** 发布时间 */
    private String publishTime;
}
