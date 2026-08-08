package com.aid.upgrade.dto;

import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部署配置分项诊断参数。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeploymentConfigTestDto extends DeploymentConfigSaveDto {

    /** 固定诊断项：config/dns/certificate/https/mysql/redis/rocketmq */
    private List<String> targets;
}
