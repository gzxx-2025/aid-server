package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 在线升级所使用的服务器资源快照
 *
 * @author 视觉AID
 */
@Data
public class UpgradeHostResourceVo {

    /** 操作系统可用逻辑处理器数量 */
    private Integer cpuCores;

    /** 操作系统可见总内存，单位字节 */
    private Long totalMemoryBytes;

    /** 触发高风险提醒的CPU核数上限 */
    private Integer warningCpuCores;

    /** 触发高风险提醒的内存上限，单位字节 */
    private Long warningMemoryBytes;

    /** 是否完整检测到CPU与内存 */
    private boolean detected;

    /** 是否属于在线升级高风险配置 */
    private boolean onlineUpgradeRisk;
}
