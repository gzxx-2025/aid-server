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
    /** 业务数据库主机；Docker 外部 MySQL 模式填写容器可访问地址 */
    private String dbHost;
    /** 业务数据库端口 */
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
    /** Redis 6+ ACL 用户名，可留空 */
    private String redisUsername;
    /** Redis 密码，留空保持原值 */
    private String redisPassword;
    /** 是否显式清空 Redis 密码 */
    private Boolean clearRedisPassword;
    /** Redis 数据库索引 */
    private String redisDatabase;
    /** JWT 密钥，留空保持原值 */
    private String tokenSecret;
    /** 后端 JVM 启动参数 */
    private String javaOpts;
    /** 依赖处理模式；auto 自动安装或拉取，manual 仅提示 */
    private String dependencyInstallMode;
    /** Docker Compose 可选组件列表；包含 mysql 表示使用内置 MySQL */
    private String composeProfiles;
    /** 是否启用 RocketMQ */
    private String rocketmqEnabled;
    /** RocketMQ NameServer 地址 */
    private String rocketmqNameserver;
    /** 内置 RocketMQ Broker 刷盘模式 */
    private String rocketmqFlushDiskType;
    /** RocketMQ ACL AccessKey，留空保持原值 */
    private String rocketmqAccessKey;
    /** RocketMQ ACL SecretKey，留空保持原值 */
    private String rocketmqSecretKey;
    /** 是否显式清空 RocketMQ ACL 凭证 */
    private Boolean clearRocketmqCredentials;
    /** Docker HTTPS 对外端口 */
    private String httpsPort;
    /** 手动部署是否启用 HTTPS；Docker 由 Compose Profiles 控制 */
    private String httpsEnabled;
    /** Docker HTTPS 用户端域名 */
    private String httpsPublicDomain;
    /** Docker HTTPS 管理端域名 */
    private String httpsAdminDomain;
    /** Docker HTTPS 完整证书路径 */
    private String httpsCertPath;
    /** Docker HTTPS 私钥路径 */
    private String httpsKeyPath;
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
