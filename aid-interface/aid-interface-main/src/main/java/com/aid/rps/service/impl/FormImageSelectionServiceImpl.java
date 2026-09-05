package com.aid.rps.service.impl;

import java.util.Date;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.rps.service.IFormImageSelectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 形态图片使用状态规则实现。
 * 所有写入先锁定形态行，保证同一形态的选择、取消和生成落库串行执行。
 */
@Slf4j
@Service
public class FormImageSelectionServiceImpl implements IFormImageSelectionService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String ASSET_TYPE_PROP = "prop";

    @Autowired
    private IAidRolePropSceneFormImageService formImageService;

    @Autowired
    private IAidRolePropSceneFormService formService;

    @Autowired
    private IAidRolePropSceneService assetService;

    @Override
    @Transactional
    public void selectImage(Long imageId, Long userId)
    {
        AidRolePropSceneFormImage probe = requireImage(imageId, userId);
        AidRolePropSceneForm form = lockForm(probe.getFormId(), userId);
        AidRolePropSceneFormImage image = requireImageInForm(imageId, form.getId(), userId);
        AidRolePropScene asset = requireAsset(form, userId);
        validateAssetType(asset.getAssetType(), form.getId());

        Date now = DateUtils.getNowDate();
        if (isSinglePrimaryType(asset.getAssetType()))
        {
            clearSelectedImages(form.getId(), imageId, userId, now);
        }
        if (!Objects.equals(image.getIsUse(), 1))
        {
            setImageSelection(imageId, form.getId(), userId, 1, now);
        }
        log.info("形态图片设为使用中: imageId={}, formId={}, assetType={}",
                imageId, form.getId(), asset.getAssetType());
    }

    @Override
    @Transactional
    public void unselectImage(Long imageId, Long userId)
    {
        AidRolePropSceneFormImage probe = requireImage(imageId, userId);
        AidRolePropSceneForm form = lockForm(probe.getFormId(), userId);
        AidRolePropSceneFormImage image = requireImageInForm(imageId, form.getId(), userId);
        if (!Objects.equals(image.getIsUse(), 1))
        {
            log.info("形态图片已非使用中: imageId={}, formId={}", imageId, form.getId());
            return;
        }

        long selectedCount = formImageService.count(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .select(AidRolePropSceneFormImage::getId)
                .eq(AidRolePropSceneFormImage::getFormId, form.getId())
                .eq(AidRolePropSceneFormImage::getUserId, userId)
                .eq(AidRolePropSceneFormImage::getIsUse, 1)
                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
        if (selectedCount <= 1L)
        {
            log.info("取消形态主图失败，必须保留一张: imageId={}, formId={}", imageId, form.getId());
            throw new ServiceException("必须保留一张主图");
        }

        setImageSelection(imageId, form.getId(), userId, 0, DateUtils.getNowDate());
        log.info("形态图片取消使用: imageId={}, formId={}", imageId, form.getId());
    }

    @Override
    @Transactional
    public void saveGeneratedImage(AidRolePropSceneFormImage image, Long userId, boolean sceneSelectedByDefault)
    {
        if (Objects.isNull(image) || Objects.isNull(image.getFormId()) || Objects.isNull(userId))
        {
            log.info("生成图片落库失败，参数缺失: formId={}, userId={}",
                    Objects.nonNull(image) ? image.getFormId() : null, userId);
            throw new ServiceException("参数缺失");
        }

        AidRolePropSceneForm form = lockForm(image.getFormId(), userId);
        AidRolePropScene asset = requireAsset(form, userId);
        validateAssetType(asset.getAssetType(), form.getId());
        validateGeneratedImageRelation(image, form, userId);

        boolean singlePrimary = isSinglePrimaryType(asset.getAssetType());
        boolean selected = singlePrimary || sceneSelectedByDefault;
        Date now = DateUtils.getNowDate();
        if (singlePrimary)
        {
            clearSelectedImages(form.getId(), null, userId, now);
        }

        image.setIsUse(selected ? 1 : 0);
        if (!formImageService.save(image))
        {
            log.error("生成图片落库失败: formId={}, userId={}, assetType={}",
                    form.getId(), userId, asset.getAssetType());
            throw new ServiceException("图片保存失败");
        }
        log.info("生成图片已落库并应用使用规则: imageId={}, formId={}, assetType={}, isUse={}",
                image.getId(), form.getId(), asset.getAssetType(), image.getIsUse());
    }

    private AidRolePropSceneFormImage requireImage(Long imageId, Long userId)
    {
        if (Objects.isNull(imageId) || Objects.isNull(userId))
        {
            log.info("形态图片状态处理失败，参数缺失: imageId={}, userId={}", imageId, userId);
            throw new ServiceException("参数缺失");
        }
        AidRolePropSceneFormImage image = formImageService.getOne(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getFormId,
                                AidRolePropSceneFormImage::getIsUse)
                        .eq(AidRolePropSceneFormImage::getId, imageId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"), false);
        if (Objects.isNull(image))
        {
            log.info("形态图片不存在: imageId={}, userId={}", imageId, userId);
            throw new ServiceException("图片不存在");
        }
        return image;
    }

    private AidRolePropSceneForm lockForm(Long formId, Long userId)
    {
        AidRolePropSceneForm form = formService.getOne(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .select(AidRolePropSceneForm::getId,
                                AidRolePropSceneForm::getAssetId,
                                AidRolePropSceneForm::getProjectId,
                                AidRolePropSceneForm::getEpisodeId,
                                AidRolePropSceneForm::getUserId)
                        .eq(AidRolePropSceneForm::getId, formId)
                        .eq(AidRolePropSceneForm::getUserId, userId)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1 FOR UPDATE"), false);
        if (Objects.isNull(form))
        {
            log.info("形态不存在或无权操作: formId={}, userId={}", formId, userId);
            throw new ServiceException("形态不存在");
        }
        return form;
    }

    private AidRolePropSceneFormImage requireImageInForm(Long imageId, Long formId, Long userId)
    {
        AidRolePropSceneFormImage image = formImageService.getOne(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getIsUse)
                        .eq(AidRolePropSceneFormImage::getId, imageId)
                        .eq(AidRolePropSceneFormImage::getFormId, formId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"), false);
        if (Objects.isNull(image))
        {
            log.info("形态图片状态已变化: imageId={}, formId={}, userId={}", imageId, formId, userId);
            throw new ServiceException("图片不存在");
        }
        return image;
    }

    private AidRolePropScene requireAsset(AidRolePropSceneForm form, Long userId)
    {
        AidRolePropScene asset = assetService.getOne(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getAssetType)
                        .eq(AidRolePropScene::getId, form.getAssetId())
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"), false);
        if (Objects.isNull(asset))
        {
            log.info("形态资产不存在或无权操作: formId={}, assetId={}, userId={}",
                    form.getId(), form.getAssetId(), userId);
            throw new ServiceException("资产不存在");
        }
        return asset;
    }

    private void validateGeneratedImageRelation(AidRolePropSceneFormImage image,
                                                AidRolePropSceneForm form,
                                                Long userId)
    {
        if (!Objects.equals(image.getAssetId(), form.getAssetId())
                || !Objects.equals(image.getProjectId(), form.getProjectId())
                || !Objects.equals(image.getEpisodeId(), form.getEpisodeId())
                || !Objects.equals(image.getUserId(), userId))
        {
            log.error("生成图片归属不一致: formId={}, imageAssetId={}, formAssetId={}, userId={}",
                    form.getId(), image.getAssetId(), form.getAssetId(), userId);
            throw new ServiceException("图片归属异常");
        }
    }

    private boolean isSinglePrimaryType(String assetType)
    {
        return ASSET_TYPE_CHARACTER.equals(assetType) || ASSET_TYPE_PROP.equals(assetType);
    }

    private void validateAssetType(String assetType, Long formId)
    {
        if (!ASSET_TYPE_CHARACTER.equals(assetType)
                && !ASSET_TYPE_SCENE.equals(assetType)
                && !ASSET_TYPE_PROP.equals(assetType))
        {
            log.error("形态资产类型异常: formId={}, assetType={}", formId, assetType);
            throw new ServiceException("资产类型异常");
        }
    }

    private void clearSelectedImages(Long formId, Long excludedImageId, Long userId, Date now)
    {
        LambdaUpdateWrapper<AidRolePropSceneFormImage> update = Wrappers.lambdaUpdate();
        update.eq(AidRolePropSceneFormImage::getFormId, formId);
        update.eq(AidRolePropSceneFormImage::getUserId, userId);
        update.eq(AidRolePropSceneFormImage::getIsUse, 1);
        update.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        if (Objects.nonNull(excludedImageId))
        {
            update.ne(AidRolePropSceneFormImage::getId, excludedImageId);
        }
        update.set(AidRolePropSceneFormImage::getIsUse, 0);
        update.set(AidRolePropSceneFormImage::getUpdateTime, now);
        update.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
        formImageService.update(update);
    }

    private void setImageSelection(Long imageId, Long formId, Long userId, int isUse, Date now)
    {
        LambdaUpdateWrapper<AidRolePropSceneFormImage> update = Wrappers.lambdaUpdate();
        update.eq(AidRolePropSceneFormImage::getId, imageId);
        update.eq(AidRolePropSceneFormImage::getFormId, formId);
        update.eq(AidRolePropSceneFormImage::getUserId, userId);
        update.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        update.set(AidRolePropSceneFormImage::getIsUse, isUse);
        update.set(AidRolePropSceneFormImage::getUpdateTime, now);
        update.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
        if (!formImageService.update(update))
        {
            log.error("形态图片状态更新失败: imageId={}, formId={}, userId={}, isUse={}",
                    imageId, formId, userId, isUse);
            throw new ServiceException("图片状态更新失败");
        }
    }
}
