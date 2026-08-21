package com.aid.asset.service.impl;

import com.aid.aid.service.IAidComicAssetService;
import com.aid.asset.dto.OfficialAssetQueryRequest;
import com.aid.common.exception.ServiceException;
import com.aid.aid.domain.AidComicAsset;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAssetBusinessServiceImplTest {

    @Mock
    private IAidComicAssetService officialAssetService;

    private UserAssetBusinessServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "official-asset-test");
        assistant.setCurrentNamespace("official-asset-test");
        TableInfoHelper.initTableInfo(assistant, AidComicAsset.class);
        service = new UserAssetBusinessServiceImpl();
        ReflectionTestUtils.setField(service, "aidComicAssetService", officialAssetService);
    }

    @Test
    void concreteCategoryShouldRejectNonStyleAssetType() {
        OfficialAssetQueryRequest request = new OfficialAssetQueryRequest();
        request.setAssetType("pose");
        request.setCategoryCode("three_d");
        when(officialAssetService.normalizeStyleCategoryFilter("three_d")).thenReturn("three_d");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.queryOfficialAssetList(request));

        assertEquals("分类仅限风格", error.getMessage());
    }
}
