package com.aid.episode.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.github.pagehelper.PageInfo;
import com.aid.episode.dto.UserEpisodeCreateRequest;
import com.aid.episode.dto.UserEpisodeDeleteRequest;
import com.aid.episode.dto.UserEpisodeDetailRequest;
import com.aid.episode.dto.UserEpisodeQueryRequest;
import com.aid.episode.dto.UserEpisodeSubmitAuditRequest;
import com.aid.episode.dto.UserEpisodeUpdateRequest;
import com.aid.episode.service.IUserEpisodeBusinessService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户剧集Controller
 * 提供给C端用户使用的剧集CRUD接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/episode")
public class UserEpisodeController extends BaseController
{
    @Resource
    private IUserEpisodeBusinessService userEpisodeBusinessService;

    /**
     * 查询剧集列表。
     * 出参在剧集字段基础上附加每集最新成片信息（episodeEditorId / finalVideoUrl / exportStatus），
     * 供前端在列表直接展示成片与「已合成」角标。
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody UserEpisodeQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        // 分页由 Service 在归属校验后紧邻列表查询开启（钳制 pageSize 上限），此处不再 startPage
        List<AidComicEpisode> list = userEpisodeBusinessService.selectUserEpisodeList(request, userId);
        // 先取分页 total（PageHelper 的 Page 对象），再转 VO 附加项目配置与成片信息
        long total = new PageInfo<>(list).getTotal();
        AjaxResult result = AjaxResult.success();
        result.put("total", total);
        result.put("data", userEpisodeBusinessService.convertToVOList(list));
        return result;
    }

    /**
     * 获取剧集详情。
     * 出参附加该集最新成片信息（episodeEditorId / finalVideoUrl / exportStatus）。
     */
    @PostMapping("/detail")
    public AjaxResult getInfo(@Valid @RequestBody UserEpisodeDetailRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicEpisode episode = userEpisodeBusinessService.selectUserEpisodeById(request.getId(), userId);
        if (episode == null) {
            return error("剧集不存在或无权限访问");
        }
        return success(userEpisodeBusinessService.convertToVO(episode));
    }

    /**
     * 创建剧集
     */
    @PostMapping("/create")
    public AjaxResult add(@Valid @RequestBody UserEpisodeCreateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicEpisode episode = userEpisodeBusinessService.insertUserEpisode(request, userId);
        return success(userEpisodeBusinessService.convertToVO(episode));
    }

    /**
     * 修改剧集
     */
    @PostMapping("/update")
    public AjaxResult edit(@Valid @RequestBody UserEpisodeUpdateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicEpisode episode = userEpisodeBusinessService.updateUserEpisode(request, userId);
        return success(userEpisodeBusinessService.convertToVO(episode));
    }

    /**
     * 删除剧集
     * 级联删除该集下的分镜、成片记录等子数据并清理对应OSS文件，删除后不可恢复。
     */
    @PostMapping("/delete")
    public AjaxResult remove(@Valid @RequestBody UserEpisodeDeleteRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        int result = userEpisodeBusinessService.softDeleteUserEpisodeById(request.getId(), userId);
        return toAjax(result);
    }

    /**
     * 提交剧集审核
     * 除「审核中」外的状态均可提交（「审核通过」的剧集仅在重新导出产生待审新片时可再次提审），
     * 提交后剧集状态变为「审核中」。
     *
     * @param request 提交审核请求（剧集ID）
     * @return 提交审核成功提示
     */
    @PostMapping("/submit-audit")
    public AjaxResult submitAudit(@Valid @RequestBody UserEpisodeSubmitAuditRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            userEpisodeBusinessService.submitAudit(request.getId(), userId);
            return success("提交审核成功");
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }
}
