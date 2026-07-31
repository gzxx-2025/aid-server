package com.aid.asset.audio.service.impl;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidConfigService;
import com.aid.aid.service.IAidReferenceAudioService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.asset.audio.dto.ReferenceAudioUploadRequest;
import com.aid.asset.audio.vo.ReferenceAudioVO;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.media.util.AudioDurationProber;
import com.aid.media.util.MediaBytesFetcher;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 参考音频登记用例。
 * 上传期只守「能不能存」的硬边界：本站路径、可探测格式、时长硬区间、项目配额、项目归属；
 * 「能不能用在某个模型上」不在这里判，由出片时的模型能力校验单点负责。
 *
 * @author 视觉AID
 */
class ReferenceAudioBusinessServiceImplTest {

    private static final Long PROJECT_ID = 100L;

    private static final Long EPISODE_ID = 200L;

    private static final Long USER_ID = 300L;

    private static final byte[] AUDIO_BYTES = new byte[]{1, 2, 3, 4};

    private IAidReferenceAudioService referenceAudioService;

    private IAidComicProjectService comicProjectService;

    private IAidComicEpisodeService comicEpisodeService;

    private IAidConfigService aidConfigService;

    private MockedStatic<MediaBytesFetcher> bytesFetcher;

    private MockedStatic<AudioDurationProber> durationProber;

