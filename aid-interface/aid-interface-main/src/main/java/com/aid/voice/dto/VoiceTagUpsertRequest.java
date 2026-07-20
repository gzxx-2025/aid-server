package com.aid.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 音色标签字典新增 / 更新请求
 *
 * @author 视觉AID
 */
@Data
public class VoiceTagUpsertRequest
{
    /** 主键（仅更新时传入） */
    private Long id;

    /** 标签类型 */
    @NotBlank(message = "类型不能为空")
    private String tagType;

    /** 标签编码 */
    @NotBlank(message = "编码不能为空")
    @Size(max = 64, message = "编码过长")
    private String tagCode;

    /** 标签名称 */
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称过长")
    private String tagName;

    /** 排序 */
    private Integer sortOrder;

    /** 状态：0启用 1停用 */
    private String status;

    /** 备注 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
