package com.aid.common.error.rule;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidErrorLog;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.aid.mapper.AidErrorLogMapper;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 未识别错误样本转规则服务。
 *
 * <p>负责生成规则草稿，并在同一事务中创建规则、回写样本命中结果，
 * 避免规则已经创建但样本仍长期显示为未识别。</p>
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorLogRuleConversionService {

    private static final String GLOBAL_PROVIDER_PLACEHOLDER = "_global";
    private static final int DRAFT_PATTERN_MAX_CHARS = 80;

    private final AidErrorLogMapper errorLogMapper;
    private final ErrorRuleService errorRuleService;
    private final ErrorRuleEngine errorRuleEngine;

    /**
     * 根据未识别样本生成可编辑的规则草稿。
     *
     * @param errorLogId 错误样本 ID
     * @return 规则草稿
     */
    public AidProviderErrorRule buildDraft(Long errorLogId) {
        AidErrorLog sample = getSample(errorLogId, false);
        ensureUnmatched(sample);

        String providerCode = normalizeProviderCode(sample.getProviderCode());
        AidProviderErrorRule draft = new AidProviderErrorRule();
        draft.setProviderCode(providerCode);
        draft.setModelCode(StrUtil.isBlank(sample.getModelCode()) ? null : sample.getModelCode());
        draft.setRuleName(StrUtil.isBlank(providerCode) ? "全局错误规则" : providerCode + " 错误规则");
        draft.setMatchType(ErrorRuleEngine.MATCH_KEYWORD);
        draft.setMatchPattern(buildDraftPattern(sample.getRawMessage()));
        draft.setCaseSensitive(0);
        // 错误码必须由管理员确认，不能继续沿用未识别时的兜底错误码。
        draft.setErrorCode(null);
        draft.setPriority(100);
        draft.setEnabled(1);
        draft.setIsBuiltin(0);
        draft.setRemark("来源错误样本 #" + sample.getId());
        return draft;
    }

    /**
     * 创建规则并把来源样本标记为已处理。
     *
     * @param errorLogId 错误样本 ID
     * @param rule       管理员确认后的规则
     * @param operator   操作人
     * @return 新规则 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long convert(Long errorLogId, AidProviderErrorRule rule, String operator) {
        AidErrorLog sample = getSample(errorLogId, true);
        ensureUnmatched(sample);
        if (Objects.isNull(rule)) {
            log.info("[ErrorRuleConvert] 规则参数缺失, errorLogId={}", errorLogId);
            throw new ServiceException("规则参数缺失");
        }

        // 全局样本在日志表用 _global 去重，在规则表必须恢复为 NULL 才代表全局规则。
        rule.setProviderCode(normalizeProviderCode(rule.getProviderCode()));
        if (StrUtil.isBlank(rule.getModelCode())) {
            rule.setModelCode(null);
        }
        if (Objects.isNull(rule.getEnabled())) {
            rule.setEnabled(1);
        }
        errorRuleService.validate(rule);
        if (Objects.equals(rule.getEnabled(), 0)) {
            log.info("[ErrorRuleConvert] 转换规则未启用, errorLogId={}", errorLogId);
            throw new ServiceException("请先启用规则");
        }
        ensureScopeMatches(sample, rule);

        int httpStatus = Objects.isNull(sample.getHttpStatus()) ? -1 : sample.getHttpStatus();
        if (!errorRuleEngine.matches(rule, httpStatus, sample.getRawMessage())) {
            log.info("[ErrorRuleConvert] 新规则未命中来源样本, errorLogId={}, matchType={}",
                    errorLogId, rule.getMatchType());
            throw new ServiceException("规则未命中样本");
        }

        Long ruleId = errorRuleService.add(rule, operator);
        AidErrorLog update = new AidErrorLog();
        update.setId(sample.getId());
        update.setMatchedRuleId(ruleId);
        update.setMatchedErrorCode(rule.getErrorCode());
        if (errorLogMapper.updateById(update) != 1) {
            log.error("[ErrorRuleConvert] 样本状态更新失败, errorLogId={}, ruleId={}", errorLogId, ruleId);
            throw new ServiceException("样本更新失败");
        }
        return ruleId;
    }

    /**
     * 精简查询字段，新增样本字段时不得默认带入本转换链路。
     */
    private AidErrorLog getSample(Long errorLogId, boolean forUpdate) {
        if (Objects.isNull(errorLogId)) {
            log.info("[ErrorRuleConvert] 样本ID缺失");
            throw new ServiceException("样本ID缺失");
        }
        LambdaQueryWrapper<AidErrorLog> query = new LambdaQueryWrapper<AidErrorLog>()
                .select(AidErrorLog::getId,
                        AidErrorLog::getProviderCode,
                        AidErrorLog::getModelCode,
                        AidErrorLog::getHttpStatus,
                        AidErrorLog::getRawMessage,
                        AidErrorLog::getMatchedRuleId)
                .eq(AidErrorLog::getId, errorLogId);
        if (forUpdate) {
            query.last("FOR UPDATE");
        }
        AidErrorLog sample = errorLogMapper.selectOne(query);
        if (Objects.isNull(sample)) {
            log.info("[ErrorRuleConvert] 样本不存在, errorLogId={}", errorLogId);
            throw new ServiceException("样本不存在");
        }
        return sample;
    }

    private void ensureUnmatched(AidErrorLog sample) {
        if (Objects.nonNull(sample.getMatchedRuleId())) {
            log.info("[ErrorRuleConvert] 样本已处理, errorLogId={}, ruleId={}",
                    sample.getId(), sample.getMatchedRuleId());
            throw new ServiceException("样本已处理");
        }
    }

    private void ensureScopeMatches(AidErrorLog sample, AidProviderErrorRule rule) {
        String sampleProviderCode = normalizeProviderCode(sample.getProviderCode());
        boolean providerMatches = Objects.isNull(rule.getProviderCode())
                || Objects.equals(rule.getProviderCode(), sampleProviderCode);
        boolean modelMatches = Objects.isNull(rule.getModelCode())
                || Objects.equals(rule.getModelCode(), sample.getModelCode());
        if (!providerMatches || !modelMatches) {
            log.info("[ErrorRuleConvert] 规则范围未覆盖来源样本, errorLogId={}, providerCode={}, modelCode={}",
                    sample.getId(), rule.getProviderCode(), rule.getModelCode());
            throw new ServiceException("规则范围不匹配");
        }
    }

    private String normalizeProviderCode(String providerCode) {
        return StrUtil.isBlank(providerCode) || GLOBAL_PROVIDER_PLACEHOLDER.equals(providerCode)
                ? null
                : providerCode;
    }

    private String buildDraftPattern(String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return "";
        }
        String firstLine = rawMessage.strip().lines()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
        return firstLine.length() > DRAFT_PATTERN_MAX_CHARS
                ? firstLine.substring(0, DRAFT_PATTERN_MAX_CHARS)
                : firstLine;
    }
}
