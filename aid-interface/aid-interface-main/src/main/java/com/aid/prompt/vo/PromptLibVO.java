package com.aid.prompt.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 提示词素材库VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PromptLibVO {

    /** 主键ID */
    private Long id;

    /** 所属用户ID (0=官方) */
    private Long userId;

    /** 提示词分类 */
    private String promptType;

    /** 提示词名称 */
    private String promptName;

    /** 提示词具体内容 */
    private String promptContent;

    /** 效果预览图URL（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 排序 */
    private Long sortOrder;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
