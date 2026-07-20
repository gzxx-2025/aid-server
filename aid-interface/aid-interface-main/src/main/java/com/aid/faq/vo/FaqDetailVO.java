package com.aid.faq.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Builder;
import lombok.Data;

/**
 * 常见问题详情 VO（C 端，含完整内容）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class FaqDetailVO
{
    /** 主键ID */
    private Long id;

    /** 问题标题 */
    private String title;

    /** 分类 */
    private String category;

    /** 问题完整内容（答案明细） */
    private String content;

    /** 浏览次数 */
    private Long viewCount;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
}
