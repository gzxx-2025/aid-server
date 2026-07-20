package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 常见问题（FAQ）对象 aid_faq
 *
 * 用于配置 C 端「常见问题」帮助中心：列表展示标题/分类/发布时间，详情展示完整内容。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_faq")
public class AidFaq extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 问题标题 */
    @Excel(name = "问题标题")
    private String title;

    /** 分类（可空，如：账号、充值、生成、其他） */
    @Excel(name = "分类")
    private String category;

    /** 问题完整内容（答案明细，支持富文本/长文本） */
    @Excel(name = "问题内容")
    private String content;

    /** 排序（值越小越靠前） */
    @Excel(name = "排序")
    private Integer sortOrder;

    /** 浏览次数 */
    @Excel(name = "浏览次数")
    private Long viewCount;

    /** 状态（0显示 1隐藏） */
    @Excel(name = "状态", readConverterExp = "0=显示,1=隐藏")
    private String status;

    /** 发布时间 */
    @Excel(name = "发布时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;
}
