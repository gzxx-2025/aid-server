package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分镜机位生图请求 DTO（单机位 / 九宫格统一入口）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardMultiViewGridImageGenerateRequest
{
    /** 分镜 ID：必须存在、未删除、归属当前用户 */
    @NotNull(message = "分镜不存在")
    private Long storyboardId;

    /** 参考图 URL（必传 1 张）：须为本站资源（相对路径或本站域名 URL），发起前经远程合法性校验，非法则拒绝、不进任务与计费。 */
    @NotBlank(message = "参考图缺失")
    private String imageUrl;

    /** 机位提示词列表：长度只能为 1 或 9，其他数量一律拒绝，每个元素非空。 */
    @NotEmpty(message = "机位必须1或9个")
    private List<@NotBlank(message = "机位不能空") String> angles;

    /**
     * 图片模型编码：须存在 / 启用 / {@code model_type=image}；单机位须在 {@code image_multi_view} 池内，
     * 九宫格须在 {@code image_multi_grid} 池内。
     */
    @NotBlank(message = "模型不能空")
    private String modelCode;

    /**
     * 图片比例（如 1:1 / 16:9，可选，缺省 1:1）。注入提示词 {@code {aspect_ratio}} 占位，
     * 不做模型 capability 严格匹配，由各厂商参数装配器决定是否落底层参数。
     */
    private String aspectRatio;
}
