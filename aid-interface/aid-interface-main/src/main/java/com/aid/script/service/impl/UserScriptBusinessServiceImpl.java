package com.aid.script.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.common.exception.ServiceException;
import com.aid.common.page.SafePageUtils;
import com.aid.common.utils.DateUtils;
import com.aid.enums.ProjectTypeEnum;
import com.aid.enums.ScriptStatusEnum;
import com.aid.script.dto.UserScriptQueryRequest;
import com.aid.script.dto.UserScriptSaveRequest;
import com.aid.script.dto.UserScriptUploadRequest;
import com.aid.script.helper.ScriptFileExtractor;
import com.aid.script.service.IUserScriptBusinessService;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户剧本业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserScriptBusinessServiceImpl implements IUserScriptBusinessService
{
    /**
     * HTML 标签检测正则。
     * 剧本原文 {@code original_text} 仅允许 Markdown，不允许 HTML 标签。
     * 该正则只匹配"真正的 HTML 标签形态"：{@code <} + 可选 {@code /} + 字母开头标签名（+ 任意属性） + {@code >}，
     * 以及自闭合写法（如 {@code <br/>}）。
     * 不会误伤 Markdown 中合法的 {@code <}：例如 {@code a < b}、{@code 1<2}、箭头 {@code ->}，
     * 因为这些 {@code <} 后面跟的是空格 / 数字 / 非字母，匹配不到 {@code <字母} 的标签开头结构。
     */
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>");

    /**
     * 换行标签匹配（上传剧本中唯一允许保留的 HTML 标签）。
     * 匹配 {@code <br>}、{@code <br/>}、{@code <br />}、{@code </br>}，大小写不敏感。
     */
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)</?br\\s*/?>");

    /** 上传剧本允许的文件扩展名白名单（仅纯文本 .txt，图片/Word 等一律拒绝） */
    private static final Set<String> ALLOWED_UPLOAD_EXTENSIONS =
            Set.of(ScriptFileExtractor.EXT_TXT);

    /** 电影剧本字数上限：1 万字 */
    private static final int MAX_WORD_MOVIE = 10000;

    /** 剧集-单集（指定集数上传）字数上限：1 万字 */
    private static final int MAX_WORD_EPISODE_SINGLE = 10000;

    /** 剧集-整篇（未指定集数、待分集导入）字数上限：10 万字 */
    private static final int MAX_WORD_SERIES = 100000;

    /** 上传剧本文件大小上限（字节）：10 万字 UTF-8 约 300KB，10MB 富余；超限先拒绝再解析，防超大文件整包进堆 */
    private static final long MAX_UPLOAD_FILE_BYTES = 10L * 1024L * 1024L;
    @Autowired
    private IAidComicScriptService aidComicScriptService;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    /**
     * 校验项目归属并返回项目
     */
    private AidComicProject getAndCheckProject(Long projectId, Long userId)
    {
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .eq(AidComicProject::getDelFlag, "0"));
        if (project == null) {
            log.info("剧本操作项目缺失或越权, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        return project;
    }

    /**
     * 校验剧集归属
     */
    private void checkEpisodeBelongsToProject(Long episodeId, Long projectId)
    {
        if (episodeId == null || episodeId == 0L) {
            return;
        }
        AidComicEpisode episode = aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .eq(AidComicEpisode::getId, episodeId)
                        .eq(AidComicEpisode::getProjectId, projectId)
                        .eq(AidComicEpisode::getDelFlag, "0"));
        if (episode == null) {
            throw new RuntimeException("剧集不存在或不属于该项目");
        }
    }

    /**
     * 校验请求参数（项目归属 + 电影/剧集类型校验）
     */
    private AidComicProject validateRequest(UserScriptSaveRequest request, Long userId)
    {
        AidComicProject project = getAndCheckProject(request.getProjectId(), userId);
        boolean isMovie = ProjectTypeEnum.MOVIE.getValue().equals(project.getProjectType());
        if (isMovie && request.getEpisodeId() != 0L) {
            throw new RuntimeException("电影类型项目集数ID应为0");
        }
        if (!isMovie) {
            if (request.getEpisodeId() == 0L) {
                throw new RuntimeException("剧集类型项目必须指定集数ID");
            }
            checkEpisodeBelongsToProject(request.getEpisodeId(), request.getProjectId());
        }
        // 剧本原文与上传口径一致：仅允许 <br> 换行标签，其它 HTML 标签一律拒绝
        // （上传/分集导入允许 <br> 入库，保存必须兼容，否则导入内容一经编辑即无法保存）
        assertOnlyLineBreakHtml(request.getOriginalText());
        // 字数上限与上传口径一致：保存目标恒为单集/电影整篇，超限拒绝（防绕过上传限制写入超大文本）
        int wordCount = countWord(request.getOriginalText());
        if (wordCount > MAX_WORD_EPISODE_SINGLE) {
            log.info("剧本保存超字数, projectId={}, episodeId={}, wordCount={}, max={}",
                    request.getProjectId(), request.getEpisodeId(), wordCount, MAX_WORD_EPISODE_SINGLE);
            throw new ServiceException("剧本超1万字");
        }
        return project;
    }

    /**
     * 查询该集当前使用中的剧本
     */
    private AidComicScript getCurrentScript(Long projectId, Long episodeId, Long userId)
    {
        return aidComicScriptService.getOne(
                Wrappers.<AidComicScript>lambdaQuery()
                        .eq(AidComicScript::getProjectId, projectId)
                        .eq(AidComicScript::getEpisodeId, episodeId)
                        .eq(AidComicScript::getUserId, userId)
                        .eq(AidComicScript::getStatus, ScriptStatusEnum.IN_USE.getValue())
                        .eq(AidComicScript::getDelFlag, "0"));
    }

    /**
     * 通过剧本ID校验归属，返回剧本
     */
    private AidComicScript getAndCheckScript(Long id, Long userId)
    {
        AidComicScript script = aidComicScriptService.getOne(
                Wrappers.<AidComicScript>lambdaQuery()
                        .eq(AidComicScript::getId, id)
                        .eq(AidComicScript::getUserId, userId)
                        .eq(AidComicScript::getDelFlag, "0"));
        if (script == null) {
            throw new RuntimeException("剧本不存在");
        }
        getAndCheckProject(script.getProjectId(), userId);
        return script;
    }

    @Override
    public List<AidComicScript> selectUserScriptList(UserScriptQueryRequest request, Long userId)
    {
        getAndCheckProject(request.getProjectId(), userId);

        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, request.getProjectId());
        wrapper.eq(AidComicScript::getUserId, userId);
        wrapper.eq(AidComicScript::getDelFlag, "0");
        if (request.getEpisodeId() != null) {
            wrapper.eq(AidComicScript::getEpisodeId, request.getEpisodeId());
        }
        if (request.getStatus() != null) {
            wrapper.eq(AidComicScript::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(AidComicScript::getComicVersion);
        // 分页紧邻列表查询开启（归属校验查询在前会消费分页拦截，导致列表退化为全量）
        SafePageUtils.startClampedPage();
        return aidComicScriptService.list(wrapper);
    }

    @Override
    public AidComicScript selectUserScriptByProject(Long projectId, Long episodeId, Long userId)
    {
        // 校验项目归属
        getAndCheckProject(projectId, userId);

        // 校验剧集归属（剧集类型项目）
        if (episodeId != null && episodeId != 0L) {
            checkEpisodeBelongsToProject(episodeId, projectId);
        }

        // 优先查询当前使用中的剧本(status=1)
        AidComicScript script = aidComicScriptService.getOne(
                Wrappers.<AidComicScript>lambdaQuery()
                        .eq(AidComicScript::getProjectId, projectId)
                        .eq(AidComicScript::getEpisodeId, episodeId)
                        .eq(AidComicScript::getUserId, userId)
                        .eq(AidComicScript::getStatus, ScriptStatusEnum.IN_USE.getValue())
                        .eq(AidComicScript::getDelFlag, "0"));

        // 如果不存在使用中的剧本，则返回草稿版本(status=0)
        if (script == null) {
            script = aidComicScriptService.getOne(
                    Wrappers.<AidComicScript>lambdaQuery()
                            .eq(AidComicScript::getProjectId, projectId)
                            .eq(AidComicScript::getEpisodeId, episodeId)
                            .eq(AidComicScript::getUserId, userId)
                            .eq(AidComicScript::getStatus, ScriptStatusEnum.DRAFT.getValue())
                            .eq(AidComicScript::getDelFlag, "0")
                            .orderByDesc(AidComicScript::getComicVersion)
                            .last("LIMIT 1"));
        }

        // 都不存在则返回null
        return script;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicScript saveUserScript(UserScriptSaveRequest request, Long userId)
    {
        validateRequest(request, userId);

        // 创建 / 版本+1 统一入库
        return doSaveAsNewVersion(request.getProjectId(), request.getEpisodeId(), userId, request.getOriginalText());
    }

    /**
     * 核心入库逻辑：无当前使用中剧本则创建版本1，已有则将其转历史并创建版本+1。
     *
     * @param projectId    项目ID
     * @param episodeId    集数ID（电影为0）
     * @param userId       当前用户ID
     * @param originalText 剧本原文
     * @return 新写入的剧本记录
     */
    private AidComicScript doSaveAsNewVersion(Long projectId, Long episodeId, Long userId, String originalText)
    {
        // 查询该集当前使用中的剧本
        AidComicScript currentScript = getCurrentScript(projectId, episodeId, userId);

        if (currentScript == null) {
            AidComicScript script = new AidComicScript();
            script.setProjectId(projectId);
            script.setEpisodeId(episodeId);
            script.setUserId(userId);
            script.setOriginalText(originalText);
            script.setComicVersion(1);
            script.setStatus(ScriptStatusEnum.IN_USE.getValue());
            script.setIsExtracted(0);
            script.setDelFlag("0");
            script.setCreateBy(String.valueOf(userId));
            script.setCreateTime(DateUtils.getNowDate());
            aidComicScriptService.save(script);
            return script;
        } else {
            // 将当前版本标记为历史版本
            LambdaUpdateWrapper<AidComicScript> updateWrapper = Wrappers.lambdaUpdate();
            updateWrapper.eq(AidComicScript::getId, currentScript.getId());
            updateWrapper.set(AidComicScript::getStatus, ScriptStatusEnum.HISTORY.getValue());
            updateWrapper.set(AidComicScript::getUpdateBy, String.valueOf(userId));
            updateWrapper.set(AidComicScript::getUpdateTime, DateUtils.getNowDate());
            aidComicScriptService.update(updateWrapper);

            // 创建新版本
            AidComicScript newScript = new AidComicScript();
            newScript.setProjectId(currentScript.getProjectId());
            newScript.setEpisodeId(currentScript.getEpisodeId());
            newScript.setUserId(userId);
            newScript.setOriginalText(originalText);
            newScript.setSimplifiedText(currentScript.getSimplifiedText());
            newScript.setComicVersion(currentScript.getComicVersion() + 1);
            newScript.setStatus(ScriptStatusEnum.IN_USE.getValue());
            newScript.setIsExtracted(currentScript.getIsExtracted());
            newScript.setDelFlag("0");
            newScript.setCreateBy(String.valueOf(userId));
            newScript.setCreateTime(DateUtils.getNowDate());
            aidComicScriptService.save(newScript);
            return newScript;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicScript autoSaveUserScript(UserScriptSaveRequest request, Long userId)
    {
        validateRequest(request, userId);

        // 查询该集当前使用中的剧本
        AidComicScript currentScript = getCurrentScript(request.getProjectId(), request.getEpisodeId(), userId);

        if (Objects.isNull(currentScript))
        {
            AidComicScript script = new AidComicScript();
            script.setProjectId(request.getProjectId());
            script.setEpisodeId(request.getEpisodeId());
            script.setUserId(userId);
            script.setOriginalText(request.getOriginalText());
            script.setComicVersion(1);
            script.setStatus(ScriptStatusEnum.IN_USE.getValue());
            script.setIsExtracted(0);
            script.setDelFlag("0");
            script.setCreateBy(String.valueOf(userId));
            script.setCreateTime(DateUtils.getNowDate());
            aidComicScriptService.save(script);
            return script;
        }
        LambdaUpdateWrapper<AidComicScript> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicScript::getId, currentScript.getId());
        updateWrapper.set(AidComicScript::getOriginalText, request.getOriginalText());
        updateWrapper.set(AidComicScript::getUpdateBy, String.valueOf(userId));
        updateWrapper.set(AidComicScript::getUpdateTime, DateUtils.getNowDate());
        aidComicScriptService.update(updateWrapper);

        currentScript.setOriginalText(request.getOriginalText());
        currentScript.setUpdateTime(DateUtils.getNowDate());
        return currentScript;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicScript uploadUserScript(UserScriptUploadRequest request, Long userId)
    {
        Long projectId = request.getProjectId();
        Long episodeId = request.getEpisodeId();
        MultipartFile file = request.getFile();

        AidComicProject project = getAndCheckProject(projectId, userId);

        if (Objects.isNull(file) || file.isEmpty()) {
            log.error("剧本上传失败：文件为空, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("请选择文件");
        }

        String ext = FileUtil.extName(file.getOriginalFilename());
        ext = StrUtil.isBlank(ext) ? "" : ext.toLowerCase();
        if (!ALLOWED_UPLOAD_EXTENSIONS.contains(ext)) {
            log.error("剧本上传失败：不支持的文件类型, fileName={}", file.getOriginalFilename());
            throw new ServiceException("格式不支持");
        }

        // 解析前按文件大小快速拒绝：超大文件不进堆内存（字数校验在解析后，无法拦截读取本身）
        if (file.getSize() > MAX_UPLOAD_FILE_BYTES) {
            log.error("剧本上传失败：文件过大, fileName={}, size={}B, max={}B",
                    file.getOriginalFilename(), file.getSize(), MAX_UPLOAD_FILE_BYTES);
            throw new ServiceException("文件过大");
        }

        String originalText = ScriptFileExtractor.extractText(file, ext);
        if (StrUtil.isBlank(originalText)) {
            log.error("剧本上传失败：解析后内容为空, fileName={}", file.getOriginalFilename());
            throw new ServiceException("内容不能为空");
        }

        assertOnlyLineBreakHtml(originalText);

        boolean isMovie = ProjectTypeEnum.MOVIE.getValue().equals(project.getProjectType());
        int wordCount = countWord(originalText);

        if (isMovie) {
            // 电影：整篇限制 1 万字，集数ID固定为 0
            if (wordCount > MAX_WORD_MOVIE) {
                log.error("剧本上传失败：电影超字数, wordCount={}, max={}", wordCount, MAX_WORD_MOVIE);
                throw new ServiceException("剧本超1万字");
            }
            return doSaveAsNewVersion(projectId, 0L, userId, originalText);
        }
        boolean singleEpisode = Objects.nonNull(episodeId) && episodeId != 0L;
        if (singleEpisode) {
            // 指定了剧集ID：按"单集上传"处理，单集限制 1 万字，直接落到该集
            checkEpisodeBelongsToProject(episodeId, projectId);
            if (wordCount > MAX_WORD_EPISODE_SINGLE) {
                log.error("剧本上传失败：剧集单集超字数, episodeId={}, wordCount={}, max={}",
                        episodeId, wordCount, MAX_WORD_EPISODE_SINGLE);
                throw new ServiceException("单集超1万字");
            }
            return doSaveAsNewVersion(projectId, episodeId, userId, originalText);
        }

        // 未指定剧集ID：按"整篇导入"处理，整篇限制 10 万字
        if (wordCount > MAX_WORD_SERIES) {
            log.error("剧本上传失败：剧集整篇超字数, wordCount={}, max={}", wordCount, MAX_WORD_SERIES);
            throw new ServiceException("剧本超10万");
        }
        // 剧集整篇导入统一走「剧本分集」链路：前端读取文件文本后调
        // /api/user/script/split/preview 预览分集，确认后调 /split/confirm 自动建集入库
        log.info("剧本上传：剧集整篇请走分集导入接口, projectId={}, userId={}", projectId, userId);
        throw new ServiceException("请用分集导入");
    }

    /**
     * 校验剧本原文：除换行符 {@code <br>} 外不允许出现任何 HTML 标签。
     * 先移除唯一允许的 {@code <br>} 标签，再用 {@link #HTML_TAG_PATTERN} 检测是否仍存在其它标签；
     * 命中则按规范先 log.error 再抛短文案异常。仅用于校验，不修改入库内容（{@code <br>} 原样保留）。
     *
     * @param text 剧本原文
     */
    private void assertOnlyLineBreakHtml(String text)
    {
        if (StrUtil.isBlank(text)) {
            return;
        }
        // 移除允许的换行标签后再检测，避免误伤 <br>
        String stripped = BR_TAG_PATTERN.matcher(text).replaceAll("");
        if (HTML_TAG_PATTERN.matcher(stripped).find()) {
            log.error("剧本上传失败：含非法 HTML 标签, 内容前 200 字={}", StrUtil.sub(text, 0, 200));
            throw new ServiceException("标签格式有误");
        }
    }

    /**
     * 统计剧本字数（中文按字计）。
     * 剔除换行标签 {@code <br>} 与所有空白字符后按字符数计，作为字数上限的判定依据。
     *
     * @param text 剧本原文
     * @return 字数
     */
    private int countWord(String text)
    {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        // 去掉换行标签与全部空白（换行/空格/制表符），仅统计有效文字
        String pure = BR_TAG_PATTERN.matcher(text).replaceAll("");
        pure = pure.replaceAll("\\s+", "");
        return pure.length();
    }

    @Override
    public int softDeleteUserScriptById(Long id, Long userId)
    {
        getAndCheckScript(id, userId);

        // 硬删除：项目主数据不保留回收站，校验归属后物理删除（剧本无 OSS 文件，无需清理）
        return aidComicScriptService.getBaseMapper().delete(Wrappers.<AidComicScript>lambdaQuery()
                .eq(AidComicScript::getId, id)) > 0 ? 1 : 0;
    }
}
