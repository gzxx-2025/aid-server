package com.aid.rps.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑形态图片实例请求 DTO，仅更新单行图片，字段传入即更新、不传保留原值。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageUpdateRequest
{
    /** 图片实例ID */
    @NotNull(message = "图片ID不能为空")
    private Long imageId;

    /** 图片名称 */
    @Size(max = 120, message = "图片名称不能超过120个字符")
    private String name;

    /** 图片URL（入参剥离域名入库） */
    @MediaUrl
    private String imageUrl;

    /** 提示词下标（0-based） */
    private Integer descriptionIndex;

    /** 提示词快照（生图时使用的最终提示词文本） */
    private String promptSnapshot;

    /** 参考图列表（序列化为 JSON 写入 reference_images 列，元素不自动剥离域名） */
    private List<String> referenceImages;
}
