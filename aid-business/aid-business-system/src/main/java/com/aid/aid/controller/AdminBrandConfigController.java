package com.aid.aid.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.service.IAdminBrandConfigService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;

/**
 * 后台管理端品牌图片配置（登录 Logo / 侧栏 Logo / 页签图标）。
 * 公开接口供登录页在未鉴权时拉取展示配置。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/adminBrand")
public class AdminBrandConfigController extends BaseController
{
    @Autowired
    private IAdminBrandConfigService adminBrandConfigService;

    /**
     * 查询可公开展示的后台品牌图片配置（匿名可访问）。
     */
    @Anonymous
    @GetMapping("/public")
    public AjaxResult getPublicConfig()
    {
        return success(adminBrandConfigService.getPublicConfig());
    }
}
