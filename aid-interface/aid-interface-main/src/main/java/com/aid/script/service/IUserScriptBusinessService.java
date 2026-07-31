package com.aid.script.service;

import java.util.List;
import com.aid.aid.domain.AidComicScript;
import com.aid.script.dto.UserScriptQueryRequest;
import com.aid.script.dto.UserScriptSaveRequest;
import com.aid.script.dto.UserScriptUploadRequest;

/**
 * 用户剧本业务Service接口
 *
 * @author 视觉AID
 */
public interface IUserScriptBusinessService
{
    /**
     * 查询剧本列表
     */
    List<AidComicScript> selectUserScriptList(UserScriptQueryRequest request, Long userId);

    /**
     * 根据项目ID和剧集ID查询剧本详情
     * 优先返回当前使用中的剧本(status=1)，不存在则返回草稿版本(status=0)，都不存在则返回null
     */
    AidComicScript selectUserScriptByProject(Long projectId, Long episodeId, Long userId);

    /**
     * 保存剧本（无剧本则创建，有则版本+1）
     */
    AidComicScript saveUserScript(UserScriptSaveRequest request, Long userId);

    /**
     * 静默保存剧本（只更新内容，不更新版本号）
     */
    AidComicScript autoSaveUserScript(UserScriptSaveRequest request, Long userId);

    /**
     * 上传剧本文件并入库。
     *
     * @param request 上传请求（file 文件 + projectId + episodeId）
     * @param userId  当前用户ID
     * @return 入库后的剧本（电影/剧集单集返回保存的剧本记录）
     */
    AidComicScript uploadUserScript(UserScriptUploadRequest request, Long userId);

    /**
     * 删除剧本（带归属校验，物理删除）
     */
    int softDeleteUserScriptById(Long id, Long userId);
}
