package com.aid.rps.assembler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.rps.enums.VideoPromptContinuityMode;
import com.aid.rps.resolver.StoryboardPromptBatchAligner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 组装分镜视频提示词的相邻镜头连续性上下文。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryboardVideoPromptContinuityAssembler
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String CONTEXT_SCHEMA = "video_prompt_continuity_v1";
    private static final String CONTEXT_TITLE = "\n【跨镜连续性上下文】\n";
    private static final String SOURCE_ROOT = "ROOT";
    private static final String SOURCE_BATCH_PREVIOUS = "BATCH_PREVIOUS";
    private static final String SOURCE_EXTERNAL_PREVIOUS = "EXTERNAL_PREVIOUS";

    private final IAidStoryboardService storyboardService;
    private final ObjectMapper objectMapper;

    /**
     * 解析并校验本次生成的连续性关系。
     *
     * @param projectId             项目 ID
     * @param episodeId             剧集 ID
     * @param userId                用户 ID
     * @param targets               本次实际生成的分镜
     * @param modeValue             连续性模式接口值
     * @param previousStoryboardId  单镜请求指定的上一镜 ID
     * @param singleSelection       是否为显式单镜请求
     * @param imagePromptColumn     是否读取图生方向提示词列
     * @return 连续性上下文
     */
    public ContinuityContext resolve(Long projectId, Long episodeId, Long userId,
                                     List<AidStoryboard> targets, String modeValue,
                                     Long previousStoryboardId, boolean singleSelection,
                                     boolean imagePromptColumn)
    {
        VideoPromptContinuityMode mode = parseMode(modeValue);
        if (VideoPromptContinuityMode.NONE.equals(mode))
        {
            return ContinuityContext.disabled();
        }
        if (CollectionUtil.isEmpty(targets))
        {
            log.error("视频提示词连续性目标为空: projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("分镜不能为空");
        }

        List<AidStoryboard> episodeStoryboards = loadEpisodeStoryboards(
                projectId, episodeId, userId, imagePromptColumn);
        Map<Long, Integer> positionById = new LinkedHashMap<>();
        for (int i = 0; i < episodeStoryboards.size(); i++)
        {
            positionById.put(episodeStoryboards.get(i).getId(), i);
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        for (AidStoryboard target : targets)
        {
            targetIds.add(target.getId());
        }

        if (singleSelection)
        {
            validateSingleSelection(targets.get(0), previousStoryboardId,
                    episodeStoryboards, positionById, imagePromptColumn);
        }
        else if (Objects.nonNull(previousStoryboardId))
        {
            log.info("批量视频提示词忽略 previousStoryboardId: projectId={}, episodeId={}, previousStoryboardId={}",
                    projectId, episodeId, previousStoryboardId);
        }

        List<ContinuityLink> links = new ArrayList<>(targets.size());
        for (AidStoryboard target : targets)
        {
            Integer position = positionById.get(target.getId());
            if (Objects.isNull(position))
            {
                log.error("连续性分镜不在权威序列: projectId={}, episodeId={}, storyboardId={}",
                        projectId, episodeId, target.getId());
                throw new ServiceException("分镜已变更");
            }
            String targetShotKey = StoryboardPromptBatchAligner.buildShotKey(target.getId());
            if (position == 0)
            {
                links.add(new ContinuityLink(targetShotKey, SOURCE_ROOT, null, null));
                continue;
            }

            AidStoryboard previous = episodeStoryboards.get(position - 1);
            String previousShotKey = StoryboardPromptBatchAligner.buildShotKey(previous.getId());
            if (targetIds.contains(previous.getId()))
            {
                links.add(new ContinuityLink(targetShotKey, SOURCE_BATCH_PREVIOUS,
                        previousShotKey, null));
                continue;
            }

            String previousPrompt = effectivePrompt(previous, imagePromptColumn);
            if (StrUtil.isBlank(previousPrompt))
            {
                log.error("视频提示词前镜缺失: projectId={}, episodeId={}, targetId={}, previousId={}, promptColumn={}",
                        projectId, episodeId, target.getId(), previous.getId(),
                        imagePromptColumn ? "video_prompt_image" : "video_prompt");
                throw new ServiceException("前镜提示词缺失");
            }
            links.add(new ContinuityLink(targetShotKey, SOURCE_EXTERNAL_PREVIOUS,
                    previousShotKey, previousPrompt));
        }
        return new ContinuityContext(CONTEXT_SCHEMA, true, List.copyOf(links));
    }

    /**
     * 从任务快照恢复并校验连续性上下文。
     *
     * @param modeValue     连续性模式接口值
     * @param snapshotValue 快照中的连续性上下文
     * @param targets       本轮实际生成的分镜
     * @return 已校验的连续性上下文
     */
    public ContinuityContext restore(String modeValue, Object snapshotValue, List<AidStoryboard> targets)
    {
        VideoPromptContinuityMode mode = parseMode(modeValue);
        if (VideoPromptContinuityMode.NONE.equals(mode))
        {
            return ContinuityContext.disabled();
        }
        if (Objects.isNull(snapshotValue) || CollectionUtil.isEmpty(targets))
        {
            log.error("视频提示词连续性快照缺失: targetCount={}",
                    CollectionUtil.isEmpty(targets) ? 0 : targets.size());
            throw new ServiceException("连续上下文异常");
        }

        ContinuityContext context;
        try
        {
            context = objectMapper.convertValue(snapshotValue, ContinuityContext.class);
        }
        catch (IllegalArgumentException e)
        {
            log.error("视频提示词连续性快照解析失败", e);
            throw new ServiceException("连续上下文异常");
        }
        if (!Objects.equals(CONTEXT_SCHEMA, context.schema()) || !context.enabled()
                || Objects.isNull(context.links()) || context.links().size() != targets.size())
        {
            log.error("视频提示词连续性快照结构无效: schema={}, enabled={}, linkCount={}, targetCount={}",
                    context.schema(), context.enabled(),
                    Objects.isNull(context.links()) ? 0 : context.links().size(), targets.size());
            throw new ServiceException("连续上下文异常");
        }

        Set<String> expectedTargetShotKeys = new LinkedHashSet<>();
        for (AidStoryboard target : targets)
        {
            expectedTargetShotKeys.add(StoryboardPromptBatchAligner.buildShotKey(target.getId()));
        }
        Set<String> restoredTargetShotKeys = new LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++)
        {
            String expectedTargetKey = StoryboardPromptBatchAligner.buildShotKey(targets.get(i).getId());
            ContinuityLink link = context.links().get(i);
            if (Objects.isNull(link) || !Objects.equals(expectedTargetKey, link.targetShotKey())
                    || !restoredTargetShotKeys.add(link.targetShotKey())
                    || !isLinkValid(link, context.links(), expectedTargetShotKeys, i))
            {
                log.error("视频提示词连续性快照关系无效: index={}, expectedTargetKey={}, actualTargetKey={}, source={}, previousShotKey={}",
                        i, expectedTargetKey, Objects.isNull(link) ? null : link.targetShotKey(),
                        Objects.isNull(link) ? null : link.source(),
                        Objects.isNull(link) ? null : link.previousShotKey());
                throw new ServiceException("连续上下文异常");
            }
        }
        return new ContinuityContext(CONTEXT_SCHEMA, true, List.copyOf(context.links()));
    }

    /**
     * 将标准 JSON 连续性数据追加到动态用户输入。
     *
     * @param userContent 动态用户输入
     * @param context     连续性上下文
     * @return 追加后的输入；未启用时原样返回
     */
    public String appendContext(String userContent, ContinuityContext context)
    {
        if (Objects.isNull(context) || !context.enabled())
        {
            return userContent;
        }
        try
        {
            return userContent + CONTEXT_TITLE + objectMapper.writeValueAsString(context) + '\n';
        }
        catch (JsonProcessingException e)
        {
            log.error("视频提示词连续性上下文序列化失败", e);
            throw new ServiceException("连续上下文异常");
        }
    }

    private VideoPromptContinuityMode parseMode(String modeValue)
    {
        return VideoPromptContinuityMode.fromValue(modeValue).orElseThrow(() -> {
            log.error("视频提示词连续性模式无效: continuityMode={}",
                    StrUtil.sub(StrUtil.nullToEmpty(modeValue), 0, 64));
            return new ServiceException("连续模式无效");
        });
    }

    private boolean isLinkValid(ContinuityLink link, List<ContinuityLink> links,
                                Set<String> targetShotKeys, int index)
    {
        if (SOURCE_ROOT.equals(link.source()))
        {
            return index == 0 && Objects.isNull(link.previousShotKey())
                    && Objects.isNull(link.previousPrompt());
        }
        if (SOURCE_BATCH_PREVIOUS.equals(link.source()))
        {
            return index > 0
                    && Objects.equals(links.get(index - 1).targetShotKey(), link.previousShotKey())
                    && Objects.isNull(link.previousPrompt());
        }
        if (SOURCE_EXTERNAL_PREVIOUS.equals(link.source()))
        {
            return StrUtil.isNotBlank(link.previousShotKey())
                    && !targetShotKeys.contains(link.previousShotKey())
                    && StrUtil.isNotBlank(link.previousPrompt());
        }
        return false;
    }

    private void validateSingleSelection(AidStoryboard target, Long previousStoryboardId,
                                         List<AidStoryboard> episodeStoryboards,
                                         Map<Long, Integer> positionById,
                                         boolean imagePromptColumn)
    {
        Integer position = positionById.get(target.getId());
        if (Objects.isNull(position))
        {
            log.error("单镜连续性分镜不在权威序列: storyboardId={}", target.getId());
            throw new ServiceException("分镜已变更");
        }
        if (position == 0)
        {
            log.info("单镜连续性拒绝首镜: storyboardId={}", target.getId());
            throw new ServiceException("首镜无上一镜");
        }
        if (Objects.isNull(previousStoryboardId))
        {
            log.info("单镜连续性缺少上一镜: storyboardId={}", target.getId());
            throw new ServiceException("请选择上一镜");
        }

        AidStoryboard previous = episodeStoryboards.get(position - 1);
        if (!Objects.equals(previous.getId(), previousStoryboardId))
        {
            log.info("单镜连续性上一镜不匹配: storyboardId={}, expectedPreviousId={}, actualPreviousId={}",
                    target.getId(), previous.getId(), previousStoryboardId);
            throw new ServiceException("上一镜不匹配");
        }
        if (StrUtil.isBlank(effectivePrompt(previous, imagePromptColumn)))
        {
            log.info("单镜连续性上一镜提示词缺失: storyboardId={}, previousStoryboardId={}, promptColumn={}",
                    target.getId(), previousStoryboardId,
                    imagePromptColumn ? "video_prompt_image" : "video_prompt");
            throw new ServiceException("前镜提示词缺失");
        }
    }

    private List<AidStoryboard> loadEpisodeStoryboards(Long projectId, Long episodeId, Long userId,
                                                       boolean imagePromptColumn)
    {
        LambdaQueryWrapper<AidStoryboard> wrapper = Wrappers.lambdaQuery();
        if (imagePromptColumn)
        {
            wrapper.select(AidStoryboard::getId, AidStoryboard::getSortOrder,
                    AidStoryboard::getVideoPromptImage);
        }
        else
        {
            wrapper.select(AidStoryboard::getId, AidStoryboard::getSortOrder,
                    AidStoryboard::getVideoPrompt);
        }
        wrapper.eq(AidStoryboard::getProjectId, projectId)
                .eq(AidStoryboard::getEpisodeId, episodeId)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                .orderByAsc(AidStoryboard::getSortOrder)
                .orderByAsc(AidStoryboard::getId);
        return storyboardService.list(wrapper);
    }

    private String effectivePrompt(AidStoryboard storyboard, boolean imagePromptColumn)
    {
        return imagePromptColumn ? storyboard.getVideoPromptImage() : storyboard.getVideoPrompt();
    }

    /**
     * 本次模型调用的连续性上下文。
     *
     * @param schema  上下文协议标识
     * @param enabled 是否启用
     * @param links   有序镜头关系
     */
    public record ContinuityContext(String schema, boolean enabled, List<ContinuityLink> links)
    {
        public static ContinuityContext disabled()
        {
            return new ContinuityContext(CONTEXT_SCHEMA, false, List.of());
        }
    }

    /**
     * 单个目标镜头与其直接上一镜的关系。
     *
     * @param targetShotKey   目标镜头身份键
     * @param source          连续性来源
     * @param previousShotKey 直接上一镜身份键
     * @param previousPrompt  批次外上一镜当前生效主提示词
     */
    public record ContinuityLink(String targetShotKey, String source,
                                 String previousShotKey, String previousPrompt)
    {
    }
}
