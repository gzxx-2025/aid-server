package com.aid.banner.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.banner.dto.HomeBannerListRequest;
import com.aid.banner.vo.HomeBannerVO;

/**
 * 首页 Banner - C 端只读 Service 接口
 *
 * @author 视觉AID
 */
public interface IHomeBannerService
{
    /**
     * 分页查询当前可展示的首页 Banner 列表
     * 仅返回 status=0（显示）且处于生效时间区间内的记录，按 sortOrder 升序。
     *
     * @param request 查询请求（pageNum/pageSize 可选）
     * @return 首页 Banner 分页结果
     */
    IPage<HomeBannerVO> listEnabledBanners(HomeBannerListRequest request);
}
