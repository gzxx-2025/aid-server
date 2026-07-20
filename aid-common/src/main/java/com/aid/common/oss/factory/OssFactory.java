package com.aid.common.oss.factory;

import cn.hutool.core.util.IdUtil;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.spring.SpringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件上传 Factory（与源系统调用面一致：OssFactory.instance().uploadSuffix(...)）
 * 已改造为按 aid_config[oss.uploadMode] 分发：
 *   - 存在 Spring 环境时：委托给 {@link OssTemplate#uploadBytes(byte[], String, String)}
 *     统一按 uploadMode 分发到本地或阿里云 OSS，返回「相对路径」（DB 只存路径，读取层拼域名）。
 *   - Spring 未就绪时（如早期启动链路）：兜底写本地 /profile/media/，保证不崩。
 * 新代码请直接使用 {@link OssTemplate}，此类保留仅为兼容旧调用面。
 */
public final class OssFactory {

    private static final MediaOssClient CLIENT = new MediaOssClient();

    private OssFactory() {
    }

    /**
     * 获取默认实例（与源系统签名一致）
     */
    public static MediaOssClient instance() {
        return CLIENT;
    }

    /**
     * 上传适配器：优先走 OssTemplate 按 uploadMode 分发，失败兜底到本地
     */
    public static final class MediaOssClient {

        /**
         * 按后缀与内容类型写入 OSS 或本地，并返回相对访问路径（带前导 /）。
         * 分发策略：优先从 Spring 获取 OssTemplate，按 uploadMode 走 local / oss；
         * 若 Spring 未就绪则兜底写本地 /profile/media/。
         */
        public UploadResult uploadSuffix(byte[] bytes, String suffix, String contentType) throws IOException {
            if (bytes == null || bytes.length == 0) {
                throw new IOException("empty upload bytes");
            }
            String safeSuffix = StringUtils.isNotBlank(suffix) ? suffix : ".bin";
            if (!safeSuffix.startsWith(".")) {
                safeSuffix = "." + safeSuffix;
            }
            String fileName = IdUtil.fastSimpleUUID() + safeSuffix;

            // 优先走统一分发（按 uploadMode）
            try {
                OssTemplate ossTemplate = SpringUtils.getBean(OssTemplate.class);
                if (ossTemplate != null) {
                    String url = ossTemplate.uploadBytes(bytes, fileName, null);
                    return UploadResult.builder().url(url).filename(fileName).build();
                }
            } catch (Exception ignore) {
                // Spring 未就绪或 OssTemplate 未注册，走本地兜底
            }

            // 兜底：写入本地 profile/media 目录
            Path dir = Paths.get(AidAppConfig.getProfile(), "media");
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.write(file, bytes);
            String url = Constants.RESOURCE_PREFIX + "/media/" + fileName;
            return UploadResult.builder().url(url).filename(fileName).build();
        }
    }
}
