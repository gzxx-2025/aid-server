package com.aid.common.aid.oss.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.aid.common.aid.oss.properties.OssProperties;

import cn.hutool.core.util.StrUtil;

/**
 * OSS Spring配置类
 *
 * @author 视觉AID
 */
@Configuration
@ComponentScan(basePackages = "com.aid.common.aid.oss")
public class OssSpringConfig
{
    /**
     * 创建OSS客户端Bean（在OSS配置完整时创建，否则返回null）
     *
     * @param configManager OSS配置管理器
     * @return OSS客户端实例，配置不完整时返回null
     */
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssConfigManager configManager)
    {
        OssProperties properties = configManager.getOssProperties();

        // 必要配置不为空时才创建OSS客户端
        if (StrUtil.isNotBlank(properties.getEndpoint())
                && StrUtil.isNotBlank(properties.getAccessKeyId())
                && StrUtil.isNotBlank(properties.getAccessKeySecret()))
        {
            return new OSSClientBuilder().build(
                    properties.getEndpoint(),
                    properties.getAccessKeyId(),
                    properties.getAccessKeySecret()
            );
        }

        // 配置不完整返回null，OssTemplate中会处理
        return null;
    }

    /**
     * 创建腾讯云COS客户端Bean（在COS配置完整时创建，否则返回null）。
     * COSClient 是线程安全的，内部维持连接池，全局保持单例即可。
     *
     * @param configManager OSS配置管理器
     * @return COS客户端实例，配置不完整时返回null
     */
    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient(OssConfigManager configManager)
    {
        OssProperties properties = configManager.getOssProperties();

        // 必要配置不为空时才创建COS客户端
        if (StrUtil.isNotBlank(properties.getCosRegion())
                && StrUtil.isNotBlank(properties.getCosSecretId())
                && StrUtil.isNotBlank(properties.getCosSecretKey()))
        {
            COSCredentials cred = new BasicCOSCredentials(
                    properties.getCosSecretId(),
                    properties.getCosSecretKey());
            ClientConfig clientConfig = new ClientConfig(new Region(properties.getCosRegion()));
            // 推荐使用 https 协议（5.6.54+ 默认已是 https，这里显式设置以兼容旧版本）
            clientConfig.setHttpProtocol(HttpProtocol.https);
            return new COSClient(cred, clientConfig);
        }

        // 配置不完整返回null，OssTemplate中会处理
        return null;
    }
}
