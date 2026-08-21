package com.aid.aid.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 风格分类选项。
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "风格分类选项")
public class StyleCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 稳定分类代码 */
    @Schema(description = "稳定分类代码", example = "three_d")
    private String code;

    /** 分类中文名称 */
    @Schema(description = "分类中文名称", example = "3D")
    private String label;
}
