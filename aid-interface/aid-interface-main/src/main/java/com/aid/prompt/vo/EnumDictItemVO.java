package com.aid.prompt.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 枚举字典项 VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class EnumDictItemVO {

    /** 枚举值 */
    private String value;

    /** 枚举中文描述 */
    private String desc;
}
