package com.aid.compose.service.impl;

import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposeTracks;
import com.aid.compose.service.ComposeBillingService;
import com.aid.compose.service.ComposeUrlNormalizer;
import com.aid.media.service.IMediaGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoreComposeServiceImplTest {

    @Mock
    private AidMediaTaskMapper aidMediaTaskMapper;
    @Mock
    private ComposeUrlNormalizer composeUrlNormalizer;
    @Mock
    private ComposeBillingService composeBillingService;
    @Mock
    private MpsConfigManager mpsConfigManager;
    @Mock
    private IMediaGenerationService mediaGenerationService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private CoreComposeServiceImpl service;

    @BeforeEach
    void setUp() {
        MpsProperties properties = new MpsProperties();
        properties.setSubtitleMaxChars(10);
        when(mpsConfigManager.getMpsProperties()).thenReturn(properties);
        service = new CoreComposeServiceImpl(aidMediaTaskMapper, composeUrlNormalizer, composeBillingService,
                mpsConfigManager, mediaGenerationService, transactionTemplate);
    }

    @Test
    void shouldSkipUntimedSubtitleWithoutVoiceDuration() {
        ComposeGroup group = baseGroup();
        group.setSubtitle("旁白：还没说话");
        ComposeCommand command = command(group);

        ComposeTracks tracks = service.buildTracks(command);

        assertTrue(tracks.getSubtitleItems().isEmpty());
    }

    @Test
    void shouldKeepUntimedSubtitleWithinVoiceDuration() {
        ComposeGroup group = baseGroup();
        group.setAudioUrls(List.of("https://example.com/voice.mp3"));
        group.setAudioDurations(List.of(2D));
        group.setSubtitle("旁白：已经开始说话");
        ComposeCommand command = command(group);

        ComposeTracks tracks = service.buildTracks(command);

        assertEquals(1, tracks.getSubtitleItems().size());
        assertEquals(0D, tracks.getSubtitleItems().get(0).getStart());
        assertEquals(2D, tracks.getSubtitleItems().get(0).getDuration());
    }

    private ComposeCommand command(ComposeGroup group) {
        ComposeCommand command = new ComposeCommand();
        command.setGroups(List.of(group));
        return command;
    }

    private ComposeGroup baseGroup() {
        ComposeGroup group = new ComposeGroup();
        group.setVideoUrls(List.of("https://example.com/video.mp4"));
        group.setVideoDurations(List.of(5D));
        return group;
    }
}
