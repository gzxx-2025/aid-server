package com.aid.script.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

/**
 * 用户剧本详情VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class UserScriptVO {

    /** 主键ID */
    private Long id;

    /** 项目ID */
    private Long projectId;

    /** 集数ID */
    private Long episodeId;

    /** 用户上传的原版剧本 */
    private String originalText;

    /** AI简化版剧本 */
    private String simplifiedText;

    /** 是否已执行资产提取(0否 1是) */
    private Integer isExtracted;

    /** 剧本版本号 */
    private Integer comicVersion;

    /** 状态(0草稿 1使用 2历史版本) */
    private Integer status;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
