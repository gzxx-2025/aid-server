package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidReferenceAudioService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.DateUtils;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.util.AudioDurationProber;
import com.aid.media.util.MediaFormatResolver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonIgnore;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜提示词参考音频解析器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardPromptAudioReferenceResolver
{
    private static final Pattern AUDIO_NAME = Pattern.compile("\\[(音频-[^\\]]+)]");
    private static final Pattern QUOTED_AUDIO_NAME = Pattern.compile("[\\\"“](音频-[^\\\"”]+)[\\\"”]");
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String STATUS_ENABLED = "0";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String DEL_FLAG_NORMAL = "0";

    /** 试听时长回写操作人：系统补齐历史数据 */
    private static final String BACKFILL_OPERATOR = "system";

    /** 剧集占位值：参考音频标记为全剧集通用 */
    private static final long EPISODE_ID_NONE = 0L;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    @Autowired
    private IAidAudioRecordService audioRecordService;

    @Autowired
    private IAidReferenceAudioService referenceAudioService;

    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /**
     * 解析提示词中的音频占位与用户显式选择的两类音频。
     * 三条来源在此汇合成同一个 {@link ReferenceAudioInput} 列表，
     * 后续的能力校验、截断与厂商下发均不再区分来源。
     *
     * @param text 提示词或 script_params 文本
     * @param projectId 项目 ID
     * @param episodeId 剧集 ID
     * @param userId 用户 ID
     * @param audioRecordIds 用户选择的音频记录 ID
     * @param referenceAudioIds 用户选择的上传参考音频 ID
     * @return 解析结果
     */
    public ResolveResult resolve(String text, Long projectId, Long episodeId, Long userId,
            List<Long> audioRecordIds, List<Long> referenceAudioIds)
    {
        ResolveResult result = new ResolveResult();
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return result;
        }
        Map<Integer, String> names = parseNames(text, result);
        List<ReferenceAudioInput> references = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<Long> unresolvedRecordIds = new ArrayList<>();
        List<Long> unresolvedUploadIds = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(names))
        {
            resolveVoiceSamples(names, projectId, episodeId, userId, references, unresolved);
        }
        int nextIndex = names.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
        nextIndex = resolveAudioRecords(audioRecordIds, projectId, episodeId, userId, nextIndex,
                references, unresolvedRecordIds);
        // 上传参考音频排在音频记录之后，接着上一段用掉的编号继续发号，保证同批引用序号不重复
        resolveUploadedAudios(referenceAudioIds, projectId, episodeId, userId, nextIndex,
                references, unresolvedUploadIds);
        result.setReferenceAudioUrls(references.stream()
                .map(ReferenceAudioInput::getSampleUrl)
                .filter(StrUtil::isNotBlank)
                .toList());
        result.setAudioReferences(references);
        result.setUnresolvedAudioNames(unresolved);
        result.setUnresolvedAudioRecordIds(unresolvedRecordIds);
        result.setUnresolvedReferenceAudioIds(unresolvedUploadIds);
        return result;
    }

    private Map<Integer, String> parseNames(String text, ResolveResult resolveResult)
    {
        // 占位正则统一收口在 StoryboardAudioPlaceholders，保证配音链路与出片链路口径一致
        StoryboardAudioPlaceholders.PlaceholderResult placeholders = StoryboardAudioPlaceholders.parse(text);
        Map<Integer, String> result = new LinkedHashMap<>(placeholders.getNames());
        if (placeholders.isConflicted())
        {
            resolveResult.setInvalidAudioIndex(true);
        }
        if (StrUtil.isBlank(text))
        {
            return result;
        }
        int nextIndex = placeholders.nextIndex();
        Set<String> seen = new LinkedHashSet<>(result.values());
        Matcher bracketName = AUDIO_NAME.matcher(text);
        while (bracketName.find())
        {
            String name = StrUtil.trim(bracketName.group(1));
            if (StrUtil.isNotBlank(name) && seen.add(name))
            {
                result.put(nextIndex++, name);
            }
        }
        Matcher quotedName = QUOTED_AUDIO_NAME.matcher(text);
        while (quotedName.find())
        {
            String name = StrUtil.trim(quotedName.group(1));
            if (StrUtil.isNotBlank(name) && seen.add(name))
            {
                result.put(nextIndex++, name);
            }
        }
        return result;
    }

    private void resolveVoiceSamples(Map<Integer, String> names, Long projectId, Long episodeId, Long userId,
            List<ReferenceAudioInput> references, List<String> unresolved)
    {
        List<AidRolePropScene> characters = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidRoleVoiceBinding> bindings = loadBindings(characters, projectId, episodeId, userId);
        List<Map.Entry<Integer, String>> ordered = new ArrayList<>(names.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        Set<Long> emittedBindingIds = new LinkedHashSet<>();
        for (Map.Entry<Integer, String> entry : ordered)
        {
            AidRolePropScene character = matchCharacter(entry.getValue(), characters);
            AidRoleVoiceBinding binding = Objects.isNull(character) ? null : bindings.get(character.getId());
            // 角色绑了上传参考音频就用它，否则回落音色库试听样音
            boolean useUploaded = Objects.nonNull(binding) && StrUtil.isNotBlank(binding.getReferenceAudioUrl());
            String relativeUrl = Objects.isNull(binding) ? null
                    : (useUploaded ? binding.getReferenceAudioUrl() : binding.getSampleUrl());
            if (StrUtil.isBlank(relativeUrl))
            {
                if (!unresolved.contains(entry.getValue()))
                {
                    unresolved.add(entry.getValue());
                }
                continue;
            }
            // 同一角色被多个占位引用时只下发一条，避免重复计入条数上限与总时长
            if (!emittedBindingIds.add(binding.getId()))
            {
                continue;
            }
            String fullUrl = mediaUrlResolver.toFullUrl(relativeUrl);
            ReferenceAudioInput input = new ReferenceAudioInput();
            input.setIndex(entry.getKey());
            input.setName(entry.getValue());
            // 来源仍记隐式：由提示词占位推导而来，校验不通过时降级剔除而非阻断出片
            input.setSourceType(ReferenceAudioInput.SOURCE_VOICE_SAMPLE);
            input.setAssetId(character.getId());
            input.setBindingId(binding.getId());
            input.setVoiceLibraryId(binding.getVoiceLibraryId());
            input.setVoiceName(binding.getVoiceName());
            input.setSampleUrl(fullUrl);
            input.setFormat(MediaFormatResolver.resolveFormat(fullUrl));
            if (useUploaded)
            {
                // 上传音频的时长在登记时已探测入库，不做懒探测，也不回写绑定表的试听时长列
                input.setReferenceAudioId(binding.getReferenceAudioId());
                input.setDurationMs(binding.getReferenceAudioDurationMs());
            }
            else
            {
                input.setDurationMs(resolveSampleDurationMs(binding, fullUrl));
            }
            references.add(input);
        }
    }

    /**
     * 取试听样音时长：优先读绑定表冗余列，缺失时探测一次并回写，避免每次出片重复下载。
     *
     * @param binding 角色音色绑定
     * @param fullUrl 试听音频完整 URL
     * @return 时长毫秒；探测失败返回 null
     */
    private Integer resolveSampleDurationMs(AidRoleVoiceBinding binding, String fullUrl)
    {
        Integer durationMs = binding.getSampleDurationMs();
        if (Objects.nonNull(durationMs) && durationMs > 0)
        {
            return durationMs;
        }
        Integer probed = AudioDurationProber.probeDurationMs(fullUrl);
        backfillSampleDuration(binding.getId(), probed);
        return probed;
    }

    /**
     * 回写试听样音时长，历史绑定行首次使用后自愈。
     *
     * @param bindingId  绑定 ID
     * @param durationMs 探测出的时长毫秒
     */
    private void backfillSampleDuration(Long bindingId, Integer durationMs)
    {
        if (Objects.isNull(bindingId) || Objects.isNull(durationMs) || durationMs <= 0)
        {
            return;
        }
        try
        {
            AidRoleVoiceBinding update = new AidRoleVoiceBinding();
            update.setId(bindingId);
            update.setSampleDurationMs(durationMs);
            update.setUpdateTime(DateUtils.getNowDate());
            update.setUpdateBy(BACKFILL_OPERATOR);
            roleVoiceBindingService.updateById(update);
        }
        catch (Exception ex)
        {
            log.warn("试听时长回写失败, bindingId={}, err={}", bindingId, ex.getMessage());
        }
    }

    private Map<Long, AidRoleVoiceBinding> loadBindings(List<AidRolePropScene> characters, Long projectId,
            Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(characters))
        {
            return Collections.emptyMap();
        }
        List<Long> assetIds = characters.stream().map(AidRolePropScene::getId).toList();
        List<AidRoleVoiceBinding> rows = roleVoiceBindingService.list(
                Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .select(AidRoleVoiceBinding::getId, AidRoleVoiceBinding::getAssetId,
                                AidRoleVoiceBinding::getEpisodeId, AidRoleVoiceBinding::getVoiceLibraryId,
                                AidRoleVoiceBinding::getVoiceName, AidRoleVoiceBinding::getSampleUrl,
                                AidRoleVoiceBinding::getSampleDurationMs,
                                AidRoleVoiceBinding::getReferenceAudioId,
                                AidRoleVoiceBinding::getReferenceAudioUrl,
                                AidRoleVoiceBinding::getReferenceAudioDurationMs)
                        .in(AidRoleVoiceBinding::getAssetId, assetIds)
                        .eq(AidRoleVoiceBinding::getProjectId, projectId)
                        .eq(AidRoleVoiceBinding::getUserId, userId)
                        .and(Objects.nonNull(episodeId), wrapper -> wrapper
                                .eq(AidRoleVoiceBinding::getEpisodeId, episodeId)
                                .or().isNull(AidRoleVoiceBinding::getEpisodeId)
                                .or().eq(AidRoleVoiceBinding::getEpisodeId, 0L))
                        .eq(AidRoleVoiceBinding::getStatus, STATUS_ENABLED)
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidRoleVoiceBinding> result = new LinkedHashMap<>();
        for (AidRoleVoiceBinding row : rows)
        {
            AidRoleVoiceBinding current = result.get(row.getAssetId());
            boolean exact = Objects.nonNull(episodeId) && Objects.equals(row.getEpisodeId(), episodeId);
            boolean currentExact = Objects.nonNull(current) && Objects.nonNull(episodeId)
                    && Objects.equals(current.getEpisodeId(), episodeId);
            if (Objects.isNull(current) || (exact && !currentExact))
            {
                result.put(row.getAssetId(), row);
            }
        }
        return result;
    }

    private AidRolePropScene matchCharacter(String audioName, List<AidRolePropScene> characters)
    {
        String normalized = normalizeAudioName(audioName);
        return characters.stream()
                .filter(character -> {
                    String roleName = normalizeAudioName(character.getName());
                    return Objects.equals(normalized, roleName) || normalized.startsWith(roleName + "_");
                })
                .max(Comparator.comparingInt(character -> StrUtil.length(character.getName())))
                .orElse(null);
    }

    /**
     * 解析用户显式选择的配音记录。
     *
     * @param audioRecordIds      用户选择的音频记录 ID
     * @param projectId           项目 ID
     * @param episodeId           剧集 ID
     * @param userId              用户 ID
     * @param startIndex          本段引用序号起点
     * @param references          解析结果收集器
     * @param unresolvedRecordIds 不可用记录 ID 收集器
     * @return 本段用完后的下一个可用引用序号
     */
    private int resolveAudioRecords(List<Long> audioRecordIds, Long projectId, Long episodeId, Long userId,
            int startIndex, List<ReferenceAudioInput> references, List<Long> unresolvedRecordIds)
    {
        if (CollectionUtil.isEmpty(audioRecordIds))
        {
            return startIndex;
        }
        List<Long> orderedIds = audioRecordIds.stream().filter(Objects::nonNull).distinct().toList();
        if (CollectionUtil.isEmpty(orderedIds))
        {
            return startIndex;
        }
        List<AidAudioRecord> records = audioRecordService.list(
                Wrappers.<AidAudioRecord>lambdaQuery()
                        .select(AidAudioRecord::getId, AidAudioRecord::getAudioUrl,
                                AidAudioRecord::getDurationMs, AidAudioRecord::getVoiceLibraryId)
                        .in(AidAudioRecord::getId, orderedIds)
                        .eq(AidAudioRecord::getProjectId, projectId)
                        // 剧集收口：禁止跨剧集引用他集音频记录
                        .eq(Objects.nonNull(episodeId), AidAudioRecord::getEpisodeId, episodeId)
                        .eq(AidAudioRecord::getUserId, userId)
                        .eq(AidAudioRecord::getStatus, STATUS_SUCCEEDED)
                        .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidAudioRecord> byId = new LinkedHashMap<>();
        records.forEach(record -> byId.put(record.getId(), record));
        int index = startIndex;
        for (Long id : orderedIds)
        {
            AidAudioRecord record = byId.get(id);
            if (Objects.isNull(record) || StrUtil.isBlank(record.getAudioUrl()))
            {
                unresolvedRecordIds.add(id);
                continue;
            }
            String fullUrl = mediaUrlResolver.toFullUrl(record.getAudioUrl());
            ReferenceAudioInput input = new ReferenceAudioInput();
            input.setIndex(index++);
            input.setName("音频记录" + id);
            input.setSourceType(ReferenceAudioInput.SOURCE_AUDIO_RECORD);
            input.setAudioRecordId(id);
            input.setVoiceLibraryId(record.getVoiceLibraryId());
            input.setSampleUrl(fullUrl);
            input.setFormat(MediaFormatResolver.resolveFormat(fullUrl));
            Integer durationMs = record.getDurationMs();
            input.setDurationMs(Objects.nonNull(durationMs) && durationMs > 0
                    ? durationMs : AudioDurationProber.probeDurationMs(fullUrl));
            references.add(input);
        }
        return index;
    }

    /**
     * 解析用户显式选择的上传参考音频。
     * 时长在登记接口已探测入库，此处不再回源下载；
     * 查不到的 ID 只收集不抛错，由调用方按接口语义决定预览降级还是出片报错。
     *
     * @param referenceAudioIds   用户选择的上传参考音频 ID
     * @param projectId           项目 ID
     * @param episodeId           剧集 ID
     * @param userId              用户 ID
     * @param startIndex          本段引用序号起点
     * @param references          解析结果收集器
     * @param unresolvedUploadIds 不可用参考音频 ID 收集器
     */
    private void resolveUploadedAudios(List<Long> referenceAudioIds, Long projectId, Long episodeId, Long userId,
            int startIndex, List<ReferenceAudioInput> references, List<Long> unresolvedUploadIds)
    {
        if (CollectionUtil.isEmpty(referenceAudioIds))
        {
            return;
        }
        List<Long> orderedIds = referenceAudioIds.stream().filter(Objects::nonNull).distinct().toList();
        if (CollectionUtil.isEmpty(orderedIds))
        {
            return;
        }
        List<AidReferenceAudio> rows = referenceAudioService.list(
                Wrappers.<AidReferenceAudio>lambdaQuery()
                        .select(AidReferenceAudio::getId, AidReferenceAudio::getAudioName,
                                AidReferenceAudio::getAudioUrl, AidReferenceAudio::getDurationMs,
                                AidReferenceAudio::getAudioFormat)
                        .in(AidReferenceAudio::getId, orderedIds)
                        .eq(AidReferenceAudio::getProjectId, projectId)
                        .eq(AidReferenceAudio::getUserId, userId)
                        // 剧集收口：本集专属音频不跨集引用，episode_id=0 为全剧集通用
                        .and(Objects.nonNull(episodeId), wrapper -> wrapper
                                .eq(AidReferenceAudio::getEpisodeId, episodeId)
                                .or().eq(AidReferenceAudio::getEpisodeId, EPISODE_ID_NONE))
                        .eq(AidReferenceAudio::getStatus, STATUS_ENABLED)
                        .eq(AidReferenceAudio::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidReferenceAudio> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        int index = startIndex;
        for (Long id : orderedIds)
        {
            AidReferenceAudio audio = byId.get(id);
            if (Objects.isNull(audio) || StrUtil.isBlank(audio.getAudioUrl()))
            {
                unresolvedUploadIds.add(id);
                continue;
            }
            String fullUrl = mediaUrlResolver.toFullUrl(audio.getAudioUrl());
            ReferenceAudioInput input = new ReferenceAudioInput();
            input.setIndex(index++);
            input.setName(StrUtil.isNotBlank(audio.getAudioName()) ? audio.getAudioName() : "参考音频" + id);
            input.setSourceType(ReferenceAudioInput.SOURCE_UPLOAD);
            input.setReferenceAudioId(id);
            input.setSampleUrl(fullUrl);
            // 格式以登记时落库的为准，缺失才回退按 URL 后缀推断
            input.setFormat(StrUtil.isNotBlank(audio.getAudioFormat())
                    ? audio.getAudioFormat() : MediaFormatResolver.resolveFormat(fullUrl));
            input.setDurationMs(audio.getDurationMs());
            references.add(input);
        }
    }

    private String normalizeAudioName(String name)
    {
        String value = StrUtil.removePrefix(StrUtil.trimToEmpty(name), "音频-");
        return value.replace('*', '_').replace('-', '_').replace('－', '_')
                .replace('—', '_').replace('–', '_').toLowerCase(Locale.ROOT);
    }

    /** 参考音频解析结果。 */
    @Data
    public static class ResolveResult
    {
        /** 已解析参考音频 URL。 */
        private List<String> referenceAudioUrls = Collections.emptyList();

        /** 已解析参考音频明细。 */
        private List<ReferenceAudioInput> audioReferences = Collections.emptyList();

        /** 未解析音频引用名（提示词占位推导，不可用时降级跳过）。 */
        private List<String> unresolvedAudioNames = Collections.emptyList();

        /** 未解析的音频记录 ID（用户显式选择，不可用时必须报错）。 */
        private List<Long> unresolvedAudioRecordIds = Collections.emptyList();

        /** 未解析的上传参考音频 ID（用户显式选择，不可用时必须报错）。 */
        private List<Long> unresolvedReferenceAudioIds = Collections.emptyList();

        /** 同一编号绑定了不同音频名，仅供生成链路校验，不对接口输出。 */
        @JsonIgnore
        private boolean invalidAudioIndex;
    }
}
