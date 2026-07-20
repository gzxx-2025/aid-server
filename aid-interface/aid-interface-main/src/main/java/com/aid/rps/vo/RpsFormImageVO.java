package com.aid.rps.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 形态图片实例 VO（aid_role_prop_scene_form_image）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class RpsFormImageVO
{
    /** 图片实例ID */
    private Long id;

    /** 图片名称（默认 资产名_形态名_序号） */
    private String name;

    /** 图片URL（出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 来源类型：ai_auto / ai_builder / ai_manual / upload / official / migrate */
    private String sourceType;

    /** 提示词下标（0-based）；上传/官方/迁移图为 null */
    private Integer descriptionIndex;

    /** 是否使用中（0/1） */
    private Integer isUse;

    /** 图片状态：pending / processing / completed / failed */
    private String imageStatus;

    /** 本次生图使用的参考图列表（已反序列化） */
    private List<String> referenceImages;
}
