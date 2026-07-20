package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 资产形态批量生成请求，支持一次传多个 assetIds、后端逐个建独立任务。
 *
 * @author 视觉AID
 */
@Data
public class FormGenerateRequest
{
    /** 资产ID列表（至少 1 个，支持多个批量提交） */
    @NotEmpty(message = "资产ID不能为空")
    private List<Long> assetIds;

    /** 智能体编码（character / scene / prop 三类均必填，按资产类型校验 bizCategoryCode） */
    private String agentCode;

    /** 可选：用户指定的模型编码，不传用智能体默认模型，传了必须为文本模型 */
    private String modelCode;
}
