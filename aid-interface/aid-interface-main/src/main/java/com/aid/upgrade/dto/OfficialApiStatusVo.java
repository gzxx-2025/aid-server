package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 官方API地址同步状态
 *
 * @author 视觉AID
 */
@Data
public class OfficialApiStatusVo {

    /** 更新清单里的官方API地址 */
    private String remoteBaseUrl;

    /** 本地当前配置的官方API地址 */
    private String localBaseUrl;

    /** 官方API官网地址（注册开通、获取Key入口，来自更新清单） */
    private String websiteUrl;

    /** 远端地址与本地是否不一致（不一致才提醒用户获取） */
    private boolean changed;
}
