package com.aid.aid.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidHomeBanner;

/**
 * 首页 Banner 配置 Service 接口
 *
 * @author 视觉AID
 */
public interface IAidHomeBannerService extends IService<AidHomeBanner>
{
    /**
     * 查询首页 Banner 配置
     *
     * @param id 首页 Banner 配置主键
     * @return 首页 Banner 配置
     */
    AidHomeBanner selectAidHomeBannerById(Long id);

    /**
     * 查询首页 Banner 配置列表
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 首页 Banner 配置集合
     */
    List<AidHomeBanner> selectAidHomeBannerList(AidHomeBanner aidHomeBanner);

    /**
     * 新增首页 Banner 配置
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 结果
     */
    int insertAidHomeBanner(AidHomeBanner aidHomeBanner);

    /**
     * 修改首页 Banner 配置
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 结果
     */
    int updateAidHomeBanner(AidHomeBanner aidHomeBanner);

    /**
     * 批量删除首页 Banner 配置
     *
     * @param ids 需要删除的首页 Banner 配置主键集合
     * @return 结果
     */
    int deleteAidHomeBannerByIds(Long[] ids);

    /**
     * 删除首页 Banner 配置信息
     *
     * @param id 首页 Banner 配置主键
     * @return 结果
     */
    int deleteAidHomeBannerById(Long id);
}
