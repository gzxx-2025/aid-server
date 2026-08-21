package com.aid.aid.service.impl;

import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidComicAssetCategory;
import com.aid.aid.service.IAidComicAssetCategoryService;
import com.aid.aid.vo.StyleCategoryVO;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AidComicAssetServiceImplTest {

    @Mock
    private IAidComicAssetCategoryService categoryService;

    private AidComicAssetServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "comic-asset-category-test");
        assistant.setCurrentNamespace("comic-asset-category-test");
        TableInfoHelper.initTableInfo(assistant, AidComicAssetCategory.class);
        service = new AidComicAssetServiceImpl();
        ReflectionTestUtils.setField(service, "aidComicAssetCategoryService", categoryService);
    }

    @Test
    void shouldTreatBlankAndAllAsNoCategoryFilter() {
        assertNull(service.normalizeStyleCategoryFilter(null));
        assertNull(service.normalizeStyleCategoryFilter("  "));
        assertNull(service.normalizeStyleCategoryFilter(" all "));
    }

    @Test
    void shouldRejectUnknownCategoryFilter() {
        assertThrows(ServiceException.class,
                () -> service.normalizeStyleCategoryFilter("unknown"));
    }

    @Test
    void shouldExposeAllFirstAndStableCategoryCodes() {
        List<StyleCategoryVO> options = service.listStyleCategoryOptions(true);

        assertEquals(11, options.size());
        assertEquals("all", options.get(0).getCode());
        assertEquals("comic_drama", options.get(1).getCode());
        assertEquals("korean", options.get(10).getCode());
    }

    @Test
    void shouldAttachCategoriesInUiOrderWithoutNPlusOne() {
        AidComicAssetCategory threeD = relation(1L, "three_d");
        AidComicAssetCategory comicDrama = relation(1L, "comic_drama");
        when(categoryService.list(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAssetCategory>>any())).thenReturn(List.of(threeD, comicDrama));
        AidComicAsset style = asset(1L, "style");
        AidComicAsset other = asset(2L, "pose");

        service.attachStyleCategories(List.of(style, other));

        assertEquals(List.of("comic_drama", "three_d"), style.getCategoryCodes());
        assertEquals("漫剧", style.getCategories().get(0).getLabel());
        assertEquals(List.of(), other.getCategories());
    }

    @Test
    void partialStyleUpdateShouldPreserveRecommendationSortAndCategories() {
        AidComicAssetServiceImpl spyService = spy(service);
        AidComicAsset current = asset(1L, "style");
        current.setIsRecommended(true);
        current.setSortOrder(90);
        AidComicAssetCategory chinese = relation(1L, "chinese");
        doReturn(current).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(org.mockito.ArgumentMatchers.any(AidComicAsset.class));
        when(categoryService.list(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAssetCategory>>any())).thenReturn(List.of(chinese));
        when(categoryService.remove(org.mockito.ArgumentMatchers
                .<Wrapper<AidComicAssetCategory>>any())).thenReturn(true);
        when(categoryService.saveBatch(anyCollection())).thenReturn(true);

        AidComicAsset update = asset(1L, "style");
        assertEquals(1, spyService.updateAidComicAsset(update));

        assertTrue(update.getIsRecommended());
        assertEquals(90, update.getSortOrder());
        assertEquals(List.of("chinese"), update.getCategoryCodes());
    }

    private AidComicAssetCategory relation(Long assetId, String code) {
        AidComicAssetCategory relation = new AidComicAssetCategory();
        relation.setAssetId(assetId);
        relation.setCategoryCode(code);
        return relation;
    }

    private AidComicAsset asset(Long id, String assetType) {
        AidComicAsset asset = new AidComicAsset();
        asset.setId(id);
        asset.setAssetType(assetType);
        return asset;
    }
}
