package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * AI音色标签字典对象 aid_ai_voice_tag
 * 仅承载音色库的三类业务标签：{@code character_type} / {@code voice_style} / {@code tone}。
 * 情感标签不在本表：情感能力以供应商声明为唯一标准（{@code aid_ai_model.capability_json.emotions}）。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_ai_voice_tag")
public class AidAiVoiceTag extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 标签类型：character_type / voice_style / tone */
    @Excel(name = "标签类型")
    private String tagType;

    /** 标签编码（同一 tag_type 下唯一，音色库中存的就是它） */
    @Excel(name = "标签编码")
    private String tagCode;

    /** 标签展示名 */
    @Excel(name = "标签展示名")
    private String tagName;

    /** 排序（越大越靠前） */
    @Excel(name = "排序")
    private Integer sortOrder;

    /** 状态：0启用 1停用 */
    @Excel(name = "状态")
    private String status;

    /** 删除标志：0存在 2删除 */
    private String delFlag;

}
