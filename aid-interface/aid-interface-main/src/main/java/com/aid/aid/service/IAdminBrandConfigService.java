package com.aid.aid.service;

import com.aid.aid.domain.vo.AdminBrandConfigVO;

/**
 * 平台品牌配置服务。
 *
 * @author 视觉AID
 */
public interface IAdminBrandConfigService
{
    /**
     * 查询可公开展示的后台平台品牌配置。
     *
     * @return 后台平台品牌配置
     */
    AdminBrandConfigVO getPublicConfig();
}
