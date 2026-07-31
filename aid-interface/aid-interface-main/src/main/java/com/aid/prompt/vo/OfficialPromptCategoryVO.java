package com.aid.prompt.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 官方只读参数词库 - 分类 VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class OfficialPromptCategoryVO {

    /** 分类代码 */
    private String categoryCode;

    /** 分类中文名称 */
    private String categoryName;

    /** 分类下官方词条数量 */
    private Integer itemCount;

    /** 展示排序（越小越靠前） */
    private Integer sortOrder;
}
