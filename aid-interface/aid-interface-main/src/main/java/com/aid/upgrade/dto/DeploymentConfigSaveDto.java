package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 部署运行配置保存参数。字段为空表示保持原值，密钥不会从查询接口回显。
 *
 * @author 视觉AID
 */
@Data
public class DeploymentConfigSaveDto {

    /** 配置文件路径；默认路径或升级器允许目录内的 .env/.conf */
    private String configPath;

    /** 用户端对外端口 */
    private String httpPort;
    /** 后台管理端对外端口 */
    private String adminPort;
    /** 后端服务端口 */
    private String backendPort;
    /** Docker 数据根目录，已部署后不允许迁移 */
    private String dataRoot;
    /** Docker 内置 MySQL root 密码，留空保持原值 */
    private String mysqlRootPassword;
    /** Docker 内置 MySQL 宿主机端口 */
    private String mysqlPort;
    /** 手动部署数据库主机 */
    private String dbHost;
    /** 手动部署数据库端口 */
    private String dbPort;
    /** 业务数据库名称 */
    private String dbName;
    /** 业务数据库账号 */
    private String dbUsername;
    /** 业务数据库密码，留空保持原值 */
    private String dbPassword;
    /** Redis 主机或容器服务名 */
    private String redisHost;
    /** Redis 端口 */
    private String redisPort;
    /** Redis 密码，留空保持原值 */
    private String redisPassword;
    /** JWT 密钥，留空保持原值 */
    private String tokenSecret;
    /** 后端 JVM 启动参数 */
    private String javaOpts;
    /** Docker Compose 可选组件列表 */
    private String composeProfiles;
    /** 是否启用 RocketMQ */
    private String rocketmqEnabled;
    /** RocketMQ NameServer 地址 */
    private String rocketmqNameserver;
    /** MySQL InnoDB 缓冲池大小 */
    private String mysqlBufferPool;
    /** MySQL 最大连接数 */
    private String mysqlMaxConnections;
    /** Redis 最大内存 */
    private String redisMaxmemory;
    /** Redis 内存淘汰策略 */
    private String redisMaxmemoryPolicy;
    /** Web 端 Node.js 启动参数 */
    private String webNodeOptions;
    /** RocketMQ NameServer JVM 参数 */
    private String mqNamesrvJavaOpts;
    /** RocketMQ Broker JVM 参数 */
    private String mqBrokerJavaOpts;
}
