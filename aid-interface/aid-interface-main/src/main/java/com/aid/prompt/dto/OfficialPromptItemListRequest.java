package com.aid.prompt.dto;

import java.util.List;
import lombok.Data;

/**
 * 官方只读参数词库 - 词条列表查询入参
 * categoryCode 与 categoryCodes 二选一，至少传一个；
 * 分类必须来自 {@link com.aid.prompt.constant.OfficialPromptCategory} 白名单。
 *
 * @author 视觉AID
 */
@Data
public class OfficialPromptItemListRequest {

    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..50，默认 20 */
    private Integer pageSize;

    /** 单分类代码（可选，与 categoryCodes 二选一） */
    private String categoryCode;

    /** 多分类代码（可选，与 categoryCode 二选一） */
    private List<String> categoryCodes;

    /** 按 promptName 模糊搜索（可选） */
    private String keyword;
}
