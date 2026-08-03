package com.aid.aid.domain.vo;

import lombok.Data;

/**
 * 平台品牌图片公开配置。
 *
 * @author 视觉AID
 */
@Data
public class AdminBrandConfigVO
{
    /** 平台 LOGO 地址 */
    private String platformLogoUrl;

    /** 浏览器页签图标地址 */
    private String faviconUrl;
}
