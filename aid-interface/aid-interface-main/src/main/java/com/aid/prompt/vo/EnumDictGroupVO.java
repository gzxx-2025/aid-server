package com.aid.prompt.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 枚举字典分组 VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class EnumDictGroupVO {

    /** 枚举类型（类名 SimpleName） */
    private String enumType;

    /** 枚举项列表 */
    private List<EnumDictItemVO> items;
}
