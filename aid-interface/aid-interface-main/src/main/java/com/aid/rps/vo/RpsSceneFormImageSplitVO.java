package com.aid.rps.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景拆分四宫格响应 VO。
 * 返回 {@code POST /api/user/asset/rps/form-image/scene/split} 的结果：
 * 源图主键、4 张子图详情列表（顺序与请求 sceneImages 一致：主视→反打→左立面→右立面），
 * 子图详情 {@link RpsFormImageDetailVO} 中的 {@code imageUrl} 出参时已自动拼上 CDN/local 域名。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpsSceneFormImageSplitVO
{
    /** 拆分源图 ID（aid_role_prop_scene_form_image.id） */
    private Long sourceImageId;

    /** 主资产 ID（aid_role_prop_scene.id） */
    private Long assetId;

    /** 形态 ID（aid_role_prop_scene_form.id） */
    private Long formId;

    /**
     * 4 张子图详情，顺序严格对应请求入参：。
     */
    private List<RpsFormImageDetailVO> children;
}
