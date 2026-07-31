package com.aid.common.error.rule;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.mapper.AidErrorLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 错误样本日志服务。
 * 同步 UPSERT，但写入异常会被吞掉只记日志，不影响主调用链。
 * 项目尚未启用 {@code @EnableAsync}，这里不做异步化以免行为退化为同步阻塞且异常静默。
 * 单条 UPSERT 在主链路 ms 级，开销可接受。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AidErrorLogService {

    /** 原始消息截断阈值（字符近似 = 字节，对 UTF-8 实际字节略高，由 DB 列上限兜底） */
    private static final int RAW_MESSAGE_MAX_CHARS = 2048;

    /** providerCode 为空时的占位（避免唯一索引按 NULL 不去重） */
    private static final String GLOBAL_PROVIDER_PLACEHOLDER = "_global";

    private final AidErrorLogMapper errorLogMapper;

    /**
     * 命中规则时记录样本（含规则 ID）。
     */
    public void recordHit(String providerCode, String modelCode, String taskId,
                          int httpStatus, String rawMessage,
                          Long ruleId, String matchedErrorCode) {
        record(providerCode, modelCode, taskId, httpStatus, rawMessage, ruleId, matchedErrorCode);
    }

    /**
     * 未命中任何规则时记录样本（matchedRuleId = null）。
     */
    public void recordMiss(String providerCode, String modelCode, String taskId,
                           int httpStatus, String rawMessage,
                           String fallbackErrorCode) {
        record(providerCode, modelCode, taskId, httpStatus, rawMessage, null, fallbackErrorCode);
    }

    private void record(String providerCode, String modelCode, String taskId,
                        int httpStatus, String rawMessage,
                        Long ruleId, String matchedErrorCode) {
        if (StringUtils.isBlank(rawMessage)) {
            return;
        }
        try {
            String truncated = rawMessage.length() > RAW_MESSAGE_MAX_CHARS
                    ? rawMessage.substring(0, RAW_MESSAGE_MAX_CHARS)
                    : rawMessage;
            String hash = SecureUtil.md5(normalizeForHash(truncated));
            errorLogMapper.upsertSample(
                    StringUtils.defaultIfBlank(providerCode, GLOBAL_PROVIDER_PLACEHOLDER),
                    modelCode,
                    httpStatus <= 0 ? null : httpStatus,
                    truncated,
                    ruleId,
                    matchedErrorCode,
                    hash,
                    taskId);
        } catch (Exception e) {
            // 记录样本失败不影响主链路
            log.warn("[AidErrorLog] 记录样本失败: {}", e.getMessage());
        }
    }

    /**
     * 归一化用于 hash：
     *
     *   - 统一小写
     *   - 合并连续空白
     *   - 去除明显的可变片段（taskId / requestId / UUID 类长串），避免同语义错误生成多条样本
     *
     */
    private String normalizeForHash(String raw) {
        String s = raw.toLowerCase();
        s = s.replaceAll("[a-f0-9]{8,}", "*");
        s = s.replaceAll("\\d{4}-\\d{2}-\\d{2}[t\\s]\\d{2}:\\d{2}:\\d{2}[\\d:.+\\-z]*", "*");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
