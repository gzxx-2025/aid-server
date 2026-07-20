package com.aid.faq.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Builder;
import lombok.Data;

/**
 * 常见问题列表项 VO（C 端，不含完整内容）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class FaqListItemVO
{
    /** 主键ID */
    private Long id;

    /** 问题标题 */
    private String title;

    /** 分类 */
    private String category;

    /** 排序（值越小越靠前） */
    private Integer sortOrder;

    /** 浏览次数 */
    private Long viewCount;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
}
