package com.aid.compose.service.impl;

import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposeTracks;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.service.ComposeBillingService;
import com.aid.compose.service.ComposeUrlNormalizer;
import com.aid.compose.util.SubtitleScreenSplitter;
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
    void shouldRenderUntimedSubtitleAcrossVideoWithoutVoiceDuration() {
        ComposeGroup group = baseGroup();
        group.setSubtitle("旁白：还没说话");
        ComposeCommand command = command(group);

        ComposeTracks tracks = service.buildTracks(command);

        assertEquals(1, tracks.getSubtitleItems().size());
        assertEquals("还没说话", tracks.getSubtitleItems().get(0).getSubtitleText());
        assertEquals(5D, tracks.getSubtitleItems().get(0).getDuration());
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

    @Test
    void shouldRenderTwoUntimedDialoguesWithoutSpeakerPrefixes() {
        ComposeGroup group = baseGroup();
        group.setSubtitle("张叔：有没有对症的治疗药物\n科普医师：目前需要进一步检查");

        ComposeTracks tracks = service.buildTracks(command(group));

        assertEquals(2, tracks.getSubtitleItems().size());
        assertEquals("有没有对症的治疗药物",
                tracks.getSubtitleItems().get(0).getSubtitleText());
        assertEquals("目前需要进一步检查",
                tracks.getSubtitleItems().get(1).getSubtitleText());
        assertEquals(5D, tracks.getSubtitleItems().stream()
                .mapToDouble(item -> item.getDuration()).sum());
    }

    @Test
    void shouldRenderTimedSubtitleWithoutSpeakerPrefix() {
        ComposeGroup group = baseGroup();
        TimedSubtitleCue cue = new TimedSubtitleCue();
        cue.setStartSeconds(0D);
        cue.setEndSeconds(2D);
        cue.setSpeaker("科普医师");
        cue.setText("查出指标异常后");
        group.setSubtitleCues(List.of(cue));

        ComposeTracks tracks = service.buildTracks(command(group));

        assertEquals(1, tracks.getSubtitleItems().size());
        assertEquals("查出指标异常后", tracks.getSubtitleItems().get(0).getSubtitleText());
        assertEquals(2D, tracks.getSubtitleItems().get(0).getDuration());
    }

    @Test
    void shouldBalanceLongQuestionIntoSevenToTwelveCharactersPerScreen() {
        ComposeGroup group = baseGroup();
        TimedSubtitleCue cue = new TimedSubtitleCue();
        cue.setStartSeconds(0D);
        cue.setEndSeconds(4D);
        cue.setSpeaker("张叔");
        cue.setText("查出指标异常后有没有对症的治疗药物");
        group.setSubtitleCues(List.of(cue));

        ComposeTracks tracks = service.buildTracks(command(group));

        assertEquals(2, tracks.getSubtitleItems().size());
        assertTrue(tracks.getSubtitleItems().stream().allMatch(item -> {
            int characters = SubtitleScreenSplitter.charCount(item.getSubtitleText());
            return characters >= 7 && characters <= 12;
        }));
        assertEquals(4D, tracks.getSubtitleItems().stream()
                .mapToDouble(item -> item.getDuration()).sum());
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
