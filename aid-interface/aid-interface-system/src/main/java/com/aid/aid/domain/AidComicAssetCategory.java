package com.aid.aid.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 官方风格与分类的多对多关系。
 *
 * @author 视觉AID
 */
@Data
@TableName("aid_comic_asset_category")
public class AidComicAssetCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 官方风格资产ID */
    private Long assetId;

    /** 稳定分类代码 */
    private String categoryCode;
}
