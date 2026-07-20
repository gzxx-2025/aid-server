package com.aid.aid.domain.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 统一字典查询请求对象
 * 用于查询系统中的提示词库数据和枚举数据
 *
 * @author 视觉AID
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PromptLibQueryDTO {

    /**
     * 数据分类（可选）
     * 取值为 aid_prompt_lib 表的 prompt_type（如 style/composition/shot_size 等），
     * 或 com.aid.enums 包下的枚举类型名（以 Enum 结尾）。
     * 为空或 "all" 时返回全部提示词库数据与枚举数据。
     */
    private String category;

    /**
     * 是否只查询官方预设（可选，true=仅查询官方预设，false/null=查询所有）
     * 仅在查询提示词库数据时有效
     */
    private Boolean officialOnly;

    /**
     * 状态筛选（可选，0正常 1停用，null=查询所有）
     * 仅在查询提示词库数据时有效
     */
    private String status;
}
