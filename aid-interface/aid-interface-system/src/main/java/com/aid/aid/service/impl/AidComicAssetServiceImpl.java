package com.aid.aid.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidComicAssetCategory;
import com.aid.aid.enums.StyleCategoryEnum;
import com.aid.aid.service.IAidComicAssetCategoryService;
import com.aid.aid.vo.StyleCategoryVO;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidComicAssetMapper;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目提取资产Service业务层处理
 *
 * @author 视觉AID
 */
@Service
@Slf4j
public class AidComicAssetServiceImpl extends ServiceImpl<AidComicAssetMapper, AidComicAsset> implements IAidComicAssetService
{
    private static final String ASSET_TYPE_STYLE = "style";
    private static final int DEFAULT_SORT_ORDER = 1000;
    private static final int MAX_SORT_ORDER = 999999;

    @Autowired
    private IAidComicAssetCategoryService aidComicAssetCategoryService;

    /**
     * 查询项目提取资产
     *
     * @param id 项目提取资产主键
     * @return 项目提取资产
     */
    @Override
    public AidComicAsset selectAidComicAssetById(Long id)
    {
        AidComicAsset asset = this.getById(id);
        if (Objects.nonNull(asset)) {
            attachStyleCategories(List.of(asset));
        }
        return asset;
    }

    /**
     * 查询项目提取资产列表
     *
     * @param aidComicAsset 项目提取资产
     * @return 项目提取资产
     */
    @Override
    public List<AidComicAsset> selectAidComicAssetList(AidComicAsset aidComicAsset)
    {
        LambdaQueryWrapper<AidComicAsset> lambdaQueryWrapper = Wrappers.lambdaQuery();
        // 查询字段精简：后台列表不加载隐藏长模板，详情接口按主键读取完整记录。
        lambdaQueryWrapper.select(AidComicAsset::getId, AidComicAsset::getAssetType,
                AidComicAsset::getAssetName, AidComicAsset::getPersonalityDesc,
                AidComicAsset::getPromptText, AidComicAsset::getImageUrl,
                AidComicAsset::getIsRecommended, AidComicAsset::getSortOrder,
                AidComicAsset::getDelFlag, AidComicAsset::getCreateTime,
                AidComicAsset::getCreateBy, AidComicAsset::getUpdateTime,
                AidComicAsset::getUpdateBy, AidComicAsset::getRemark);
        // 动态查询条件
        if (StrUtil.isNotBlank(aidComicAsset.getAssetType())) {
            lambdaQueryWrapper.eq(AidComicAsset::getAssetType, aidComicAsset.getAssetType());
        }
        if (StrUtil.isNotBlank(aidComicAsset.getAssetName())) {
            lambdaQueryWrapper.like(AidComicAsset::getAssetName, aidComicAsset.getAssetName());
        }
        if (Objects.nonNull(aidComicAsset.getIsRecommended())) {
            lambdaQueryWrapper.eq(AidComicAsset::getIsRecommended, aidComicAsset.getIsRecommended());
        }
        String categoryCode = normalizeStyleCategoryFilter(aidComicAsset.getCategoryCode());
        applyStyleCategoryFilter(lambdaQueryWrapper, categoryCode);
        if (Objects.equals(ASSET_TYPE_STYLE, aidComicAsset.getAssetType()) || StrUtil.isNotBlank(categoryCode)) {
            lambdaQueryWrapper.orderByDesc(AidComicAsset::getIsRecommended)
                    .orderByAsc(AidComicAsset::getSortOrder)
                    .orderByAsc(AidComicAsset::getId);
        } else {
            lambdaQueryWrapper.orderByDesc(AidComicAsset::getCreateTime)
                    .orderByDesc(AidComicAsset::getId);
        }
        List<AidComicAsset> list = this.list(lambdaQueryWrapper);
        attachStyleCategories(list);
        return list;
    }

