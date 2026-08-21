package com.aid.storyboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.storyboard.dto.StoryboardSortRequest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

@ExtendWith(MockitoExtension.class)
class StoryboardWorkbenchSortCompletenessTest
{
    @BeforeAll
    static void initTableInfo()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "storyboard-sort-test");
        assistant.setCurrentNamespace("storyboard-sort-test");
        TableInfoHelper.initTableInfo(assistant, AidStoryboard.class);
    }

    @Mock
    private IAidStoryboardService aidStoryboardService;

    @InjectMocks
    private StoryboardWorkbenchServiceImpl service;

    @Test
    void rejectsPartialSortListSoTimelineOrderCannotBePartiallyOverwritten()
    {
        long userId = 8L;
        AidStoryboard first = new AidStoryboard();
        first.setId(11L);
        first.setProjectId(21L);
        first.setEpisodeId(31L);
        AidStoryboard second = new AidStoryboard();
        second.setId(12L);
        second.setProjectId(21L);
        second.setEpisodeId(31L);

        when(aidStoryboardService.list(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(aidStoryboardService.count(any(Wrapper.class))).thenReturn(3L);
        StoryboardSortRequest request = new StoryboardSortRequest();
        request.setSortedIds(List.of(12L, 11L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.sortStoryboards(request, userId));

        assertEquals("排序列表不完整", error.getMessage());
        verify(aidStoryboardService, never()).update(any(Wrapper.class));
    }

    @Test
    void acceptsCompleteSortListAboveLegacyFiveHundredLimit()
    {
        long userId = 8L;
        List<Long> sortedIds = new ArrayList<>();
        List<AidStoryboard> scoped = new ArrayList<>();
        List<AidStoryboard> numbered = new ArrayList<>();
        for (long i = 1; i <= 501; i++)
        {
            sortedIds.add(i);
            AidStoryboard item = new AidStoryboard();
            item.setId(i);
            item.setProjectId(21L);
            item.setEpisodeId(31L);
            scoped.add(item);

            AidStoryboard persisted = new AidStoryboard();
            persisted.setId(i);
            persisted.setProjectId(21L);
            persisted.setEpisodeId(31L);
            persisted.setSortOrder(i);
            persisted.setScriptParams("{}");
            numbered.add(persisted);
        }
        when(aidStoryboardService.list(any(Wrapper.class))).thenReturn(scoped, numbered);
        when(aidStoryboardService.count(any(Wrapper.class))).thenReturn(501L);
        when(aidStoryboardService.updateBatchById(any(), eq(200))).thenReturn(true);
        StoryboardSortRequest request = new StoryboardSortRequest();
        request.setSortedIds(sortedIds);

        service.sortStoryboards(request, userId);

        verify(aidStoryboardService, times(2)).updateBatchById(any(), eq(200));
    }
}
