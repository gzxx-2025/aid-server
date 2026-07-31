package com.aid.prompt.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 官方只读参数词库 - 词条 VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class OfficialPromptItemVO {

    /** 主键ID */
    private Long id;

    /** 分类代码（对应 prompt_type） */
    private String categoryCode;

    /** 分类中文名称 */
    private String categoryName;

    /** 词条名称（对应 prompt_name） */
    private String itemName;

    /** 中文提示词内容 */
    private String promptText;

    /** 英文提示词内容 */
    private String promptTextEn;

    /** 效果预览图URL（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 展示排序 */
    private Long sortOrder;

    /** 备注 */
    private String remark;
}
