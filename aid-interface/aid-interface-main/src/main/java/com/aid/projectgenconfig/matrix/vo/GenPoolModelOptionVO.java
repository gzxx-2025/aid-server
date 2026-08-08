package com.aid.projectgenconfig.matrix.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 生成池模型下拉项及其在当前业务场景下的有效图片能力。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class GenPoolModelOptionVO
{
    /** 模型编码 */
    private String value;

    /** 模型展示名称 */
    private String label;

    /** 当前业务场景支持的清晰度选项 */
    private List<String> sizeOptions;

    /** 当前业务场景支持的比例选项 */
    private List<String> aspectRatioOptions;

    /** 当前业务场景默认清晰度 */
    private String defaultSize;

    /** 当前业务场景默认比例 */
    private String defaultAspectRatio;

    /** 当前业务场景是否支持清晰度档位 */
    private Boolean supportsSizePreset;

    /** 当前业务场景是否支持独立比例 */
    private Boolean supportsAspectRatio;
}
