package com.aid.asset.service;

import java.util.List;
import com.aid.aid.domain.AidComicAsset;
import com.aid.asset.dto.OfficialAssetQueryRequest;

/**
 * 用户资产业务Service接口
 *
 * @author 视觉AID
 */
public interface IUserAssetBusinessService
{
    /**
     * 查询官方素材列表，从 aid_comic_asset 表查询可复用素材，
     * 不用于查询角色/场景/道具主资产
     *
     * @param request 查询条件
     * @return 素材列表
     */
    List<AidComicAsset> queryOfficialAssetList(OfficialAssetQueryRequest request);
}
