package com.aid.config.test.tester;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.aid.common.config.AidAppConfig;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 对象存储（OSS / COS / 本地）配置连通性测试。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class OssConnectivityTester implements ConfigConnectivityTester {

    /**
     * 本地探活临时文件大小（1KB）。
     */
    private static final int PROBE_FILE_SIZE = 1024;

    @Override
    public String testKey() {
        return "oss";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String uploadMode = TesterPayloads.str(payload, "uploadMode", "oss").toLowerCase();

        try {
            return switch (uploadMode) {
                case "cos" -> testCos(payload);
                case "local" -> testLocal();
                default -> testOss(payload);
            };
        } catch (Exception e) {
            // 兜底：任何未预期异常都转为友好失败
            log.error("OSS连通性测试未知异常: uploadMode={}, err={}", uploadMode, e.getMessage(), e);
            return failWithDetails("对象存储配置测试失败",
                    "uploadMode=" + uploadMode + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        }
    }

    /**
     * 阿里云 OSS 探活：doesBucketExist。
     *
     * @param payload 临时配置
     * @return 测试结果
     */
    private ConfigTestResult testOss(Map<String, Object> payload) {
        String endpoint = TesterPayloads.str(payload, "endpoint");
        String accessKeyId = TesterPayloads.str(payload, "accessKeyId");
        String accessKeySecret = TesterPayloads.str(payload, "accessKeySecret");
        String bucketName = TesterPayloads.str(payload, "bucketName");

        if (StrUtil.hasBlank(endpoint, accessKeyId, accessKeySecret, bucketName)) {
            return ConfigTestResult.fail("请填写完整OSS配置");
        }

        String details = StrUtil.format("uploadMode=oss, endpoint={}, bucketName={}", endpoint, bucketName);
        OSS ossClient = null;
        try {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            // 只读探活：判断 Bucket 是否存在（鉴权失败会抛异常）
            boolean exists = ossClient.doesBucketExist(bucketName);
            if (!exists) {
                log.warn("OSS连通性测试: Bucket不存在, bucketName={}", bucketName);
                return failWithDetails("Bucket不存在或密钥无权限", details + "; exists=false");
            }
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "oss");
            result.setDetails(details + "; exists=true");
            return result;
        } catch (Exception e) {
            log.error("OSS连通性测试失败: bucketName={}, err={}", bucketName, e.getMessage());
            return failWithDetails("Bucket不存在或密钥无权限",
                    details + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        } finally {
            // 释放连接池
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /**
     * 腾讯云 COS 探活：getBucketLocation。
     *
     * @param payload 临时配置
     * @return 测试结果
     */
    private ConfigTestResult testCos(Map<String, Object> payload) {
        String cosRegion = TesterPayloads.str(payload, "cosRegion");
        String cosSecretId = TesterPayloads.str(payload, "cosSecretId");
        String cosSecretKey = TesterPayloads.str(payload, "cosSecretKey");
        String cosBucketName = TesterPayloads.str(payload, "cosBucketName");

        if (StrUtil.hasBlank(cosRegion, cosSecretId, cosSecretKey, cosBucketName)) {
            return ConfigTestResult.fail("请填写完整COS配置");
        }

        String details = StrUtil.format("uploadMode=cos, region={}, bucketName={}", cosRegion, cosBucketName);
        COSClient cosClient = null;
        try {
            COSCredentials cred = new BasicCOSCredentials(cosSecretId, cosSecretKey);
            ClientConfig clientConfig = new ClientConfig(new Region(cosRegion));
            clientConfig.setHttpProtocol(HttpProtocol.https);
            cosClient = new COSClient(cred, clientConfig);
            // 只读探活：查询 Bucket 所在地域（鉴权/权限失败会抛异常）
            String location = cosClient.getBucketLocation(cosBucketName);
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "cos");
            result.setDetails(details + "; location=" + StrUtil.trimToEmpty(location));
            return result;
        } catch (Exception e) {
            log.error("COS连通性测试失败: bucketName={}, err={}", cosBucketName, e.getMessage());
            return failWithDetails("Bucket不存在或密钥无权限",
                    details + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        } finally {
            // 释放连接池
            if (cosClient != null) {
                cosClient.shutdown();
            }
        }
    }

    /**
     * 本地存储探活：在上传目录写入 1KB 临时文件再删除。
     *
     * @return 测试结果
     */
    private ConfigTestResult testLocal() {
        String uploadPath = AidAppConfig.getUploadPath();
        String details = "uploadMode=local, uploadPath=" + uploadPath;
        File probeFile = null;
        try {
            File folder = new File(uploadPath);
            if (!folder.exists() && !folder.mkdirs()) {
                log.warn("本地存储连通性测试: 目录创建失败, path={}", uploadPath);
                return failWithDetails("本地目录不可写", details + "; mkdirs=false");
            }
            // 写入 1KB 临时文件，验证目录可写
            probeFile = new File(folder, "__conntest_" + System.currentTimeMillis() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(probeFile)) {
                fos.write(new byte[PROBE_FILE_SIZE]);
            }
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "local");
            result.setDetails(details + "; write=ok");
            return result;
        } catch (Exception e) {
            log.error("本地存储连通性测试失败: path={}, err={}", uploadPath, e.getMessage(), e);
            return failWithDetails("本地目录不可写",
                    details + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        } finally {
            // 清理临时文件
            deleteQuietly(probeFile);
        }
    }

    /**
     * 安静删除临时探活文件。
     *
     * @param file 待删除文件，可为 null
     */
    private void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            log.warn("本地存储连通性测试: 临时文件删除失败, path={}", file.getAbsolutePath());
        }
    }

    /**
     * 构造带调试明细的失败结果。
     *
     * @param message 友好文案
     * @param details 调试明细（无密钥）
     * @return 失败结果
     */
    private ConfigTestResult failWithDetails(String message, String details) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(details);
        return result;
    }
}
