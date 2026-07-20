package com.aid.asset.dto;

import lombok.Data;

/**
 * C端 - 查询用户自定义参考资产列表请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserComicAssetListRequest {

    /** 资产类型（不传查全部允许类型；传入必须在C端白名单内） */
    private String assetType;

    /** 资产名称模糊关键字 */
    private String keyword;

    /** 页码，默认1 */
    private Integer pageNum;

    /** 每页数量，默认20 */
    private Integer pageSize;
}
