package com.aid.aid.domain.vo;

import lombok.Data;

/**
 * 后台管理端品牌图片配置。
 *
 * @author 视觉AID
 */
@Data
public class AdminBrandConfigVO
{
    /** 登录页品牌 Logo 地址 */
    private String loginLogoUrl;

    /** 后台左上角 Logo 地址 */
    private String sidebarLogoUrl;

    /** 浏览器页签图标地址 */
    private String faviconUrl;
}
