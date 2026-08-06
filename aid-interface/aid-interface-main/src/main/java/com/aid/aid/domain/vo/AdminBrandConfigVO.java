package com.aid.aid.domain.vo;

import lombok.Data;

/**
 * 后台平台品牌公开配置。
 *
 * @author 视觉AID
 */
@Data
public class AdminBrandConfigVO
{
    /** 平台名称 */
    private String siteName;

    /** 平台 LOGO 地址 */
    private String platformLogoUrl;

    /** 浏览器页签图标地址 */
    private String faviconUrl;
}
