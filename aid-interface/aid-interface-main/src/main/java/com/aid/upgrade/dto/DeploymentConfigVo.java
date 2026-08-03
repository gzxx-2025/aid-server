package com.aid.upgrade.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 部署运行配置展示对象；values 已由升级器移除全部密钥原文。
 *
 * @author 视觉AID
 */
@Data
public class DeploymentConfigVo {

    /** 部署方式：docker/systemd */
    private String mode;

    /** 当前实际生效的配置文件路径 */
    private String configPath;

    /** 当前部署方式的默认配置文件路径 */
    private String defaultConfigPath;

    /** 允许管理员自定义配置文件的目录 */
    private String allowedConfigRoot;

    /** 非密钥配置项 */
    private Map<String, String> values;

    /** 已配置但不返回原文的密钥项名称 */
    private List<String> configuredSecrets;
}
