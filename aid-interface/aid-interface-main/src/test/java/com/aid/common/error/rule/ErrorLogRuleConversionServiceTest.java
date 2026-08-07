package com.aid.common.error.rule;

import com.aid.aid.domain.AidErrorLog;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.aid.mapper.AidErrorLogMapper;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorLogRuleConversionServiceTest {

    private AidErrorLogMapper errorLogMapper;
    private ErrorRuleService errorRuleService;
    private ErrorLogRuleConversionService conversionService;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, AidErrorLog.class);
        errorLogMapper = mock(AidErrorLogMapper.class);
        errorRuleService = mock(ErrorRuleService.class);
        conversionService = new ErrorLogRuleConversionService(
                errorLogMapper, errorRuleService, new ErrorRuleEngine());
    }

    @Test
    void shouldBuildEditableDraftAndRestoreGlobalScope() {
        AidErrorLog sample = sample();
        sample.setProviderCode("_global");
        sample.setRawMessage("任务派发失败\nrequestId=dynamic-value");
        when(errorLogMapper.selectOne(any())).thenReturn(sample);

        AidProviderErrorRule draft = conversionService.buildDraft(sample.getId());

        assertNull(draft.getProviderCode());
        assertEquals("全局错误规则", draft.getRuleName());
        assertEquals(ErrorRuleEngine.MATCH_KEYWORD, draft.getMatchType());
        assertEquals("任务派发失败", draft.getMatchPattern());
        assertNull(draft.getErrorCode());
    }

    @Test
    void shouldCreateRuleAndMarkSourceSampleInOneConversion() {
        AidErrorLog sample = sample();
        when(errorLogMapper.selectOne(any())).thenReturn(sample);
        when(errorRuleService.add(any(), any())).thenReturn(88L);
        when(errorLogMapper.updateById(any(AidErrorLog.class))).thenReturn(1);

        AidProviderErrorRule rule = rule();
        Long ruleId = conversionService.convert(sample.getId(), rule, "admin");

        assertEquals(88L, ruleId);
        verify(errorRuleService).validate(rule);
        verify(errorRuleService).add(rule, "admin");
        ArgumentCaptor<AidErrorLog> updateCaptor = ArgumentCaptor.forClass(AidErrorLog.class);
        verify(errorLogMapper).updateById(updateCaptor.capture());
        assertEquals(88L, updateCaptor.getValue().getMatchedRuleId());
        assertEquals(TaskErrorCode.UPSTREAM_SERVER_ERROR.name(),
                updateCaptor.getValue().getMatchedErrorCode());
    }

    @Test
    void shouldRejectRuleThatDoesNotMatchSourceSample() {
        AidErrorLog sample = sample();
        when(errorLogMapper.selectOne(any())).thenReturn(sample);
        AidProviderErrorRule rule = rule();
        rule.setMatchPattern("另一个错误");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> conversionService.convert(sample.getId(), rule, "admin"));

        assertEquals("规则未命中样本", exception.getMessage());
    }

    @Test
    void shouldRejectRuleScopeThatDoesNotCoverSourceSample() {
        AidErrorLog sample = sample();
        when(errorLogMapper.selectOne(any())).thenReturn(sample);
        AidProviderErrorRule rule = rule();
        rule.setProviderCode("another-provider");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> conversionService.convert(sample.getId(), rule, "admin"));

        assertEquals("规则范围不匹配", exception.getMessage());
    }

    @Test
    void shouldRejectDisabledRuleBeforeCreatingIt() {
        AidErrorLog sample = sample();
        when(errorLogMapper.selectOne(any())).thenReturn(sample);
        AidProviderErrorRule rule = rule();
        rule.setEnabled(0);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> conversionService.convert(sample.getId(), rule, "admin"));

        assertEquals("请先启用规则", exception.getMessage());
    }

    @Test
    void shouldRejectAlreadyConvertedSample() {
        AidErrorLog sample = sample();
        sample.setMatchedRuleId(66L);
        when(errorLogMapper.selectOne(any())).thenReturn(sample);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> conversionService.buildDraft(sample.getId()));

        assertEquals("样本已处理", exception.getMessage());
    }

    private AidErrorLog sample() {
        AidErrorLog sample = new AidErrorLog();
        sample.setId(13L);
        sample.setProviderCode("volcengine");
        sample.setModelCode("seedance-test");
        sample.setHttpStatus(500);
        sample.setRawMessage("任务派发失败");
        return sample;
    }

    private AidProviderErrorRule rule() {
        AidProviderErrorRule rule = new AidProviderErrorRule();
        rule.setProviderCode("volcengine");
        rule.setModelCode("seedance-test");
        rule.setRuleName("任务派发失败");
        rule.setMatchType(ErrorRuleEngine.MATCH_KEYWORD);
        rule.setMatchPattern("任务派发失败");
        rule.setErrorCode(TaskErrorCode.UPSTREAM_SERVER_ERROR.name());
        rule.setEnabled(1);
        return rule;
    }
}
