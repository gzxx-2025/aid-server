package com.aid.compose.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.compose.dto.EpisodeSegmentVideoItem;
import com.aid.compose.dto.EpisodeSegmentVideosRequest;
import com.aid.compose.dto.EpisodeSegmentVideosResult;
import com.aid.compose.dto.EpisodeSegmentZipDownloadRequest;
import com.aid.compose.service.EpisodeSegmentExportService;
import com.aid.compose.service.EpisodeSegmentZipService;
import com.aid.compose.util.AttachmentDownloadNames;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 分段素材流式打包下载实现。
 * ZipOutputStream 直接包在 HTTP 响应输出流上，素材逐个「从对象存储拉输入流 → 8KB 缓冲区
 * 拷贝进 zip 条目」，全程无临时文件、无整包缓存，服务端内存占用恒定（仅缓冲区）；
 * 媒体文件（mp4/png/mp3）本身已压缩，zip 走不压缩档位（Deflater.NO_COMPRESSION）避免无效 CPU 消耗。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeSegmentZipServiceImpl implements EpisodeSegmentZipService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 拉流拷贝缓冲区大小（8KB）：流式打包的内存占用上限即少量此缓冲区 */
    private static final int COPY_BUFFER_SIZE = 8192;

    /** 素材拉流连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** 素材拉流读取超时（毫秒）：单个大视频允许较长读取窗口 */
    private static final int READ_TIMEOUT_MS = 120_000;

    /** 段目录名中标题的最大长度（超长截断，防止路径过长） */
    private static final int TITLE_MAX_LEN = 40;

    /** 电影的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    /** 电影下载名中的剧集标识 */
    private static final String MOVIE_EPISODE_LABEL = "完结";

    /** 分段素材清单服务（复用查询与越权校验） */
    private final EpisodeSegmentExportService episodeSegmentExportService;

    /** 项目服务（下载名取项目名称） */
    private final IAidComicProjectService aidComicProjectService;

    /** 剧集服务（下载名取单集标题/集数） */
    private final IAidComicEpisodeService aidComicEpisodeService;

    /** 分镜服务（补查最终分镜图ID） */
    private final IAidStoryboardService aidStoryboardService;

    /** 抽卡记录 Mapper（分镜图 URL） */
    private final AidGenRecordMapper aidGenRecordMapper;

    /** 媒体 URL 解析器（相对路径 → 完整下载地址） */
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public void streamSegmentsZip(EpisodeSegmentZipDownloadRequest request, HttpServletResponse response) {
        if (Objects.isNull(request) || Objects.isNull(request.getProjectId())
                || Objects.isNull(request.getEpisodeId())) {
            log.error("分段素材打包入参非法: projectId/episodeId 为空");
            throw new ServiceException("参数有误");
        }
        boolean withImages = !Boolean.FALSE.equals(request.getIncludeImages());
        boolean withVideos = !Boolean.FALSE.equals(request.getIncludeVideos());
        boolean withAudios = !Boolean.FALSE.equals(request.getIncludeAudios());
        boolean withSubtitles = !Boolean.FALSE.equals(request.getIncludeSubtitles());

        // 复用清单服务：内部完成项目归属校验（越权直接抛错），返回视频/配音/字幕清单
        EpisodeSegmentVideosRequest listRequest = new EpisodeSegmentVideosRequest();
        listRequest.setProjectId(request.getProjectId());
        listRequest.setEpisodeId(request.getEpisodeId());
        EpisodeSegmentVideosResult listResult = episodeSegmentExportService.listSegmentVideos(listRequest);
        if (Objects.isNull(listResult) || CollectionUtil.isEmpty(listResult.getItems())) {
            log.info("分段素材打包无可导出内容, projectId={}, episodeId={}",
                    request.getProjectId(), request.getEpisodeId());
            throw new ServiceException("暂无可导出素材");
        }
        // 分镜图清单：清单服务不含图片，按 final_image_id 补查一次
        Map<Long, String> imageUrlBySb = withImages
                ? loadFinalImageUrls(request.getProjectId(), request.getEpisodeId()) : Map.of();

        String timestamp = DateUtils.dateTimeNow();
        String projectName = loadProjectName(request.getProjectId());
        AidComicEpisode episode = Objects.equals(request.getEpisodeId(), MOVIE_EPISODE_ID)
                ? null : loadEpisode(request.getProjectId(), request.getEpisodeId());
        // ASCII 主文件名（filename= / download-filename）：任何客户端直接可用、绝不乱码；
        // 中文展示名经 filename*=utf-8'' 标准编码保留（支持 RFC 5987 的浏览器显示中文）
        String asciiZipName = buildAsciiZipName(request.getProjectId(), request.getEpisodeId(),
                projectName, episode, timestamp);
        String displayZipName = buildDisplayZipName(request.getProjectId(), request.getEpisodeId(),
                projectName, episode, timestamp);
        List<String> failedFiles = new ArrayList<>();
        int fileCount = 0;
        try {
            // 响应头必须在首字节写出前设置；此后发生的异常只能中断流，无法再回传 JSON 错误
            response.setContentType("application/zip");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            AttachmentDownloadNames.writeAttachmentHeader(response, asciiZipName, displayZipName);

            try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
                // 媒体文件已压缩，zip 不再压缩：纯拷贝直通，CPU 开销可忽略且保持流式
                zos.setLevel(Deflater.NO_COMPRESSION);
                Set<String> usedNames = new HashSet<>();
                int index = 0;
                for (EpisodeSegmentVideoItem item : listResult.getItems()) {
                    index++;
                    String dir = buildSegmentDir(index, item.getTitle());
                    if (withImages) {
                        String imageUrl = imageUrlBySb.get(item.getStoryboardId());
                        fileCount += putRemoteFile(zos, usedNames, dir + "分镜图" + resolveExt(imageUrl, ".png"),
                                imageUrl, failedFiles) ? 1 : 0;
                    }
                    if (withVideos) {
                        // 恒用分镜视频（final_video_id 指向的配音前原视频），不用配音/对口型后的视频
                        String videoUrl = item.getVideoUrl();
                        fileCount += putRemoteFile(zos, usedNames, dir + "视频" + resolveExt(videoUrl, ".mp4"),
                                videoUrl, failedFiles) ? 1 : 0;
                    }
                    if (withAudios) {
                        fileCount += putRemoteFile(zos, usedNames, dir + "配音" + resolveExt(item.getAudioUrl(), ".mp3"),
                                item.getAudioUrl(), failedFiles) ? 1 : 0;
                    }
                    if (withSubtitles && StrUtil.isNotBlank(item.getSubtitle())) {
                        putTextFile(zos, usedNames, dir + "字幕.txt", item.getSubtitle());
                        fileCount++;
                    }
                }
                // 导出说明（清单 + 失败明细）放包尾，不影响前面素材的流式写出
                putTextFile(zos, usedNames, "导出说明.txt",
                        buildManifest(request, listResult.getItems().size(), fileCount, failedFiles));
                zos.finish();
            }
            log.info("分段素材打包完成, projectId={}, episodeId={}, files={}, failed={}",
                    request.getProjectId(), request.getEpisodeId(), fileCount, failedFiles.size());
        } catch (IOException ex) {
            // 多为客户端取消下载导致的管道断开：记录后结束，无法也无需再回写响应
            log.warn("分段素材打包流中断, projectId={}, episodeId={}, err={}",
                    request.getProjectId(), request.getEpisodeId(), ex.getMessage());
        }
    }

    /**
     * 生成 ASCII 安全下载文件名：{项目标识}_{剧集标识}_{时间}.zip（电影剧集标识固定 final）。
     * 项目/剧集名含 ASCII 字母数字时保留转写，否则回退 project{id} / ep{no}。
     *
     * @param projectId   项目ID
     * @param episodeId   剧集ID（电影为 0）
     * @param projectName 项目名称（可空）
     * @param episode     剧集记录（电影/缺失为 null）
     * @param timestamp   时间标识
     * @return ASCII 安全 zip 文件名
     */
    private String buildAsciiZipName(Long projectId, Long episodeId, String projectName,
            AidComicEpisode episode, String timestamp) {
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
        return truncateName(projectToken) + "_" + episodeToken + "_" + timestamp + ".zip";
    }

    /**
     * 生成中文展示文件名：项目名_剧集标识_时间标识.zip（电影的剧集标识固定「完结」）。
     * 经 filename*=utf-8'' 标准编码下发，支持 RFC 5987 的浏览器直接下载时显示中文名。
     *
     * @param projectId   项目ID
     * @param episodeId   剧集ID（电影为 0）
     * @param projectName 项目名称（可空）
     * @param episode     剧集记录（电影/缺失为 null）
     * @param timestamp   时间标识
     * @return 中文展示 zip 文件名
     */
    private String buildDisplayZipName(Long projectId, Long episodeId, String projectName,
            AidComicEpisode episode, String timestamp) {
        String displayProject = truncateName(sanitizeFileName(projectName));
        if (StrUtil.isBlank(displayProject)) {
            displayProject = "项目" + projectId;
        }
        String episodeLabel = resolveEpisodeLabel(episodeId, episode);
        return displayProject + "_" + episodeLabel + "_" + timestamp + ".zip";
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
     * 解析中文展示名中的剧集标识：电影固定「完结」；剧集优先取单集标题，无标题回退「第N集」。
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
     * 名称超长截断（复用段目录标题上限，防止下载名过长）。
     *
     * @param name 原始名称
     * @return 截断后的名称
     */
    private String truncateName(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        return name.length() > TITLE_MAX_LEN ? name.substring(0, TITLE_MAX_LEN) : name;
    }

    /**
     * 补查各分镜「最终分镜图」URL：aid_storyboard.final_image_id → aid_gen_record.file_url。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @return 分镜ID → 分镜图相对路径
     */
    private Map<Long, String> loadFinalImageUrls(Long projectId, Long episodeId) {
        Long userId = SecurityUtils.getUserId();
        // 查询字段精简：打包只需分镜ID与最终图ID（新增使用字段时此处必须同步补充）
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getFinalImageId)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidStoryboard::getFinalImageId));
        Map<Long, String> result = new HashMap<>();
        if (CollectionUtil.isEmpty(storyboards)) {
            return result;
        }
        Set<Long> imageIds = new LinkedHashSet<>();
        for (AidStoryboard sb : storyboards) {
            imageIds.add(sb.getFinalImageId());
        }
        // 查询字段精简：打包只需记录ID与地址（新增使用字段时此处必须同步补充）
        List<AidGenRecord> records = aidGenRecordMapper.selectList(
                new LambdaQueryWrapper<AidGenRecord>()
                        .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                        .in(AidGenRecord::getId, imageIds)
                        .eq(AidGenRecord::getUserId, userId)
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidGenRecord::getFileUrl));
        Map<Long, String> urlByRecordId = new HashMap<>();
        for (AidGenRecord record : records) {
            urlByRecordId.put(record.getId(), record.getFileUrl());
        }
        for (AidStoryboard sb : storyboards) {
            String url = urlByRecordId.get(sb.getFinalImageId());
            if (StrUtil.isNotBlank(url)) {
                result.put(sb.getId(), url);
            }
        }
        return result;
    }

    /**
     * 写入一个远程素材条目：从对象存储拉输入流，边读边写进 zip（8KB 缓冲拷贝）。
     * 单个素材失败（源文件丢失/网络超时）只记失败明细，不中断整包。
     *
     * @param zos         zip 输出流
     * @param usedNames   已占用条目名（重名去重）
     * @param entryName   条目名（含段目录前缀）
     * @param url         素材地址（相对路径或完整 URL）
     * @param failedFiles 失败明细收集
     * @return 是否成功写入
     * @throws IOException 响应流本身断开时抛出（中断整包）
     */
    private boolean putRemoteFile(ZipOutputStream zos, Set<String> usedNames, String entryName,
            String url, List<String> failedFiles) throws IOException {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        String fullUrl = mediaUrlResolver.toFullUrl(url);
        String uniqueName = uniqueEntryName(usedNames, entryName);
        HttpURLConnection connection = null;
        try {
            connection = openConnection(fullUrl);
            try (InputStream in = connection.getInputStream()) {
                zos.putNextEntry(new ZipEntry(uniqueName));
                copyStream(in, zos);
                zos.closeEntry();
                return true;
            }
        } catch (IOException ex) {
            // 区分「素材源失败」与「响应流断开」：条目还没开始写响应时可安全跳过；
            // 已写一半时 zip 结构已脏，只能整体中断（向上抛）
            if (isResponseBroken(ex)) {
                throw ex;
            }
            log.warn("分段素材打包单文件拉取失败(跳过), entry={}, url={}, err={}",
                    uniqueName, fullUrl, ex.getMessage());
            failedFiles.add(uniqueName);
            usedNames.remove(uniqueName);
            return false;
        } finally {
            if (Objects.nonNull(connection)) {
                connection.disconnect();
            }
        }
    }

    /**
     * 写入一个文本条目（字幕/导出说明）。
     *
     * @param zos       zip 输出流
     * @param usedNames 已占用条目名
     * @param entryName 条目名
     * @param content   文本内容
     * @throws IOException 响应流断开
     */
    private void putTextFile(ZipOutputStream zos, Set<String> usedNames, String entryName, String content)
            throws IOException {
        zos.putNextEntry(new ZipEntry(uniqueEntryName(usedNames, entryName)));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 打开素材下载连接（带超时；跟随同协议重定向）。
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
            throw new IOException("素材源响应异常: HTTP " + status);
        }
        return connection;
    }

    /**
     * 8KB 缓冲流拷贝：内存占用与文件大小无关。
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
     * 判断 IOException 是否来自「HTTP 响应流断开」（客户端取消下载/网络中断）。
     * 素材源读取失败的异常类型多为 SocketTimeoutException/FileNotFoundException 等，
     * 这里按异常文案特征粗判，误判方向宁可整包中断也不产出脏 zip。
     *
     * @param ex IO 异常
     * @return true=响应流断开
     */
    private boolean isResponseBroken(IOException ex) {
        String message = StrUtil.blankToDefault(ex.getMessage(), "").toLowerCase();
        return message.contains("broken pipe") || message.contains("connection reset by peer")
                || message.contains("connection abort") || ex.getClass().getName().contains("ClientAbort");
    }

    /**
     * 生成段目录名：分镜01_标题/（标题去非法字符、超长截断；无标题只留序号）。
     *
     * @param index 段序号（1 基）
     * @param title 分镜标题
     * @return 目录前缀（以 / 结尾）
     */
    private String buildSegmentDir(int index, String title) {
        String safeTitle = sanitizeFileName(title);
        String prefix = String.format("分镜%02d", index);
        if (StrUtil.isBlank(safeTitle)) {
            return prefix + "/";
        }
        if (safeTitle.length() > TITLE_MAX_LEN) {
            safeTitle = safeTitle.substring(0, TITLE_MAX_LEN);
        }
        return prefix + "_" + safeTitle + "/";
    }

    /**
     * 清洗文件名非法字符（Windows/Unix 保留字符与控制符）。
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
     * 从 URL path 解析文件扩展名（含点）；识别不出用默认值。
     *
     * @param url        素材地址
     * @param defaultExt 默认扩展名（如 ".mp4"）
     * @return 扩展名
     */
    private String resolveExt(String url, String defaultExt) {
        if (StrUtil.isBlank(url)) {
            return defaultExt;
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
            // 扩展名限定为常规长度的字母数字，防止把路径尾巴误当扩展名
            if (ext.length() <= 6 && ext.substring(1).matches("[A-Za-z0-9]+")) {
                return ext.toLowerCase();
            }
        }
        return defaultExt;
    }

    /**
     * 条目名去重：重名追加 (2)、(3)…。
     *
     * @param usedNames 已占用条目名
     * @param entryName 期望条目名
     * @return 去重后的条目名
     */
    private String uniqueEntryName(Set<String> usedNames, String entryName) {
        String candidate = entryName;
        int seq = 2;
        while (!usedNames.add(candidate)) {
            int dot = entryName.lastIndexOf('.');
            candidate = dot > 0
                    ? entryName.substring(0, dot) + "(" + seq + ")" + entryName.substring(dot)
                    : entryName + "(" + seq + ")";
            seq++;
        }
        return candidate;
    }

    /**
     * 生成导出说明文本（时间/范围/统计/失败明细）。
     *
     * @param request     入参
     * @param segments    段数
     * @param fileCount   成功文件数
     * @param failedFiles 失败明细
     * @return 说明文本
     */
    private String buildManifest(EpisodeSegmentZipDownloadRequest request, int segments,
            int fileCount, List<String> failedFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("导出时间: ").append(DateUtils.getTime()).append('\n');
        sb.append("项目ID: ").append(request.getProjectId())
                .append("  剧集ID: ").append(request.getEpisodeId()).append('\n');
        sb.append("分镜段数: ").append(segments).append('\n');
        sb.append("成功文件数: ").append(fileCount).append('\n');
        if (CollectionUtil.isEmpty(failedFiles)) {
            sb.append("全部素材导出成功。\n");
        } else {
            sb.append("以下素材拉取失败（可稍后重试单独下载）:\n");
            for (String failed : failedFiles) {
                sb.append("  - ").append(failed).append('\n');
            }
        }
        return sb.toString();
    }
}
