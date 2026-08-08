package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidUserComicAssetMapper;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户自定义漫画参考资产Service业务层处理
 *
 * @author 视觉AID
 */
@Service
@Slf4j
public class AidUserComicAssetServiceImpl extends ServiceImpl<AidUserComicAssetMapper, AidUserComicAsset> implements IAidUserComicAssetService
{
    private static final String ASSET_TYPE_STYLE = "style";

    @Autowired
    private AidUserComicAssetMapper aidUserComicAssetMapper;

    /**
     * 查询用户自定义漫画参考资产
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 用户自定义漫画参考资产
     */
    @Override
    public AidUserComicAsset selectAidUserComicAssetById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询用户自定义漫画参考资产列表
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 用户自定义漫画参考资产
     */
    @Override
    public List<AidUserComicAsset> selectAidUserComicAssetList(AidUserComicAsset aidUserComicAsset)
    {
        LambdaQueryWrapper<AidUserComicAsset> wrapper = Wrappers.lambdaQuery();
        // 查询字段精简：后台列表不加载隐藏长模板，详情接口按主键读取完整记录。
        wrapper.select(AidUserComicAsset::getId, AidUserComicAsset::getUserId,
                AidUserComicAsset::getAssetType, AidUserComicAsset::getAssetName,
                AidUserComicAsset::getPersonalityDesc, AidUserComicAsset::getPromptText,
                AidUserComicAsset::getImageUrl, AidUserComicAsset::getSourceType,
                AidUserComicAsset::getSortOrder, AidUserComicAsset::getStatus,
                AidUserComicAsset::getDelFlag, AidUserComicAsset::getCreateTime,
                AidUserComicAsset::getCreateBy, AidUserComicAsset::getUpdateTime,
                AidUserComicAsset::getUpdateBy, AidUserComicAsset::getRemark);
        if (aidUserComicAsset != null)
        {
            if (aidUserComicAsset.getUserId() != null)
            {
                wrapper.eq(AidUserComicAsset::getUserId, aidUserComicAsset.getUserId());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getAssetType()))
            {
                wrapper.eq(AidUserComicAsset::getAssetType, aidUserComicAsset.getAssetType());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getAssetName()))
            {
                wrapper.like(AidUserComicAsset::getAssetName, aidUserComicAsset.getAssetName());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getSourceType()))
            {
                wrapper.eq(AidUserComicAsset::getSourceType, aidUserComicAsset.getSourceType());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getStatus()))
            {
                wrapper.eq(AidUserComicAsset::getStatus, aidUserComicAsset.getStatus());
            }
        }
        wrapper.orderByAsc(AidUserComicAsset::getSortOrder).orderByDesc(AidUserComicAsset::getId);
        return this.list(wrapper);
    }

    /**
     * 新增用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    @Override
    public int insertAidUserComicAsset(AidUserComicAsset aidUserComicAsset)
    {
        if (Objects.equals(ASSET_TYPE_STYLE, aidUserComicAsset.getAssetType()))
        {
            validateStylePrompt(aidUserComicAsset.getPromptText(), aidUserComicAsset.getId());
            aidUserComicAsset.setHiddenStylePromptJson(synchronizeCharacterPrompt(
                    aidUserComicAsset.getHiddenStylePromptJson(), aidUserComicAsset.getPromptText(),
                    aidUserComicAsset.getId()));
        }
        else
        {
            aidUserComicAsset.setHiddenStylePromptJson(normalizeHiddenStylePrompt(
                    aidUserComicAsset.getHiddenStylePromptJson(), aidUserComicAsset.getId()));
        }
        aidUserComicAsset.setCreateTime(DateUtils.getNowDate());
        return this.save(aidUserComicAsset) ? 1 : 0;
    }

    /**
     * 修改用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    @Override
    public int updateAidUserComicAsset(AidUserComicAsset aidUserComicAsset)
    {
        // 查询字段精简：仅用于判断更新后的类型并同步自定义风格角色模板。
        AidUserComicAsset stored = this.getOne(Wrappers.<AidUserComicAsset>lambdaQuery()
                .select(AidUserComicAsset::getId, AidUserComicAsset::getAssetType,
                        AidUserComicAsset::getPromptText, AidUserComicAsset::getHiddenStylePromptJson)
                .eq(AidUserComicAsset::getId, aidUserComicAsset.getId())
                .last("LIMIT 1"), false);
        String effectiveType = StrUtil.isNotBlank(aidUserComicAsset.getAssetType())
                ? aidUserComicAsset.getAssetType() : Objects.nonNull(stored) ? stored.getAssetType() : null;
        if (Objects.equals(ASSET_TYPE_STYLE, effectiveType))
        {
            String effectivePrompt = Objects.nonNull(aidUserComicAsset.getPromptText())
                    ? aidUserComicAsset.getPromptText() : Objects.nonNull(stored) ? stored.getPromptText() : null;
            validateStylePrompt(effectivePrompt, aidUserComicAsset.getId());
            if (Objects.nonNull(aidUserComicAsset.getPromptText())
                    || Objects.nonNull(aidUserComicAsset.getHiddenStylePromptJson()))
            {
                String sourceJson = Objects.nonNull(aidUserComicAsset.getHiddenStylePromptJson())
                        ? aidUserComicAsset.getHiddenStylePromptJson()
                        : Objects.nonNull(stored) ? stored.getHiddenStylePromptJson() : null;
                aidUserComicAsset.setHiddenStylePromptJson(synchronizeCharacterPrompt(
                        sourceJson, effectivePrompt, aidUserComicAsset.getId()));
            }
        }
        else
        {
            aidUserComicAsset.setHiddenStylePromptJson(normalizeHiddenStylePrompt(
                    aidUserComicAsset.getHiddenStylePromptJson(), aidUserComicAsset.getId()));
        }
        aidUserComicAsset.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidUserComicAsset) ? 1 : 0;
    }

    /**
     * 批量删除用户自定义漫画参考资产
     *
     * @param ids 需要删除的用户自定义漫画参考资产主键
     * @return 结果
     */
    @Override
    public int deleteAidUserComicAssetByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除用户自定义漫画参考资产信息
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 结果
     */
    @Override
    public int deleteAidUserComicAssetById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    private String normalizeHiddenStylePrompt(String json, Long id)
    {
        try
        {
            return HiddenStylePromptJsonUtils.normalize(json);
        }
        catch (IllegalArgumentException e)
        {
            log.error("用户风格隐藏模板格式错误: id={}", id, e);
            throw new ServiceException("隐藏风格格式错误");
        }
    }

    private String synchronizeCharacterPrompt(String json, String promptText, Long id)
    {
        try
        {
            return HiddenStylePromptJsonUtils.withCharacterPrompt(json, promptText);
        }
        catch (IllegalArgumentException e)
        {
            log.error("用户风格隐藏模板格式错误: id={}", id, e);
            throw new ServiceException("隐藏风格格式错误");
        }
    }

    private void validateStylePrompt(String promptText, Long id)
    {
        if (StrUtil.isBlank(promptText))
        {
            log.info("用户风格提示词为空: id={}", id);
            throw new ServiceException("提示词不能为空");
        }
    }
}
