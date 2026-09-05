package com.aid.orchestration.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型或智能体的受控下线请求。
 */
@Data
public class RetireResourceRequest
{
    /** 可选替代编码；为空表示清理活动引用并回退到上层默认配置。 */
    @Size(max = 100, message = "替代编码长度不能超过100")
    private String replacementCode;

    /** 调用方必须显式确认影响预览，避免误触发跨配置变更。 */
    @AssertTrue(message = "请先确认影响范围")
    private Boolean confirmed;
}
