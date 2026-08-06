package com.aid.compose.listener;

import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.compose.ComposeConstants;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComposeResultListenerTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(ComposeResultListenerTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidEpisodeEditor.class);
    }

    @Test
    void shouldIgnoreSucceededCallbackFromStaleComposeTask() {
        AidMediaTaskMapper taskMapper = mock(AidMediaTaskMapper.class);
        AidEpisodeEditorMapper editorMapper = mock(AidEpisodeEditorMapper.class);
        ComposeResultListener listener = new ComposeResultListener(
                taskMapper,
                mock(AidGenRecordMapper.class),
                mock(AidAudioRecordMapper.class),
                editorMapper,
                mock(IAidComicProjectService.class),
                mock(IAidComicEpisodeService.class));

        AidMediaTask task = new AidMediaTask();
        task.setId(100L);
        task.setMediaType(ComposeConstants.MEDIA_TYPE_COMPOSE);
        task.setStatus(MediaTaskStatus.SUCCEEDED.name());
        task.setOssUrl("/compose/stale.mp4");
        task.setCallbackCategory(ComposeConstants.CALLBACK_EPISODE_EDITOR);
        task.setCallbackRecordId(46L);
        when(taskMapper.selectById(100L)).thenReturn(task);

        AidEpisodeEditor editor = new AidEpisodeEditor();
        editor.setId(46L);
        editor.setExportTaskId("101");
        when(editorMapper.selectOne(any())).thenReturn(editor);

        listener.onMediaTaskOssPersisted(new MediaTaskOssPersistedEvent(this, 100L, 1L));

        verify(editorMapper, never()).update(any(), any());
    }
}
