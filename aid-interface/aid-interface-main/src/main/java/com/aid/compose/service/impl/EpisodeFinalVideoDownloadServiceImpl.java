package com.aid.compose.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.compose.dto.EpisodeFinalVideoDownloadRequest;
import com.aid.compose.service.EpisodeFinalVideoDownloadService;
import com.aid.compose.util.AttachmentDownloadNames;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 成片流式下载实现：从对象存储拉输入流，8KB 缓冲直拷进 HTTP 响应，
 * 全程不落磁盘、不整片驻留内存，内存占用恒定与成片大小无关。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeFinalVideoDownloadServiceImpl implements EpisodeFinalVideoDownloadService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 拉流拷贝缓冲区大小（8KB） */
    private static final int COPY_BUFFER_SIZE = 8192;

    /** 成片源拉流连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** 成片源读取超时（毫秒）：长片允许较长读取窗口 */
    private static final int READ_TIMEOUT_MS = 300_000;

    /** 下载名中名称片段的最大长度 */
    private static final int NAME_MAX_LEN = 40;

    /** 电影的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    /** 电影下载名中的剧集标识 */
    private static final String MOVIE_EPISODE_LABEL = "完结";

    /** 剧集剪辑 Mapper（成片地址来源） */
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /** 项目服务（下载名取项目名称 + 归属校验） */
    private final IAidComicProjectService aidComicProjectService;

    /** 剧集服务（下载名取单集标题/集数） */
    private final IAidComicEpisodeService aidComicEpisodeService;

    /** 媒体 URL 解析器（相对路径 → 完整下载地址） */
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public void streamFinalVideo(EpisodeFinalVideoDownloadRequest request, HttpServletResponse response) {
        if (Objects.isNull(request) || (Objects.isNull(request.getEpisodeEditorId())
                && (Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId())))) {
            log.error("成片下载入参非法: episodeEditorId 与 projectId+episodeId 均为空");
            throw new ServiceException("参数有误");
        }
        Long userId = SecurityUtils.getUserId();
        AidEpisodeEditor editor = locateEditor(request, userId);
        // 最新成片优先：待审新片（重新导出的产物）优先于公开成片
        String videoUrl = StrUtil.isNotBlank(editor.getPendingVideoUrl())
                ? editor.getPendingVideoUrl() : editor.getFinalVideoUrl();
        if (StrUtil.isBlank(videoUrl)) {
            log.info("成片下载无可用成片, episodeEditorId={}, exportStatus={}",
                    editor.getId(), editor.getExportStatus());
            throw new ServiceException("暂无成片");
        }
        String fullUrl = mediaUrlResolver.toFullUrl(videoUrl);
        String timestamp = DateUtils.dateTimeNow();
        String ext = resolveExt(videoUrl);
        String projectName = loadProjectName(editor.getProjectId());
        AidComicEpisode episode = Objects.equals(editor.getEpisodeId(), MOVIE_EPISODE_ID)
                ? null : loadEpisode(editor.getProjectId(), editor.getEpisodeId());
        // ASCII 主文件名（filename= / download-filename）：任何客户端直接可用、绝不乱码；
        // 中文展示名经 filename*=utf-8'' 标准编码保留（支持 RFC 5987 的浏览器显示中文）
        String asciiName = buildAsciiDownloadName(editor.getProjectId(), editor.getEpisodeId(),
                projectName, episode, timestamp, ext);
        String displayName = buildDisplayDownloadName(editor.getProjectId(), editor.getEpisodeId(),
                projectName, episode, timestamp, ext);

        HttpURLConnection connection = null;
        try {
            // 先探通成片源再写响应头：源不可用时仍可回传 JSON 错误（响应未提交）
            connection = openConnection(fullUrl);
            response.setContentType("video/mp4");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0) {
                // 透传成片大小：浏览器可展示下载进度
                response.setContentLengthLong(contentLength);
            }
            AttachmentDownloadNames.writeAttachmentHeader(response, asciiName, displayName);
            try (InputStream in = connection.getInputStream();
                 OutputStream out = response.getOutputStream()) {
                copyStream(in, out);
                out.flush();
            }
            log.info("成片流式下载完成, episodeEditorId={}, bytes={}", editor.getId(), contentLength);
        } catch (IOException ex) {
            // 首字节已写出后发生的 IO 异常多为客户端取消下载：记录后结束，不再回写响应
            log.warn("成片流式下载中断, episodeEditorId={}, err={}", editor.getId(), ex.getMessage());
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            // 探源阶段失败（源文件缺失/超时）：响应未提交，可正常回传错误文案
            log.error("成片下载源不可用, episodeEditorId={}, url={}", editor.getId(), fullUrl, ex);
            throw new ServiceException("成片源异常");
        } finally {
            if (Objects.nonNull(connection)) {
                connection.disconnect();
            }
        }
    }

    /**
     * 定位剪辑记录：传 episodeEditorId 按主键查并校验归属；
     * 未传按「用户+项目+剧集」查最新一条（与导出/进度查询同口径）。
     * 查询字段精简：下载仅需成片地址与归属字段（新增使用字段时此处必须同步补充）。
     *
     * @param request 下载入参
     * @param userId  当前用户ID
     * @return 剪辑记录
     */
    private AidEpisodeEditor locateEditor(EpisodeFinalVideoDownloadRequest request, Long userId) {
        if (Objects.nonNull(request.getEpisodeEditorId())) {
            AidEpisodeEditor editor = aidEpisodeEditorMapper.selectOne(
                    Wrappers.<AidEpisodeEditor>lambdaQuery()
                            .select(AidEpisodeEditor::getId, AidEpisodeEditor::getUserId,
                                    AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                                    AidEpisodeEditor::getExportStatus, AidEpisodeEditor::getFinalVideoUrl,
                                    AidEpisodeEditor::getPendingVideoUrl, AidEpisodeEditor::getDelFlag)
                            .eq(AidEpisodeEditor::getId, request.getEpisodeEditorId())
                            .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                            .last("LIMIT 1"));
            if (Objects.isNull(editor)) {
                log.error("成片下载剪辑记录缺失, episodeEditorId={}", request.getEpisodeEditorId());
                throw new ServiceException("记录不存在");
            }
            if (!Objects.equals(editor.getUserId(), userId)) {
                log.error("成片下载越权, episodeEditorId={}, ownerId={}, userId={}",
                        editor.getId(), editor.getUserId(), userId);
                throw new ServiceException("无权操作");
            }
            return editor;
        }
        // 项目归属校验：防止按项目+剧集枚举他人成片
        AidComicProject project = aidComicProjectService.getById(request.getProjectId());
        if (Objects.isNull(project) || !Objects.equals(project.getUserId(), userId)) {
            log.error("成片下载项目缺失或越权, projectId={}, userId={}", request.getProjectId(), userId);
            throw new ServiceException("项目不存在");
        }
        AidEpisodeEditor editor = aidEpisodeEditorMapper.selectOne(
                Wrappers.<AidEpisodeEditor>lambdaQuery()
                        .select(AidEpisodeEditor::getId, AidEpisodeEditor::getUserId,
                                AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                                AidEpisodeEditor::getExportStatus, AidEpisodeEditor::getFinalVideoUrl,
                                AidEpisodeEditor::getPendingVideoUrl, AidEpisodeEditor::getDelFlag)
                        .eq(AidEpisodeEditor::getUserId, userId)
                        .eq(AidEpisodeEditor::getProjectId, request.getProjectId())
                        .eq(AidEpisodeEditor::getEpisodeId, request.getEpisodeId())
                        .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidEpisodeEditor::getId)
                        .last("LIMIT 1"));
        if (Objects.isNull(editor)) {
            log.info("成片下载未命中剪辑记录, projectId={}, episodeId={}, userId={}",
                    request.getProjectId(), request.getEpisodeId(), userId);
            throw new ServiceException("暂无成片");
        }
        return editor;
    }

    /**
     * 生成 ASCII 安全下载文件名：{项目标识}_{剧集标识}_video_{时间}.扩展名（电影剧集标识固定 final）。
     * 项目/剧集名含 ASCII 字母数字时保留转写，否则回退 project{id} / ep{no}。
     *
     * @param projectId   项目ID
     * @param episodeId   剧集ID（电影为 0）
     * @param projectName 项目名称（可空）
     * @param episode     剧集记录（电影/缺失为 null）
     * @param timestamp   时间标识
     * @param ext         扩展名（含点）
     * @return ASCII 安全下载文件名
     */
    private String buildAsciiDownloadName(Long projectId, Long episodeId, String projectName,
            AidComicEpisode episode, String timestamp, String ext) {
        String projectToken = AttachmentDownloadNames.asciiToken(projectName, "project" + projectId);
        String episodeToken;
        if (Objects.equals(episodeId, MOVIE_EPISODE_ID)) {
            episodeToken = "final";
        } else {
            String titleToken = Objects.isNull(episode)
                    ? "" : AttachmentDownloadNames.asciiToken(episode.getComicTitle(), "");
            if (StrUtil.isNotBlank(titleToken)) {
                episodeToken = truncateName(titleToken);
            } else if (Objects.nonNull(episode) && Objects.nonNull(episode.getEpisodeNo())) {
                episodeToken = "ep" + episode.getEpisodeNo();
            } else {
                episodeToken = "ep" + episodeId;
            }
        }
        return truncateName(projectToken) + "_" + episodeToken + "_video_" + timestamp + ext;
    }

    /**
     * 生成中文展示文件名：项目名_剧集标识_成片_时间.扩展名（电影的剧集标识固定「完结」）。
     * 经 filename*=utf-8'' 标准编码下发，支持 RFC 5987 的浏览器直接下载时显示中文名。
     *
     * @param projectId   项目ID
     * @param episodeId   剧集ID（电影为 0）
     * @param projectName 项目名称（可空）
     * @param episode     剧集记录（电影/缺失为 null）
     * @param timestamp   时间标识
     * @param ext         扩展名（含点）
     * @return 中文展示下载文件名
     */
    private String buildDisplayDownloadName(Long projectId, Long episodeId, String projectName,
            AidComicEpisode episode, String timestamp, String ext) {
        String displayProject = truncateName(sanitizeFileName(projectName));
        if (StrUtil.isBlank(displayProject)) {
            displayProject = "项目" + projectId;
        }
        String episodeLabel = resolveEpisodeLabel(episodeId, episode);
        return displayProject + "_" + episodeLabel + "_成片_" + timestamp + ext;
    }

    /**
     * 查项目名称（下载名用）。
     * 查询字段精简：下载名只需项目名称（新增使用字段时此处必须同步补充）。
     *
     * @param projectId 项目ID
     * @return 项目名称；缺失返回空串
     */
    private String loadProjectName(Long projectId) {
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getProjectName)
                        .eq(AidComicProject::getId, projectId));
        return Objects.isNull(project) ? "" : project.getProjectName();
    }

    /**
     * 查剧集记录（下载名用）。
     * 查询字段精简：下载名只需单集标题与集数（新增使用字段时此处必须同步补充）。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @return 剧集记录；缺失返回 null
     */
    private AidComicEpisode loadEpisode(Long projectId, Long episodeId) {
        return aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .select(AidComicEpisode::getId, AidComicEpisode::getComicTitle,
                                AidComicEpisode::getEpisodeNo)
                        .eq(AidComicEpisode::getId, episodeId)
                        .eq(AidComicEpisode::getProjectId, projectId));
    }

    /**
     * 解析中文展示名中的剧集标识：电影固定「完结」；剧集优先单集标题，无标题回退「第N集」。
     *
     * @param episodeId 剧集ID（电影为 0）
     * @param episode   剧集记录（电影/缺失为 null）
     * @return 剧集标识
     */
    private String resolveEpisodeLabel(Long episodeId, AidComicEpisode episode) {
        if (Objects.equals(episodeId, MOVIE_EPISODE_ID)) {
            return MOVIE_EPISODE_LABEL;
        }
        if (Objects.isNull(episode)) {
            return "剧集" + episodeId;
        }
        String title = truncateName(sanitizeFileName(episode.getComicTitle()));
        if (StrUtil.isNotBlank(title)) {
            return title;
        }
        return Objects.isNull(episode.getEpisodeNo()) ? "剧集" + episodeId : "第" + episode.getEpisodeNo() + "集";
    }

    /**
     * 打开成片下载连接（带超时；跟随同协议重定向），非 2xx 视为源异常。
     *
     * @param fullUrl 完整下载地址
     * @return 已连接的 HttpURLConnection
     * @throws IOException 连接失败
     */
    private HttpURLConnection openConnection(String fullUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(fullUrl).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("成片源响应异常: HTTP " + status);
        }
        return connection;
    }

    /**
     * 8KB 缓冲流拷贝：内存占用与成片大小无关。
     *
     * @param in  输入流
     * @param out 输出流
     * @throws IOException 读写异常
     */
    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * 清洗文件名非法字符。
     *
     * @param name 原始名称
     * @return 清洗后的名称
     */
    private String sanitizeFileName(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        return name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    /**
     * 名称超长截断。
     *
     * @param name 原始名称
     * @return 截断后的名称
     */
    private String truncateName(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        return name.length() > NAME_MAX_LEN ? name.substring(0, NAME_MAX_LEN) : name;
    }

    /**
     * 从成片地址解析扩展名（含点），识别不出按 .mp4。
     *
     * @param url 成片地址
     * @return 扩展名
     */
    private String resolveExt(String url) {
        if (StrUtil.isBlank(url)) {
            return ".mp4";
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot);
            if (ext.length() <= 6 && ext.substring(1).matches("[A-Za-z0-9]+")) {
                return ext.toLowerCase();
            }
        }
        return ".mp4";
    }
}
