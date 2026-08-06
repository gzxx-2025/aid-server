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
 * 后台平台品牌配置。
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
     * 查询可公开展示的后台平台名称与品牌图片。
     */
    @Anonymous
    @GetMapping("/public")
    public AjaxResult getPublicConfig()
    {
        return success(adminBrandConfigService.getPublicConfig());
    }
}