    /**
     * 新增项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertAidComicAsset(AidComicAsset aidComicAsset)
    {
        normalizeAndValidateStyleSettings(aidComicAsset, false);
        aidComicAsset.setHiddenStylePromptJson(normalizeHiddenStylePrompt(
                aidComicAsset.getHiddenStylePromptJson(), aidComicAsset.getId()));
        aidComicAsset.setCreateTime(DateUtils.getNowDate());
        boolean saved = this.save(aidComicAsset);
        if (saved) {
            replaceCategoryRelations(aidComicAsset.getId(), aidComicAsset.getCategoryCodes());
        }
        return saved ? 1 : 0;
    }

    /**
     * 修改项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateAidComicAsset(AidComicAsset aidComicAsset)
    {
        normalizeAndValidateStyleSettings(aidComicAsset, true);
        aidComicAsset.setHiddenStylePromptJson(normalizeHiddenStylePrompt(
                aidComicAsset.getHiddenStylePromptJson(), aidComicAsset.getId()));
        aidComicAsset.setUpdateTime(DateUtils.getNowDate());
        boolean updated = this.updateById(aidComicAsset);
        if (updated) {
            replaceCategoryRelations(aidComicAsset.getId(), aidComicAsset.getCategoryCodes());
        }
        return updated ? 1 : 0;
    }

    /**
     * 批量删除项目提取资产
     *
     * @param ids 需要删除的项目提取资产主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAidComicAssetByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        List<Long> idList = Arrays.asList(ids);
        boolean removed = this.removeByIds(idList);
        if (!removed)
        {
            return 0;
        }
        aidComicAssetCategoryService.remove(Wrappers.<AidComicAssetCategory>lambdaQuery()
                .in(AidComicAssetCategory::getAssetId, idList));
        return 1;
    }

    /**
     * 删除项目提取资产信息
     *
     * @param id 项目提取资产主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAidComicAssetById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        boolean removed = this.removeById(id);
        if (!removed)
        {
            return 0;
        }
        aidComicAssetCategoryService.remove(Wrappers.<AidComicAssetCategory>lambdaQuery()
                .eq(AidComicAssetCategory::getAssetId, id));
        return 1;
    }

    @Override
    public String normalizeStyleCategoryFilter(String categoryCode) {
        if (StrUtil.isBlank(categoryCode) || Objects.equals(StyleCategoryEnum.ALL_CODE, categoryCode.trim())) {
            return null;
        }
        String normalized = categoryCode.trim();
        if (Objects.isNull(StyleCategoryEnum.fromCode(normalized))) {
            log.error("风格分类筛选非法: categoryCode={}", categoryCode);
            throw new ServiceException("分类错误");
        }
        return normalized;
    }

    @Override
    public void applyStyleCategoryFilter(LambdaQueryWrapper<AidComicAsset> wrapper, String categoryCode) {
        if (StrUtil.isBlank(categoryCode)) {
            return;
        }
        wrapper.eq(AidComicAsset::getAssetType, ASSET_TYPE_STYLE);
        wrapper.apply("EXISTS (SELECT 1 FROM aid_comic_asset_category ac "
                + "WHERE ac.asset_id = aid_comic_asset.id AND ac.category_code = {0})", categoryCode);
    }

    @Override
    public void attachStyleCategories(List<AidComicAsset> assets) {
        if (CollectionUtil.isEmpty(assets)) {
            return;
        }
        List<Long> styleIds = assets.stream()
                .filter(a -> Objects.equals(ASSET_TYPE_STYLE, a.getAssetType()))
                .map(AidComicAsset::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<StyleCategoryVO>> grouped = new HashMap<>();
        if (CollectionUtil.isNotEmpty(styleIds)) {
            List<AidComicAssetCategory> relations = aidComicAssetCategoryService.list(
                    Wrappers.<AidComicAssetCategory>lambdaQuery()
                            .select(AidComicAssetCategory::getAssetId, AidComicAssetCategory::getCategoryCode)
                            .in(AidComicAssetCategory::getAssetId, styleIds));
            grouped = relations.stream()
                    .filter(r -> Objects.nonNull(StyleCategoryEnum.fromCode(r.getCategoryCode())))
                    .sorted(Comparator.comparingInt(r ->
                            StyleCategoryEnum.fromCode(r.getCategoryCode()).getSortOrder()))
                    .collect(Collectors.groupingBy(AidComicAssetCategory::getAssetId,
                            HashMap::new,
                            Collectors.mapping(r -> toCategoryVO(
                                            StyleCategoryEnum.fromCode(r.getCategoryCode())),
                                    Collectors.toList())));
        }
        for (AidComicAsset asset : assets) {
            List<StyleCategoryVO> categories = new ArrayList<>(grouped.getOrDefault(asset.getId(), List.of()));
            asset.setCategories(categories);
            asset.setCategoryCodes(categories.stream().map(StyleCategoryVO::getCode).toList());
        }
    }

    @Override
    public List<StyleCategoryVO> listStyleCategoryOptions(boolean includeAll) {
        List<StyleCategoryVO> result = new ArrayList<>();
        if (includeAll) {
            result.add(new StyleCategoryVO(StyleCategoryEnum.ALL_CODE, "全部"));
        }
        result.addAll(StyleCategoryEnum.sortedValues().stream()
                .map(this::toCategoryVO)
                .toList());
        return result;
    }

    private void normalizeAndValidateStyleSettings(AidComicAsset asset, boolean editing) {
        if (Objects.isNull(asset)) {
            log.error("官方素材保存失败: 请求为空");
            throw new ServiceException("参数错误");
        }
        AidComicAsset current = null;
        if (editing) {
            if (Objects.isNull(asset.getId())) {
                log.error("官方素材修改失败: ID为空");
                throw new ServiceException("参数错误");
            }
            current = this.getById(asset.getId());
            if (Objects.isNull(current)) {
                log.error("官方素材修改失败: id={}", asset.getId());
                throw new ServiceException("素材不存在");
            }
        }
        String assetType = StrUtil.isBlank(asset.getAssetType()) && Objects.nonNull(current)
                ? current.getAssetType() : StrUtil.trim(asset.getAssetType());
        asset.setAssetType(assetType);
        int sortOrder = Objects.isNull(asset.getSortOrder())
                ? (Objects.nonNull(current) && Objects.nonNull(current.getSortOrder())
                        ? current.getSortOrder() : DEFAULT_SORT_ORDER)
                : asset.getSortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            log.error("官方素材排序号非法: id={}, sortOrder={}", asset.getId(), sortOrder);
            throw new ServiceException("排序号错误");
        }
        asset.setSortOrder(sortOrder);

        if (!Objects.equals(ASSET_TYPE_STYLE, assetType)) {
            asset.setIsRecommended(false);
            asset.setCategories(List.of());
            asset.setCategoryCodes(List.of());
            return;
        }
        if (Objects.isNull(asset.getIsRecommended()) && Objects.nonNull(current)) {
            asset.setIsRecommended(Boolean.TRUE.equals(current.getIsRecommended()));
        } else {
            asset.setIsRecommended(Boolean.TRUE.equals(asset.getIsRecommended()));
        }
        List<String> categoryCodes = asset.getCategoryCodes();
        if (categoryCodes == null && Objects.nonNull(current)) {
            attachStyleCategories(List.of(current));
            categoryCodes = current.getCategoryCodes();
        }
        if (CollectionUtil.isEmpty(categoryCodes)) {
            log.error("官方风格分类为空: id={}", asset.getId());
            throw new ServiceException("请选择分类");
        }
        Set<String> distinctCodes = new LinkedHashSet<>();
        for (String categoryCode : categoryCodes) {
            String code = StrUtil.trim(categoryCode);
            if (StrUtil.isBlank(code) || Objects.equals(StyleCategoryEnum.ALL_CODE, code)
                    || Objects.isNull(StyleCategoryEnum.fromCode(code))) {
                log.error("官方风格分类非法: id={}, code={}", asset.getId(), code);
                throw new ServiceException("分类错误");
            }
            distinctCodes.add(code);
        }
        asset.setCategoryCodes(distinctCodes.stream()
                .map(StyleCategoryEnum::fromCode)
                .sorted(Comparator.comparingInt(StyleCategoryEnum::getSortOrder))
                .map(StyleCategoryEnum::getCode)
                .toList());
    }

    private void replaceCategoryRelations(Long assetId, List<String> categoryCodes) {
        aidComicAssetCategoryService.remove(Wrappers.<AidComicAssetCategory>lambdaQuery()
                .eq(AidComicAssetCategory::getAssetId, assetId));
        if (CollectionUtil.isEmpty(categoryCodes)) {
            return;
        }
        List<AidComicAssetCategory> relations = categoryCodes.stream().map(code -> {
            AidComicAssetCategory relation = new AidComicAssetCategory();
            relation.setAssetId(assetId);
            relation.setCategoryCode(code);
            return relation;
        }).toList();
        if (!aidComicAssetCategoryService.saveBatch(relations)) {
            log.error("官方风格分类关系保存失败: assetId={}", assetId);
            throw new ServiceException("分类保存失败");
        }
    }

    private StyleCategoryVO toCategoryVO(StyleCategoryEnum category) {
        return new StyleCategoryVO(category.getCode(), category.getLabel());
    }

    private String normalizeHiddenStylePrompt(String json, Long id)
    {
        try
        {
            return HiddenStylePromptJsonUtils.normalize(json);
        }
        catch (IllegalArgumentException e)
        {
            log.error("官方风格隐藏模板格式错误: id={}", id, e);
            throw new ServiceException("隐藏风格格式错误");
        }
    }
}
