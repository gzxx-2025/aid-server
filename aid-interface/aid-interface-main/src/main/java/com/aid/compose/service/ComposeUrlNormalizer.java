package com.aid.compose.service;

/**
 * 合成素材 URL 规范化 + 可用性校验。
 * 自家 COS 资源重拼为规范 URL，外部链接直用；统一过结构校验 + 白名单 + HEAD 可达性探测。
 * 保证幂等：{@code normalize(normalize(u)) == normalize(u)}。
 *
 * @author 视觉AID
 */
public interface ComposeUrlNormalizer {

    /**
     * 规范化单个素材 URL 并做可用性校验。
     *
     * @param rawUrl        传入 URL
     * @param materialLabel 素材类型标签（如「视频素材」「配音素材」「背景音乐」），用于拼接精确错误文案；空则按「素材」处理
     * @return 喂给 MPS FileInfos 的最终 URL（Type=URL）
     */
    String normalizeAndValidate(String rawUrl, String materialLabel);
}
