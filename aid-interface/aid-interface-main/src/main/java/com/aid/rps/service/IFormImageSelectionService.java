package com.aid.rps.service;

import com.aid.aid.domain.AidRolePropSceneFormImage;

/**
 * 形态图片使用状态规则服务。
 */
public interface IFormImageSelectionService
{
    /**
     * 将指定图片设为使用中。
     * 角色、道具同一形态只保留当前图片；场景保留多选。
     */
    void selectImage(Long imageId, Long userId);

    /**
     * 取消指定图片的使用状态，同一形态必须至少保留一张主图。
     */
    void unselectImage(Long imageId, Long userId);

    /**
     * 保存生成图片并应用默认使用规则。
     * 角色、道具总是由新图接管主图；场景使用调用方传入的原有默认值。
     */
    void saveGeneratedImage(AidRolePropSceneFormImage image, Long userId, boolean sceneSelectedByDefault);
}
