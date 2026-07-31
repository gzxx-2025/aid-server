package com.aid.asset.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 用户资产详情VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class UserAssetVO {

    /** 主键 */
    private Long id;

    /** 资产类型 */
    private String assetType;

    /** 资产名称 */
    private String assetName;

    /** 性格/特征描述 */
    private String personalityDesc;

    /** 主图URL（出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
