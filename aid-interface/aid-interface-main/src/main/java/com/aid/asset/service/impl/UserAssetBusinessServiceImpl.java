package com.aid.asset.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.asset.constants.UserAssetTypeConstants;
import com.aid.asset.dto.OfficialAssetQueryRequest;
import com.aid.asset.service.IUserAssetBusinessService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.StringUtils;
import com.aid.aid.vo.StyleCategoryVO;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户资产业务Service实现：C 端只读查询官方素材（aid_comic_asset 白名单类型）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserAssetBusinessServiceImpl implements IUserAssetBusinessService
{
    @Autowired
    private IAidComicAssetService aidComicAssetService;

    @Override
    public List<AidComicAsset> queryOfficialAssetList(OfficialAssetQueryRequest request)
    {
        // 空请求体兜底：视为按默认白名单全量查询
        if (Objects.isNull(request)) {
            request = new OfficialAssetQueryRequest();
        }
        // 官方素材额外支持背景音乐；个人参考资产 CRUD 不开放 bgm 类型。
        String assetType = Objects.isNull(request.getAssetType()) ? null : request.getAssetType().trim();
        if (StringUtils.isNotEmpty(assetType) && !UserAssetTypeConstants.OFFICIAL_ASSET_TYPES.contains(assetType)) {
            log.error("官方素材查询-类型非法: assetType={}", assetType);
            throw new ServiceException("类型错误");
        }
        String assetName = Objects.isNull(request.getAssetName()) ? null : request.getAssetName().trim();
        String categoryCode = aidComicAssetService.normalizeStyleCategoryFilter(request.getCategoryCode());
        if (StringUtils.isNotEmpty(categoryCode) && StringUtils.isNotEmpty(assetType)
                && !Objects.equals("style", assetType)) {
            log.error("官方素材查询-分类仅适用于风格: assetType={}, categoryCode={}", assetType, categoryCode);
            throw new ServiceException("分类仅限风格");
        }

        LambdaQueryWrapper<AidComicAsset> wrapper = Wrappers.lambdaQuery();
        // 查询字段精简：C端官方素材不读取隐藏风格模板。
        wrapper.select(AidComicAsset::getId, AidComicAsset::getAssetType,
                AidComicAsset::getAssetName, AidComicAsset::getPromptText,
                AidComicAsset::getImageUrl, AidComicAsset::getIsRecommended,
                AidComicAsset::getSortOrder);
        wrapper.eq(AidComicAsset::getDelFlag, "0");
        if (StringUtils.isNotEmpty(assetType)) {
            wrapper.eq(AidComicAsset::getAssetType, assetType);
        } else {
            // 未传 assetType 时仅返回C端白名单内的类型，避免泄露 character/scene/prop 等非C端类型
            wrapper.in(AidComicAsset::getAssetType, UserAssetTypeConstants.OFFICIAL_ASSET_TYPES);
        }
        wrapper.like(StringUtils.isNotEmpty(assetName), AidComicAsset::getAssetName, assetName);
        aidComicAssetService.applyStyleCategoryFilter(wrapper, categoryCode);
        wrapper.orderByDesc(AidComicAsset::getIsRecommended)
                .orderByAsc(AidComicAsset::getSortOrder)
                .orderByAsc(AidComicAsset::getId);
        List<AidComicAsset> result = aidComicAssetService.list(wrapper);
        aidComicAssetService.attachStyleCategories(result);
        return result;
    }

    @Override
    public List<StyleCategoryVO> listStyleCategoryOptions()
    {
        return aidComicAssetService.listStyleCategoryOptions(true);
    }
}
