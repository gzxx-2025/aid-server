package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 部署配置分项诊断结果。
 *
 * @author 视觉AID
 */
@Data
public class DeploymentCheckVo {

    /** PASS/FAIL/SKIPPED */
    private String status;

    /** 不包含账号凭证的诊断摘要 */
    private String message;

    /** 可操作的修复建议 */
    private String suggestion;
}
