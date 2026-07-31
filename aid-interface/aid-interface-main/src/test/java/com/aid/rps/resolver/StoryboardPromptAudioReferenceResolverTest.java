package com.aid.rps.resolver;

import java.util.List;

import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidReferenceAudioService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.media.dto.ReferenceAudioInput;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分镜提示词参考音频解析用例。
 * 覆盖三条来源汇合后的关键口径：角色绑定的上传音频优先级、上传音频的显式来源语义、
 * 以及同批引用序号不重叠。
 *
 * @author 视觉AID
 */
class StoryboardPromptAudioReferenceResolverTest {

    private static final Long PROJECT_ID = 100L;

    private static final Long EPISODE_ID = 200L;

    private static final Long USER_ID = 300L;

    private static final Long ASSET_ID = 400L;

    private IAidRolePropSceneService rolePropSceneService;

    private IAidRoleVoiceBindingService roleVoiceBindingService;

    private IAidAudioRecordService audioRecordService;

    private IAidReferenceAudioService referenceAudioService;

    private StoryboardPromptAudioReferenceResolver resolver;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(StoryboardPromptAudioReferenceResolverTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidRolePropScene.class);
        TableInfoHelper.initTableInfo(assistant, AidRoleVoiceBinding.class);
        TableInfoHelper.initTableInfo(assistant, AidAudioRecord.class);
        TableInfoHelper.initTableInfo(assistant, AidReferenceAudio.class);
    }

    @BeforeEach
    void setUp() {
        rolePropSceneService = mock(IAidRolePropSceneService.class);
        roleVoiceBindingService = mock(IAidRoleVoiceBindingService.class);
        audioRecordService = mock(IAidAudioRecordService.class);
        referenceAudioService = mock(IAidReferenceAudioService.class);
        MediaUrlResolver mediaUrlResolver = mock(MediaUrlResolver.class);
        when(mediaUrlResolver.toFullUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.example.com" + invocation.getArgument(0));

        resolver = new StoryboardPromptAudioReferenceResolver();
        ReflectionTestUtils.setField(resolver, "rolePropSceneService", rolePropSceneService);
        ReflectionTestUtils.setField(resolver, "roleVoiceBindingService", roleVoiceBindingService);
        ReflectionTestUtils.setField(resolver, "audioRecordService", audioRecordService);
        ReflectionTestUtils.setField(resolver, "referenceAudioService", referenceAudioService);
        ReflectionTestUtils.setField(resolver, "mediaUrlResolver", mediaUrlResolver);

        when(rolePropSceneService.list(any(Wrapper.class))).thenReturn(List.of(character()));
        when(audioRecordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(referenceAudioService.list(any(Wrapper.class))).thenReturn(List.of());
    }

    @Test
    void shouldPreferBoundUploadedAudioOverVoiceSample() {
        // 角色绑了上传参考音频：下发的是用户自己的音频，不再是音色库试听样音
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of(binding("/sample/a.wav", 4000,
                77L, "/upload/mine.wav", 9000)));

        StoryboardPromptAudioReferenceResolver.ResolveResult result =
                resolver.resolve("镜头里 @音频1[音频-林小满] 在说话", PROJECT_ID, EPISODE_ID, USER_ID, null, null);

        List<ReferenceAudioInput> references = result.getAudioReferences();
        assertEquals(1, references.size());
        ReferenceAudioInput input = references.get(0);
        assertEquals("https://cdn.example.com/upload/mine.wav", input.getSampleUrl());
        assertEquals(9000, input.getDurationMs());
        assertEquals(77L, input.getReferenceAudioId());
        // 来源仍记隐式：由提示词占位推导而来，能力校验不通过时降级剔除而非阻断出片
        assertEquals(ReferenceAudioInput.SOURCE_VOICE_SAMPLE, input.getSourceType());
        assertFalse(input.isExplicit());
    }

    @Test
    void shouldFallBackToVoiceSampleWhenNoUploadedAudioBound() {
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of(binding("/sample/a.wav", 4000,
                null, null, null)));

        StoryboardPromptAudioReferenceResolver.ResolveResult result =
                resolver.resolve("镜头里 @音频1[音频-林小满] 在说话", PROJECT_ID, EPISODE_ID, USER_ID, null, null);

        List<ReferenceAudioInput> references = result.getAudioReferences();
        assertEquals(1, references.size());
        assertEquals("https://cdn.example.com/sample/a.wav", references.get(0).getSampleUrl());
        assertEquals(4000, references.get(0).getDurationMs());
        assertNull(references.get(0).getReferenceAudioId());
    }

    @Test
    void shouldKeepLegacyFormAudioNamesCompatibleAndDeduplicateBinding() {
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of(binding("/sample/a.wav", 4000,
                null, null, null)));

        StoryboardPromptAudioReferenceResolver.ResolveResult result = resolver.resolve(
                "@音频1[音频-林小满_初始形象]说话，@音频2[音频-林小满_战斗形象]回应",
                PROJECT_ID, EPISODE_ID, USER_ID, null, null);

        // 历史提示词仍可按角色名前缀解析，但同一音色绑定只下发一条参考音频。
        assertEquals(1, result.getAudioReferences().size());
        assertEquals(1L, result.getAudioReferences().get(0).getBindingId());
        assertTrue(result.getUnresolvedAudioNames().isEmpty());
    }

    @Test
    void shouldResolveUploadedAudioAsExplicitSource() {
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(referenceAudioService.list(any(Wrapper.class))).thenReturn(List.of(referenceAudio(51L, "我的录音",
                "/upload/mine.wav", 8000, "wav")));

        StoryboardPromptAudioReferenceResolver.ResolveResult result =
                resolver.resolve(null, PROJECT_ID, EPISODE_ID, USER_ID, null, List.of(51L));

        List<ReferenceAudioInput> references = result.getAudioReferences();
        assertEquals(1, references.size());
        ReferenceAudioInput input = references.get(0);
        assertEquals(ReferenceAudioInput.SOURCE_UPLOAD, input.getSourceType());
        // 显式来源：能力校验不通过必须报错，不能默默丢弃
        assertTrue(input.isExplicit());
        assertEquals(51L, input.getReferenceAudioId());
        assertEquals("我的录音", input.getName());
        assertEquals("wav", input.getFormat());
        assertEquals(8000, input.getDurationMs());
        assertEquals("https://cdn.example.com/upload/mine.wav", input.getSampleUrl());
        assertTrue(result.getUnresolvedReferenceAudioIds().isEmpty());
    }

    @Test
    void shouldCollectUnresolvedUploadedAudioIdsWithoutThrowing() {
        // 预览接口不抛异常：跨项目 / 已删除的 ID 只落 unresolved，由调用方按接口语义决定后续动作
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(referenceAudioService.list(any(Wrapper.class))).thenReturn(List.of(referenceAudio(51L, "我的录音",
                "/upload/mine.wav", 8000, "wav")));

        StoryboardPromptAudioReferenceResolver.ResolveResult result =
                resolver.resolve(null, PROJECT_ID, EPISODE_ID, USER_ID, null, List.of(51L, 52L));

        assertEquals(1, result.getAudioReferences().size());
        assertEquals(List.of(52L), result.getUnresolvedReferenceAudioIds());
    }

    @Test
    void shouldAssignDistinctIndexAcrossThreeSources() {
        // 三条来源汇合到同一个列表，引用序号必须互不重叠
        when(roleVoiceBindingService.list(any(Wrapper.class))).thenReturn(List.of(binding("/sample/a.wav", 4000,
                null, null, null)));
        when(audioRecordService.list(any(Wrapper.class))).thenReturn(List.of(audioRecord(61L, "/tts/r.wav", 3000)));
        when(referenceAudioService.list(any(Wrapper.class))).thenReturn(List.of(referenceAudio(51L, "我的录音",
                "/upload/mine.wav", 8000, "wav")));

        StoryboardPromptAudioReferenceResolver.ResolveResult result = resolver.resolve(
                "镜头里 @音频1[音频-林小满] 在说话", PROJECT_ID, EPISODE_ID, USER_ID, List.of(61L), List.of(51L));

        List<ReferenceAudioInput> references = result.getAudioReferences();
        assertEquals(3, references.size());
        assertEquals(1, references.get(0).getIndex());
        assertEquals(2, references.get(1).getIndex());
        assertEquals(3, references.get(2).getIndex());
        assertEquals(ReferenceAudioInput.SOURCE_VOICE_SAMPLE, references.get(0).getSourceType());
        assertEquals(ReferenceAudioInput.SOURCE_AUDIO_RECORD, references.get(1).getSourceType());
        assertEquals(ReferenceAudioInput.SOURCE_UPLOAD, references.get(2).getSourceType());
        assertEquals(3, result.getReferenceAudioUrls().size());
    }

    private static AidRolePropScene character() {
        AidRolePropScene character = new AidRolePropScene();
        character.setId(ASSET_ID);
        character.setName("林小满");
        return character;
    }

    private static AidRoleVoiceBinding binding(String sampleUrl, Integer sampleDurationMs,
            Long referenceAudioId, String referenceAudioUrl, Integer referenceAudioDurationMs) {
        AidRoleVoiceBinding binding = new AidRoleVoiceBinding();
        binding.setId(1L);
        binding.setAssetId(ASSET_ID);
        binding.setEpisodeId(EPISODE_ID);
        binding.setVoiceLibraryId(9L);
        binding.setVoiceName("温柔女声");
        binding.setSampleUrl(sampleUrl);
        binding.setSampleDurationMs(sampleDurationMs);
        binding.setReferenceAudioId(referenceAudioId);
        binding.setReferenceAudioUrl(referenceAudioUrl);
        binding.setReferenceAudioDurationMs(referenceAudioDurationMs);
        return binding;
    }

    private static AidAudioRecord audioRecord(Long id, String audioUrl, Integer durationMs) {
        AidAudioRecord record = new AidAudioRecord();
        record.setId(id);
        record.setAudioUrl(audioUrl);
        record.setDurationMs(durationMs);
        record.setVoiceLibraryId(9L);
        return record;
    }

    private static AidReferenceAudio referenceAudio(Long id, String audioName, String audioUrl,
            Integer durationMs, String audioFormat) {
        AidReferenceAudio audio = new AidReferenceAudio();
        audio.setId(id);
        audio.setAudioName(audioName);
        audio.setAudioUrl(audioUrl);
        audio.setDurationMs(durationMs);
        audio.setAudioFormat(audioFormat);
        return audio;
    }
}
