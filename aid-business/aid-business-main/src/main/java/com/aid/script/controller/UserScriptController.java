package com.aid.script.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.aid.domain.AidComicScript;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.utils.SecurityUtils;
import com.github.pagehelper.PageInfo;
import com.aid.script.dto.ScriptSplitPreviewRequest;
import com.aid.script.dto.UserScriptDeleteRequest;
import com.aid.script.dto.UserScriptDetailByProjectRequest;
import com.aid.script.dto.UserScriptQueryRequest;
import com.aid.script.dto.UserScriptSaveRequest;
import com.aid.script.dto.UserScriptUploadRequest;
import com.aid.script.service.IScriptSplitService;
import com.aid.script.service.IUserScriptBusinessService;
import com.aid.script.vo.ScriptSplitConfirmVO;
import com.aid.script.vo.ScriptSplitPreviewVO;
import com.aid.script.vo.UserScriptVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户剧本 Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/script")
public class UserScriptController extends BaseController
{
    @Resource
    private IUserScriptBusinessService userScriptBusinessService;

    /** 剧本自动分集服务 */
    @Resource
    private IScriptSplitService scriptSplitService;

    /**
     * 查询剧本列表
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody UserScriptQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        // 分页由 Service 在归属校验后紧邻列表查询开启（钳制 pageSize 上限），此处不再 startPage
        List<AidComicScript> list = userScriptBusinessService.selectUserScriptList(request, userId);
        // 出参转 VO：剥离 userId/delFlag 等内部字段（total 需在转换前从分页代理取）
        AjaxResult result = AjaxResult.success();
        result.put("total", new PageInfo<>(list).getTotal());
        result.put("data", list.stream().map(this::convertToVO).toList());
        return result;
    }

    /**
     * 根据项目ID和剧集ID获取剧本详情
     * 优先返回当前使用中的剧本，不存在则返回草稿版本，都不存在则提示创建
     */
    @PostMapping("/detailByProject")
    public AjaxResult getInfoByProject(@Valid @RequestBody UserScriptDetailByProjectRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicScript script = userScriptBusinessService.selectUserScriptByProject(
                request.getProjectId(), request.getEpisodeId(), userId);
        if (script == null) {
            return error("剧本不存在，请先创建剧本");
        }
        return success(convertToVO(script));
    }

    /**
     * 保存剧本（无剧本则创建，有则版本+1）
     */
    @PostMapping("/save")
    public AjaxResult save(@Valid @RequestBody UserScriptSaveRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicScript script = userScriptBusinessService.saveUserScript(request, userId);
        return success(convertToVO(script));
    }

    /**
     * 静默保存剧本（只更新内容，不更新版本号）
     */
    @PostMapping("/autoSave")
    public AjaxResult autoSave(@Valid @RequestBody UserScriptSaveRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicScript script = userScriptBusinessService.autoSaveUserScript(request, userId);
        return success(convertToVO(script));
    }

    /**
     * 上传剧本文件并入库。
     *
     * @param request 上传请求（file 文件 + projectId + episodeId）
     * @return 入库后的剧本详情
     */
    @PostMapping("/upload")
    public AjaxResult upload(@Valid UserScriptUploadRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicScript script = userScriptBusinessService.uploadUserScript(request, userId);
        return success(convertToVO(script));
    }

    /**
     * 剧本分集预览（只解析不入库）。
     * 整篇剧集剧本按分集词切分，返回共多少集与每集「标题 + 描述（前20字，超长省略号）+ 字数」，
     * 供用户确认分集是否正确。分集词样例含序数（如「第一集」）自动泛化匹配「第N集」；
     * 不含序数（如「===分集===」）按行首字面量匹配。仅剧集类型项目可用。
     *
     * @param request 预览入参（projectId + scriptText + 可选 episodeKeyword）
     * @return 分集预览（totalEpisodes + items）
     */
    @PostMapping("/split/preview")
    public AjaxResult splitPreview(@Valid @RequestBody ScriptSplitPreviewRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        ScriptSplitPreviewVO result = scriptSplitService.previewSplit(request, userId);
        return success(result);
    }

    /**
     * 剧本分集确认入库。
     * 用户确认预览无误后调用：按与预览完全相同的解析规则切分（须传相同文本与分集词），
     * 逐集自动创建剧集（标题/描述来自解析结果，集号在项目已有集数上顺延）并写入该集剧本
     * （版本1、使用中）。重复调用会继续顺延追加新集，误导入的集可用剧集删除接口清理。
     *
     * @param request 确认入参（与预览同构）
     * @return 入库结果（每集的 episodeId/episodeNo/title/scriptId）
     */
    @PostMapping("/split/confirm")
    public AjaxResult splitConfirm(@Valid @RequestBody ScriptSplitPreviewRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        ScriptSplitConfirmVO result = scriptSplitService.confirmSplit(request, userId);
        return success(result);
    }

    /**
     * 删除剧本（物理删除，删除后不可恢复）
     */
    @PostMapping("/delete")
    public AjaxResult remove(@Valid @RequestBody UserScriptDeleteRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        int result = userScriptBusinessService.softDeleteUserScriptById(request.getId(), userId);
        return toAjax(result);
    }

    private UserScriptVO convertToVO(AidComicScript script)
    {
        return UserScriptVO.builder()
                .id(script.getId())
                .projectId(script.getProjectId())
                .episodeId(script.getEpisodeId())
                .originalText(script.getOriginalText())
                .simplifiedText(script.getSimplifiedText())
                .isExtracted(script.getIsExtracted())
                .comicVersion(script.getComicVersion())
                .status(script.getStatus())
                .createTime(script.getCreateTime())
                .updateTime(script.getUpdateTime())
                .build();
    }
}
