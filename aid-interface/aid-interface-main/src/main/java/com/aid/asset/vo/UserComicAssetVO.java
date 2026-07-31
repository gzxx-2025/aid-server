package com.aid.asset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

import java.util.Date;

/**
 * C端 - 用户自定义参考资产返回VO
 *
 * @author 视觉AID
 */
@Data
public class UserComicAssetVO {

    /** 资产ID */
    private Long id;

    /** 用户ID（仅详情返回） */
    private Long userId;

    /** 资产类型 */
    private String assetType;

    /** 资产名称 */
    private String assetName;

    /** 特征描述/生成约束 */
    private String personalityDesc;

    /** 提示词内容 */
    private String promptText;

    /** 主图URL（出参自动拼 CDN/localDomain） */
    @MediaUrl
    private String imageUrl;

    /** 来源类型：USER用户创建/OFFICIAL_COPY官方复制/AI_GENERATED AI生成 */
    private String sourceType;

    /** 排序值 */
    private Long sortOrder;

    /** 状态：0正常 1停用（仅详情返回） */
    private String status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
