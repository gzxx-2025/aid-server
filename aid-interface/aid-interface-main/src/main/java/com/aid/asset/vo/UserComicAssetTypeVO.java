package com.aid.asset.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C端用户自定义参考资产 - 类型字典项VO
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserComicAssetTypeVO {

    /** 类型编码 */
    private String code;

    /** 类型名称 */
    private String name;

    /** 类型说明 */
    private String description;
}