    private ReferenceAudioBusinessServiceImpl service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(ReferenceAudioBusinessServiceImplTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidReferenceAudio.class);
        TableInfoHelper.initTableInfo(assistant, AidComicProject.class);
        TableInfoHelper.initTableInfo(assistant, AidComicEpisode.class);
        TableInfoHelper.initTableInfo(assistant, AidRoleVoiceBinding.class);
        TableInfoHelper.initTableInfo(assistant, AidConfig.class);
    }

    @BeforeEach
    void setUp() {
        referenceAudioService = mock(IAidReferenceAudioService.class);
        comicProjectService = mock(IAidComicProjectService.class);
        comicEpisodeService = mock(IAidComicEpisodeService.class);
        aidConfigService = mock(IAidConfigService.class);
        IAidRoleVoiceBindingService roleVoiceBindingService = mock(IAidRoleVoiceBindingService.class);
        MediaUrlResolver mediaUrlResolver = mock(MediaUrlResolver.class);
        when(mediaUrlResolver.toFullUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.example.com" + invocation.getArgument(0));

        service = new ReferenceAudioBusinessServiceImpl();
        ReflectionTestUtils.setField(service, "referenceAudioService", referenceAudioService);
        ReflectionTestUtils.setField(service, "comicProjectService", comicProjectService);
        ReflectionTestUtils.setField(service, "comicEpisodeService", comicEpisodeService);
        ReflectionTestUtils.setField(service, "roleVoiceBindingService", roleVoiceBindingService);
        ReflectionTestUtils.setField(service, "aidConfigService", aidConfigService);
        ReflectionTestUtils.setField(service, "mediaUrlResolver", mediaUrlResolver);

        when(comicProjectService.getOne(any(), eq(false))).thenReturn(seriesProject());
        when(comicEpisodeService.getOne(any(), eq(false))).thenReturn(episode());
        when(aidConfigService.getOne(any(), eq(false))).thenReturn(null);
        when(referenceAudioService.count(any())).thenReturn(0L);
        when(referenceAudioService.save(any())).thenReturn(true);

        bytesFetcher = mockStatic(MediaBytesFetcher.class);
        durationProber = mockStatic(AudioDurationProber.class);
        bytesFetcher.when(() -> MediaBytesFetcher.fetch(anyString(), anyInt()))
                .thenReturn(new MediaBytesFetcher.Content(AUDIO_BYTES, false));
        durationProber.when(() -> AudioDurationProber.probeDurationMs(any(byte[].class), eq(false)))
                .thenReturn(8000);
    }

    @AfterEach
    void tearDown() {
        bytesFetcher.close();
        durationProber.close();
    }

    @Test
    void shouldRegisterUploadedAudio() {
        ReferenceAudioVO vo = service.upload(request("/profile/upload/mine.wav"), USER_ID);

        assertEquals("/profile/upload/mine.wav", vo.getAudioUrl());
        assertEquals("wav", vo.getAudioFormat());
        assertEquals(8000, vo.getDurationMs());
        assertEquals(EPISODE_ID, vo.getEpisodeId());
        // 文件大小按服务端实际读到的字节数认定，不信任客户端上报
        assertEquals((long) AUDIO_BYTES.length, vo.getFileSize());
        verify(referenceAudioService).save(any());
    }

    @Test
    void shouldRejectExternalAudioUrl() {
        // @MediaUrl 只剥本站域名，站外地址会原样落到业务层，必须在此拦下
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("https://evil.com/a.wav"), USER_ID));

        assertEquals("音频格式有误", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectFormatWithoutDurationProbeSupport() {
        // 探不出时长的格式进出片链路必被能力校验剔除，与其让用户白传不如入口就拦
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.m4a"), USER_ID));

        assertEquals("格式不支持", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectWhenDurationProbeFails() {
        durationProber.when(() -> AudioDurationProber.probeDurationMs(any(byte[].class), eq(false)))
                .thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.wav"), USER_ID));

        assertEquals("音频不可用", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectWhenDurationOutOfHardRange() {
        // 默认硬区间 1s~300s：超长音频不入库
        durationProber.when(() -> AudioDurationProber.probeDurationMs(any(byte[].class), eq(false)))
                .thenReturn(300_001);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.wav"), USER_ID));

        assertEquals("时长超限", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectWhenProjectQuotaExhausted() {
        // 未配 aid_config 时走默认上限 20 条
        when(referenceAudioService.count(any())).thenReturn(20L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.wav"), USER_ID));

        assertEquals("数量超限", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectProjectOfAnotherUser() {
        // 项目查询本身带 userId 过滤，查不到即视为不属于当前用户
        when(comicProjectService.getOne(any(), eq(false))).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.wav"), USER_ID));

        assertEquals("项目不存在", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldRejectEpisodeOutsideProject() {
        when(comicEpisodeService.getOne(any(), eq(false))).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.upload(request("/profile/upload/mine.wav"), USER_ID));

        assertEquals("剧集不存在", ex.getMessage());
        verify(referenceAudioService, never()).save(any());
    }

    @Test
    void shouldNormalizeEpisodeIdForMovieProject() {
        // 电影项目无剧集维度，剧集ID 统一归一化为 0，且不再查剧集表
        when(comicProjectService.getOne(any(), eq(false))).thenReturn(movieProject());
        ReferenceAudioUploadRequest request = request("/profile/upload/mine.wav");
        request.setEpisodeId(null);

        ReferenceAudioVO vo = service.upload(request, USER_ID);

        assertEquals(0L, vo.getEpisodeId());
        verify(comicEpisodeService, never()).getOne(any(), eq(false));
    }

    private static ReferenceAudioUploadRequest request(String audioUrl) {
        ReferenceAudioUploadRequest request = new ReferenceAudioUploadRequest();
        request.setProjectId(PROJECT_ID);
        request.setEpisodeId(EPISODE_ID);
        request.setAudioName("我的录音");
        request.setAudioUrl(audioUrl);
        return request;
    }

    private static AidComicProject seriesProject() {
        AidComicProject project = new AidComicProject();
        project.setId(PROJECT_ID);
        project.setProjectType("series");
        return project;
    }

    private static AidComicProject movieProject() {
        AidComicProject project = new AidComicProject();
        project.setId(PROJECT_ID);
        project.setProjectType("movie");
        return project;
    }

    private static AidComicEpisode episode() {
        AidComicEpisode episode = new AidComicEpisode();
        episode.setId(EPISODE_ID);
        return episode;
    }

}
