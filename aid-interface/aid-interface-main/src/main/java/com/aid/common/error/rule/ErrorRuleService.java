package com.aid.common.error.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.aid.mapper.AidProviderErrorRuleMapper;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 错误规则增删改查 Service。
 * 所有写操作完成后会同步触发 {@link ErrorRuleCache#rebuild()}，
 * 保证内存 + 本地文件与 DB 一致。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorRuleService {

    private static final String DELETE_BUILTIN_TIP = "内置规则不可删除";
    private static final String RULE_NOT_FOUND = "规则不存在";
    private static final Set<String> VALID_MATCH_TYPES = Set.of(
            ErrorRuleEngine.MATCH_HTTP_STATUS,
            ErrorRuleEngine.MATCH_CODE,
            ErrorRuleEngine.MATCH_KEYWORD,
            ErrorRuleEngine.MATCH_REGEX,
            ErrorRuleEngine.MATCH_JSON_PATH);

    private final AidProviderErrorRuleMapper errorRuleMapper;
    private final ErrorRuleCache errorRuleCache;

    public List<AidProviderErrorRule> list(String providerCode, String modelCode,
                                           String errorCode, Integer enabled) {
        LambdaQueryWrapper<AidProviderErrorRule> qw = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(providerCode)) {
            qw.eq(AidProviderErrorRule::getProviderCode, providerCode);
        }
        if (StringUtils.isNotBlank(modelCode)) {
            qw.eq(AidProviderErrorRule::getModelCode, modelCode);
        }
        if (StringUtils.isNotBlank(errorCode)) {
            qw.eq(AidProviderErrorRule::getErrorCode, errorCode);
        }
        if (enabled != null) {
            qw.eq(AidProviderErrorRule::getEnabled, enabled);
        }
        qw.orderByAsc(AidProviderErrorRule::getPriority);
        return errorRuleMapper.selectList(qw);
    }

    public AidProviderErrorRule get(Long id) {
        AidProviderErrorRule rule = errorRuleMapper.selectById(id);
        if (rule == null) {
            log.warn("[ErrorRuleService] 规则不存在 id={}", id);
            throw new ServiceException(RULE_NOT_FOUND);
        }
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(AidProviderErrorRule rule, String operator) {
        validate(rule);
        rule.setId(null);
        // 新增的非内置规则，默认 is_builtin=0
        if (rule.getIsBuiltin() == null) {
            rule.setIsBuiltin(0);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        rule.setCreateBy(operator);
        rule.setCreateTime(new Date());
        errorRuleMapper.insert(rule);
        errorRuleCache.refresh(rule.getProviderCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(AidProviderErrorRule rule, String operator) {
        validate(rule);
        AidProviderErrorRule exist = get(rule.getId());
        // 内置规则的 is_builtin 不允许被改
        rule.setIsBuiltin(exist.getIsBuiltin());
        rule.setUpdateBy(operator);
        rule.setUpdateTime(new Date());
        errorRuleMapper.updateById(rule);
        errorRuleCache.refresh(rule.getProviderCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            AidProviderErrorRule rule = errorRuleMapper.selectById(id);
            if (rule == null) {
                continue;
            }
            if (Objects.equals(rule.getIsBuiltin(), 1)) {
                log.warn("[ErrorRuleService] 拒绝删除内置规则 id={}", id);
                throw new ServiceException(DELETE_BUILTIN_TIP);
            }
        }
        errorRuleMapper.deleteByIds(ids);
        errorRuleCache.rebuild();
    }

    @Transactional(rollbackFor = Exception.class)
    public void toggle(Long id, Integer enabled, String operator) {
        AidProviderErrorRule rule = get(id);
        rule.setEnabled(enabled);
        rule.setUpdateBy(operator);
        rule.setUpdateTime(new Date());
        errorRuleMapper.updateById(rule);
        errorRuleCache.refresh(rule.getProviderCode());
    }

    /** 运维：触发内存 + 本地文件全量重建 */
    public void rebuildCache() {
        errorRuleCache.rebuild();
    }

    private void validate(AidProviderErrorRule rule) {
        if (rule == null) {
            throw new ServiceException("参数缺失");
        }
        if (StringUtils.isBlank(rule.getRuleName())) {
            throw new ServiceException("规则名不能为空");
        }
        if (StringUtils.isBlank(rule.getMatchType())) {
            throw new ServiceException("匹配类型不能为空");
        }
        // matchType 必须是 5 种合法值之一，否则规则将永远静默失败
        String upperMatchType = rule.getMatchType().toUpperCase();
        if (!VALID_MATCH_TYPES.contains(upperMatchType)) {
            log.warn("[ErrorRuleService] 非法 matchType={}", rule.getMatchType());
            throw new ServiceException("匹配类型不支持");
        }
        rule.setMatchType(upperMatchType);
        if (StringUtils.isBlank(rule.getMatchPattern())) {
            throw new ServiceException("匹配内容不能为空");
        }
        // REGEX 类型保存前校验正则有效性，避免运行期静默失效
        if (ErrorRuleEngine.MATCH_REGEX.equals(upperMatchType)) {
            try {
                Pattern.compile(rule.getMatchPattern());
            } catch (PatternSyntaxException pse) {
                log.warn("[ErrorRuleService] 正则表达式非法, pattern={}, err={}",
                        rule.getMatchPattern(), pse.getMessage());
                throw new ServiceException("规则格式有误");
            }
        }
        // JSON_PATH 必须配套字段路径
        if (ErrorRuleEngine.MATCH_JSON_PATH.equals(upperMatchType)
                && StringUtils.isBlank(rule.getMatchField())) {
            throw new ServiceException("参数缺失");
        }
        if (StringUtils.isBlank(rule.getErrorCode())) {
            throw new ServiceException("错误码不能为空");
        }
        // errorCode 必须是合法 TaskErrorCode 枚举，否则运行期静默失效
        try {
            TaskErrorCode.valueOf(rule.getErrorCode().trim());
        } catch (IllegalArgumentException iae) {
            log.warn("[ErrorRuleService] 错误码不存在, errorCode={}", rule.getErrorCode());
            throw new ServiceException("错误码格式有误");
        }
    }
}
