package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 系统版本回退参数
 *
 * @author 视觉AID
 */
@Data
public class RollbackRequestDto {

    /** 目标版本号，必须来自更新清单的可回退版本列表 */
    private String targetVersion;
}
