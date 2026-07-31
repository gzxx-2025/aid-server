package com.aid.storyboard.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 分镜图参考图 VO：某张最终分镜图实际引用参考图的快照，取自出图时落库的引用清单，按占位编号升序。
 */
@Data
@Builder
public class StoryboardRefImageVO {

    /** 占位编号 N（对应 prompt 内 {@code @图片N}）。 */
    private Integer n;

    /** 占位名（= {@code aid_role_prop_scene_form_image.name}）。 */
    private String name;

    /** 资产类型：character(人物)/scene(场景)/prop(道具)；外部图或查不到主资产时为 null。 */
    private String assetKind;

    /** 主资产名（{@code aid_role_prop_scene.name}），用于前端展示「引用了哪个角色/场景/道具」；可空。 */
    private String assetName;

    /**
     * 参考图完整 URL（出图时已拼好域名后落库，此处原样返回，不加 {@code @MediaUrl}）；DESCRIPTION 类型无图时为 null。
     */
    private String url;

    /** 分型：REFERENCE(有图，进厂商参考图数组) / DESCRIPTION(无图，仅文字描述)。 */
    private String type;
}
