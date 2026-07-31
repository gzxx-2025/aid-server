package com.aid.aid.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.vo.AdminBrandConfigVO;
import com.aid.aid.service.IAdminBrandConfigService;
import com.aid.aid.service.IAidConfigService;

/**
 * 后台管理端品牌配置：从 aid_config(category=admin_brand) 读取登录 Logo / 侧栏 Logo / 页签图标。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AdminBrandConfigServiceImpl implements IAdminBrandConfigService
{
    /** 配置分类 */
    public static final String CATEGORY = "admin_brand";

    /** 登录页品牌 Logo */
    public static final String KEY_LOGIN_LOGO = "login_logo_url";

    /** 后台左上角 Logo */
    public static final String KEY_SIDEBAR_LOGO = "sidebar_logo_url";

    /** 浏览器页签图标 */
    public static final String KEY_FAVICON = "favicon_url";

    @Autowired
    private IAidConfigService aidConfigService;

    @Override
    public AdminBrandConfigVO getPublicConfig()
    {
        AdminBrandConfigVO vo = new AdminBrandConfigVO();
        try
        {
            AidConfig query = new AidConfig();
            query.setCategory(CATEGORY);
            // 只查本分类，空值时前端回退到内置默认图
            List<AidConfig> list = aidConfigService.selectAidConfigList(query);
            if (CollectionUtil.isEmpty(list))
            {
                return vo;
            }
            for (AidConfig item : list)
            {
                if (Objects.isNull(item) || StrUtil.isBlank(item.getConfigName()))
                {
                    continue;
                }
                String value = StrUtil.trimToEmpty(item.getConfigValue());
                if (StrUtil.isBlank(value))
                {
                    continue;
                }
                if (Objects.equals(KEY_LOGIN_LOGO, item.getConfigName()))
                {
                    vo.setLoginLogoUrl(value);
                }
                else if (Objects.equals(KEY_SIDEBAR_LOGO, item.getConfigName()))
                {
                    vo.setSidebarLogoUrl(value);
                }
                else if (Objects.equals(KEY_FAVICON, item.getConfigName()))
                {
                    vo.setFaviconUrl(value);
                }
            }
        }
        catch (Exception e)
        {
            // 配置读取失败不影响登录/后台主流程，前端继续用内置默认图
            log.error("读取后台品牌配置异常", e);
        }
        return vo;
    }
}
