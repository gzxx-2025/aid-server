package com.aid.common.error.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.AidErrorLog;
import com.aid.aid.mapper.AidErrorLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 错误样本日志查询 Service（管理后台用）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AidErrorLogQueryService {

    private final AidErrorLogMapper errorLogMapper;

    /**
     * 列表查询。
     *
     * @param providerCode 厂商编码
     * @param onlyUnmatched 仅查未识别样本（matched_rule_id IS NULL）
     */
    public List<AidErrorLog> list(String providerCode, Boolean onlyUnmatched) {
        LambdaQueryWrapper<AidErrorLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(providerCode)) {
            qw.eq(AidErrorLog::getProviderCode, providerCode);
        }
        if (Boolean.TRUE.equals(onlyUnmatched)) {
            qw.isNull(AidErrorLog::getMatchedRuleId);
        }
        qw.orderByDesc(AidErrorLog::getOccurrenceCount, AidErrorLog::getLastSeen);
        return errorLogMapper.selectList(qw);
    }

    public AidErrorLog get(Long id) {
        return errorLogMapper.selectById(id);
    }
}
