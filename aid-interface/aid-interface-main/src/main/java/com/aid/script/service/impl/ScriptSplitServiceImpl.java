package com.aid.script.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.common.exception.ServiceException;
import com.aid.enums.ProjectTypeEnum;
import com.aid.enums.ScriptStatusEnum;
import com.aid.project.service.IProjectContentGuardService;
import com.aid.script.dto.ScriptSplitPreviewRequest;
import com.aid.script.helper.ScriptEpisodeSplitter;
import com.aid.script.helper.ScriptEpisodeSplitter.EpisodeSegment;
import com.aid.script.service.IScriptSplitService;
import com.aid.script.vo.ScriptSplitConfirmVO;
import com.aid.script.vo.ScriptSplitEpisodeItemVO;
import com.aid.script.vo.ScriptSplitPreviewVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 剧本自动分集实现：预览只读、确认在项目锁内逐集创建剧集与剧本（版本1、使用中）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptSplitServiceImpl implements IScriptSplitService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 剧集状态：草稿 */
    private static final int EPISODE_STATUS_DRAFT = 0;

    /** 整篇剧本字数上限（与整篇上传口径一致） */
    private static final int MAX_TOTAL_WORDS = 100000;

    /** 单集字数上限（与单集上传口径一致） */
    private static final int MAX_EPISODE_WORDS = 10000;

    /** 单次分集数量上限（防误配分集词切出海量集） */
    private static final int MAX_EPISODES = 200;

    /** 单集描述截取字数 */
    private static final int DESCRIPTION_LENGTH = 20;

    /** 单集标题最大长度（超长截断，防止分集行整段长文本进标题） */
    private static final int TITLE_MAX_LENGTH = 50;

    /** 确认入库锁 Key 前缀（项目级，防连点重复建集） */
    private static final String CONFIRM_LOCK_PREFIX = "script:split:lock:";

    /** 确认入库锁 TTL（秒） */
    private static final long CONFIRM_LOCK_TTL_SECONDS = 60L;

    /**
     * HTML 标签检测（与剧本保存口径一致：仅允许 Markdown 与换行标签）。
     */
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>");

    /** 换行标签（剧本中唯一允许的 HTML 标签） */
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)</?br\\s*/?>");

    /** 项目服务（归属与类型校验） */
    private final IAidComicProjectService aidComicProjectService;

    /** 剧集服务（建集与集号顺延） */
    private final IAidComicEpisodeService aidComicEpisodeService;

    /** 剧本服务（逐集建剧本记录） */
    private final IAidComicScriptService aidComicScriptService;

    /** Redis 客户端（确认入库防连点锁） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 编程式事务模板：批量建集在锁内以事务提交，保证「事务提交后再释放锁」 */
    private final TransactionTemplate transactionTemplate;

    /** 项目内容守卫：公开期间禁止剧集增删（与直接建集接口同口径） */
    private final IProjectContentGuardService projectContentGuardService;

    @Override
    public ScriptSplitPreviewVO previewSplit(ScriptSplitPreviewRequest request, Long userId) {
        validateProject(request.getProjectId(), userId);
        List<EpisodeSegment> segments = parseAndValidate(request);

        List<ScriptSplitEpisodeItemVO> items = new ArrayList<>(segments.size());
        int totalChars = 0;
        for (EpisodeSegment segment : segments) {
            ScriptSplitEpisodeItemVO item = new ScriptSplitEpisodeItemVO();
            item.setEpisodeNo(segment.getSequenceNo());
            item.setTitle(resolveTitle(segment));
            item.setDescription(ScriptEpisodeSplitter.summarize(segment.getContent(), DESCRIPTION_LENGTH));
            int charCount = countWord(segment.getContent());
            item.setCharCount(charCount);
            totalChars += charCount;
            items.add(item);
        }
        ScriptSplitPreviewVO result = new ScriptSplitPreviewVO();
        result.setTotalEpisodes(items.size());
        result.setTotalCharCount(totalChars);
        result.setEpisodeKeyword(StrUtil.blankToDefault(request.getEpisodeKeyword(), "第一集").trim());
        result.setItems(items);
        return result;
    }

    @Override
    public ScriptSplitConfirmVO confirmSplit(ScriptSplitPreviewRequest request, Long userId) {
        AidComicProject project = validateProject(request.getProjectId(), userId);
        // 公开锁：批量建集与直接建集同口径，项目公开期间禁止剧集增删
        projectContentGuardService.assertProjectEditable(request.getProjectId());
        // 项目级防连点锁：确认入库是批量建集写操作，连点会成倍创建剧集。
        // 锁在事务外获取、事务提交后才释放，保证并发请求读 max(episode_no) 时上一批已可见
        String lockKey = CONFIRM_LOCK_PREFIX + request.getProjectId();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", CONFIRM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("剧本分集确认锁竞争失败, projectId={}, userId={}", request.getProjectId(), userId);
            throw new ServiceException("导入中请稍候");
        }
        try {
            List<EpisodeSegment> segments = parseAndValidate(request);
            // 批量建集在编程式事务内执行：任一集失败全部回滚
            return transactionTemplate.execute(status -> doConfirmSplit(request, userId, segments, project));
        } finally {
            try {
                stringRedisTemplate.delete(lockKey);
            } catch (Exception ex) {
                log.warn("剧本分集确认锁释放异常, projectId={}", request.getProjectId(), ex);
            }
        }
    }

    /**
     * 分集确认入库主体（事务内执行）：逐集创建剧集与剧本记录。
     *
     * @param request  确认入参
     * @param userId   当前用户ID
     * @param segments 已解析校验的单集列表
     * @return 入库结果
     */
    private ScriptSplitConfirmVO doConfirmSplit(ScriptSplitPreviewRequest request, Long userId,
                                                List<EpisodeSegment> segments, AidComicProject project) {
        // 集号在项目现有最大集号上顺延（max(episode_no)+1）
        long nextEpisodeNo = resolveNextEpisodeNo(request.getProjectId());

        List<ScriptSplitConfirmVO.CreatedEpisodeVO> created = new ArrayList<>(segments.size());
        Date now = new Date();
        for (EpisodeSegment segment : segments) {
            String title = resolveTitle(segment);
            String description = ScriptEpisodeSplitter.summarize(segment.getContent(), DESCRIPTION_LENGTH);
            // 建集：标题/描述来自解析结果，状态草稿，步骤走 DB 默认（步骤1）
            AidComicEpisode episode = new AidComicEpisode();
            episode.setProjectId(request.getProjectId());
            episode.setUserId(userId);
            episode.setEpisodeNo(nextEpisodeNo);
            episode.setComicTitle(title);
            episode.setComicDesc(description);
            // 继承项目默认生成/创作模式（与直接建集接口同口径，保证两入口落库一致）
            episode.setGenMode(project.getDefaultGenMode());
            episode.setCreationMode(project.getDefaultCreationMode());
            episode.setStatus(EPISODE_STATUS_DRAFT);
            episode.setDelFlag(DEL_FLAG_NORMAL);
            episode.setCreateTime(now);
            episode.setCreateBy(String.valueOf(userId));
            aidComicEpisodeService.save(episode);

            // 建剧本：该集正文，版本1、使用中
            AidComicScript script = new AidComicScript();
            script.setProjectId(request.getProjectId());
            script.setEpisodeId(episode.getId());
            script.setUserId(userId);
            script.setOriginalText(segment.getContent());
            script.setComicVersion(1);
            script.setStatus(ScriptStatusEnum.IN_USE.getValue());
            script.setIsExtracted(0);
            script.setDelFlag(DEL_FLAG_NORMAL);
            script.setCreateTime(now);
            script.setCreateBy(String.valueOf(userId));
            aidComicScriptService.save(script);

            ScriptSplitConfirmVO.CreatedEpisodeVO item = new ScriptSplitConfirmVO.CreatedEpisodeVO();
            item.setEpisodeId(episode.getId());
            item.setEpisodeNo(nextEpisodeNo);
            item.setTitle(title);
            item.setDescription(description);
            item.setScriptId(script.getId());
            created.add(item);
            nextEpisodeNo++;
        }
        log.info("剧本分集入库完成, projectId={}, userId={}, episodes={}",
                request.getProjectId(), userId, created.size());
        ScriptSplitConfirmVO result = new ScriptSplitConfirmVO();
        result.setTotalEpisodes(created.size());
        result.setEpisodes(created);
        return result;
    }

    /**
     * 项目校验：归属当前用户且必须是剧集类型。
     *
     * @param projectId 项目ID
     * @param userId    当前用户ID
     */
    private AidComicProject validateProject(Long projectId, Long userId) {
        // 查询字段精简：校验需归属与类型 + 建集继承的默认模式（新增使用字段时此处必须同步补充）
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getUserId,
                                AidComicProject::getProjectType,
                                AidComicProject::getDefaultGenMode, AidComicProject::getDefaultCreationMode)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL));
        if (Objects.isNull(project) || !Objects.equals(project.getUserId(), userId)) {
            log.error("剧本分集项目缺失或越权, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(ProjectTypeEnum.SERIES.getValue(), project.getProjectType())) {
            log.info("剧本分集拒绝非剧集项目, projectId={}, projectType={}", projectId, project.getProjectType());
            throw new ServiceException("仅剧集可分集");
        }
        return project;
    }

    /**
     * 解析并校验分集结果（预览与确认共用同一口径，保证两次结果一致）。
     *
     * @param request 分集入参
     * @return 有序单集列表
     */
    private List<EpisodeSegment> parseAndValidate(ScriptSplitPreviewRequest request) {
        String text = request.getScriptText();
        assertOnlyLineBreakHtml(text);
        int totalWords = countWord(text);
        if (totalWords > MAX_TOTAL_WORDS) {
            log.info("剧本分集整篇超字数, projectId={}, words={}, max={}",
                    request.getProjectId(), totalWords, MAX_TOTAL_WORDS);
            throw new ServiceException("剧本超10万");
        }
        List<EpisodeSegment> segments = ScriptEpisodeSplitter.split(text, request.getEpisodeKeyword());
        if (CollectionUtil.isEmpty(segments)) {
            log.info("剧本分集未命中分集词, projectId={}, keyword={}",
                    request.getProjectId(), request.getEpisodeKeyword());
            throw new ServiceException("未识别分集词");
        }
        if (segments.size() > MAX_EPISODES) {
            log.info("剧本分集集数超限, projectId={}, episodes={}, max={}",
                    request.getProjectId(), segments.size(), MAX_EPISODES);
            throw new ServiceException("分集过多");
        }
        for (EpisodeSegment segment : segments) {
            if (StrUtil.isBlank(segment.getContent())) {
                log.info("剧本分集存在空集, projectId={}, sequenceNo={}",
                        request.getProjectId(), segment.getSequenceNo());
                throw new ServiceException("第" + segment.getSequenceNo() + "集无内容");
            }
            int words = countWord(segment.getContent());
            if (words > MAX_EPISODE_WORDS) {
                log.info("剧本分集单集超字数, projectId={}, sequenceNo={}, words={}",
                        request.getProjectId(), segment.getSequenceNo(), words);
                throw new ServiceException("第" + segment.getSequenceNo() + "集超1万字");
            }
        }
        return segments;
    }

    /**
     * 解析单集标题：分集行余文优先（超长截断），无余文回退「第N集」。
     *
     * @param segment 单集解析结果
     * @return 标题
     */
    private String resolveTitle(EpisodeSegment segment) {
        String title = segment.getTitle();
        if (StrUtil.isBlank(title)) {
            return "第" + segment.getSequenceNo() + "集";
        }
        String trimmed = title.trim();
        return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) : trimmed;
    }

    /**
     * 取项目下一个可用集号：max(episode_no)+1（无剧集从 1 起）。
     *
     * @param projectId 项目ID
     * @return 下一个集号
     */
    private long resolveNextEpisodeNo(Long projectId) {
        // 查询字段精简：顺延集号只需最大 episode_no（新增使用字段时此处必须同步补充）
        AidComicEpisode latest = aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .select(AidComicEpisode::getId, AidComicEpisode::getEpisodeNo)
                        .eq(AidComicEpisode::getProjectId, projectId)
                        .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidComicEpisode::getEpisodeNo)
                        .last("LIMIT 1"));
        if (Objects.isNull(latest) || Objects.isNull(latest.getEpisodeNo())) {
            return 1L;
        }
        return latest.getEpisodeNo() + 1L;
    }

    /**
     * 校验剧本文本：除换行标签 {@code <br>} 外不允许任何 HTML 标签（与上传口径一致）。
     *
     * @param text 剧本文本
     */
    private void assertOnlyLineBreakHtml(String text) {
        String stripped = BR_TAG_PATTERN.matcher(text).replaceAll("");
        if (HTML_TAG_PATTERN.matcher(stripped).find()) {
            log.info("剧本分集含非法 HTML 标签, 内容前200字={}", StrUtil.sub(text, 0, 200));
            throw new ServiceException("标签格式有误");
        }
    }

    /**
     * 统计有效字数（剔除换行标签与全部空白，与剧本上传口径一致）。
     *
     * @param text 文本
     * @return 字数
     */
    private int countWord(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        String pure = BR_TAG_PATTERN.matcher(text).replaceAll("");
        return pure.replaceAll("\\s+", "").length();
    }
}
