package com.aid.asset.service.impl;

import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.asset.dto.MergedAssetPageRequest;
import com.aid.asset.vo.MergedAssetVO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserComicAssetServiceImplTest {

    @Mock
    private IAidUserComicAssetService userAssetService;

    @Mock
    private IAidComicAssetService officialAssetService;

    private UserComicAssetServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "merged-asset-test");
        assistant.setCurrentNamespace("merged-asset-test");
        TableInfoHelper.initTableInfo(assistant, AidComicAsset.class);
        TableInfoHelper.initTableInfo(assistant, AidUserComicAsset.class);
        service = new UserComicAssetServiceImpl();
        ReflectionTestUtils.setField(service, "aidUserComicAssetService", userAssetService);
        ReflectionTestUtils.setField(service, "aidComicAssetService", officialAssetService);
    }

    @Test
    void allShouldPageRecommendedThenCustomThenOfficialNormal() {
        MergedAssetPageRequest request = request("all", 3);
        when(officialAssetService.normalizeStyleCategoryFilter("all")).thenReturn(null);
        when(userAssetService.count(org.mockito.ArgumentMatchers
                .<Wrapper<AidUserComicAsset>>any())).thenReturn(1L);
        when(officialAssetService.count(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAsset>>any())).thenReturn(1L, 1L);
        when(officialAssetService.list(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAsset>>any())).thenReturn(
                List.of(official(1L, true, 10)),
                List.of(official(2L, false, 1000)));
        when(userAssetService.list(org.mockito.ArgumentMatchers
                .<Wrapper<AidUserComicAsset>>any())).thenReturn(List.of(custom(3L, 20L)));

        Map<String, Object> data = service.pageMergedAssets(request, 9L);

        @SuppressWarnings("unchecked")
        List<MergedAssetVO> list = (List<MergedAssetVO>) data.get("list");
        assertEquals(List.of("official", "custom", "official"),
                list.stream().map(MergedAssetVO::getSourceFlag).toList());
        assertEquals(List.of(true, false, false),
                list.stream().map(MergedAssetVO::getIsRecommended).toList());
        assertEquals(3L, data.get("total"));
    }

    @Test
    void concreteCategoryShouldExcludeUnclassifiedCustomAssets() {
        MergedAssetPageRequest request = request("three_d", 2);
        when(officialAssetService.normalizeStyleCategoryFilter("three_d")).thenReturn("three_d");
        when(officialAssetService.count(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAsset>>any())).thenReturn(1L, 1L);
        when(officialAssetService.list(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAsset>>any())).thenReturn(
                List.of(official(1L, true, 10)),
                List.of(official(2L, false, 1000)));

        Map<String, Object> data = service.pageMergedAssets(request, 9L);

        @SuppressWarnings("unchecked")
        List<MergedAssetVO> list = (List<MergedAssetVO>) data.get("list");
        assertEquals(2, list.size());
        assertEquals(2L, data.get("total"));
        verify(userAssetService, never()).count(org.mockito.ArgumentMatchers
                .<Wrapper<AidUserComicAsset>>any());
        verify(userAssetService, never()).list(org.mockito.ArgumentMatchers
                .<Wrapper<AidUserComicAsset>>any());
        verify(officialAssetService, times(4)).applyStyleCategoryFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("three_d"));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<AidComicAsset>> countCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(officialAssetService, times(2)).count(countCaptor.capture());
        Set<Boolean> recommendedFilters = countCaptor.getAllValues().stream()
                .map(wrapper -> (LambdaQueryWrapper<AidComicAsset>) wrapper)
                .peek(LambdaQueryWrapper::getSqlSegment)
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .collect(Collectors.toSet());
        assertEquals(Set.of(true, false), recommendedFilters);
    }

    private MergedAssetPageRequest request(String categoryCode, int pageSize) {
        MergedAssetPageRequest request = new MergedAssetPageRequest();
        request.setAssetType("style");
        request.setCategoryCode(categoryCode);
        request.setPageNum(1);
        request.setPageSize(pageSize);
        return request;
    }

    private AidComicAsset official(Long id, boolean recommended, int sortOrder) {
        AidComicAsset asset = new AidComicAsset();
        asset.setId(id);
        asset.setAssetType("style");
        asset.setAssetName("official-" + id);
        asset.setIsRecommended(recommended);
        asset.setSortOrder(sortOrder);
        asset.setCategories(Collections.emptyList());
        return asset;
    }

    private AidUserComicAsset custom(Long id, Long sortOrder) {
        AidUserComicAsset asset = new AidUserComicAsset();
        asset.setId(id);
        asset.setAssetType("style");
        asset.setAssetName("custom-" + id);
        asset.setSortOrder(sortOrder);
        return asset;
    }
}
