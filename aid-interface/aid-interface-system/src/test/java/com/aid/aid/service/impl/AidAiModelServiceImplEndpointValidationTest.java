package com.aid.aid.service.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证模型提交路径在持久化边界完成校验与规范化。 */
class AidAiModelServiceImplEndpointValidationTest {

    private final AidAiModelMapper mapper = mock(AidAiModelMapper.class);
    private final TestableAidAiModelService service = createService();

    @BeforeAll
    static void initTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "model-endpoint-test");
        assistant.setCurrentNamespace(AidAiModelMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidAiModel.class);
    }

    @Test
    void submitPathIsNormalizedAndReturnedByTheSavedEntity() {
        AidAiModel model = disabledModel("  /proxy/vendor/v8/create  ");

        assertEquals(1, service.insertAidAiModel(model));
        assertEquals("/proxy/vendor/v8/create", model.getApiSuffix());
        verify(mapper).insert(same(model));
    }

    @Test
    void invalidSubmitPathsAreRejectedBeforeSave() {
        for (String invalid : new String[]{
            "https://evil.test/create",
            "/create#fragment",
            "/api/%2e%2e/create",
            "/api//create",
            "/api/create%0d%0aX-Test:1",
            "/api/create?redirect=https://evil.test"
        }) {
            assertThrows(ServiceException.class,
                    () -> service.insertAidAiModel(disabledModel(invalid)), invalid);
        }
        verifyNoInteractions(mapper);
    }

    private AidAiModel disabledModel(String apiSuffix) {
        AidAiModel model = new AidAiModel();
        model.setModelCode("endpoint-contract");
        model.setStatus("1");
        model.setApiSuffix(apiSuffix);
        return model;
    }

    private TestableAidAiModelService createService() {
        TestableAidAiModelService value = new TestableAidAiModelService();
        value.setBaseMapper(mapper);
        when(mapper.insert(any(AidAiModel.class))).thenReturn(1);
        return value;
    }

    private static final class TestableAidAiModelService extends AidAiModelServiceImpl {
        private void setBaseMapper(AidAiModelMapper mapper) {
            this.baseMapper = mapper;
        }
    }
}
