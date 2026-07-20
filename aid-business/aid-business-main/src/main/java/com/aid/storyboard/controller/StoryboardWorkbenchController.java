package com.aid.storyboard.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.storyboard.dto.StoryboardCreateRequest;
import com.aid.storyboard.dto.StoryboardDeleteRequest;
import com.aid.storyboard.dto.StoryboardDetailRequest;
import com.aid.storyboard.dto.StoryboardListRequest;
import com.aid.storyboard.dto.StoryboardSaveRequest;
import com.aid.storyboard.dto.StoryboardSortRequest;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.storyboard.vo.StoryboardVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜工作台Controller
 * 提供给C端用户使用的分镜创作核心接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/storyboard")
public class StoryboardWorkbenchController extends BaseController {

    @Resource
    private IStoryboardWorkbenchService storyboardWorkbenchService;

    /**
     * 查询分镜列表
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody StoryboardListRequest request) {
        Long userId = SecurityUtils.getUserId();
        List<StoryboardVO> list = storyboardWorkbenchService.listStoryboards(request, userId);
        return success(list);
    }

    /**
     * 查询分镜详情
     */
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody StoryboardDetailRequest request) {
        Long userId = SecurityUtils.getUserId();
        StoryboardVO vo = storyboardWorkbenchService.getStoryboardDetail(request, userId);
        return success(vo);
    }

    /**
     * 新增分镜
     */
    @PostMapping("/create")
    public AjaxResult create(@Valid @RequestBody StoryboardCreateRequest request) {
        Long userId = SecurityUtils.getUserId();
        StoryboardVO vo = storyboardWorkbenchService.createStoryboard(request, userId);
        return success(vo);
    }

    /**
     * 删除分镜(软删除，单删 / 批删合并)
     * 入参 {@code ids}：传一个即单删，传多个即批删。严格模式——任一分镜不存在 / 已删除 /
     * 不归属当前用户，则整批拒绝。返回实际删除条数（放在 data 中）。
     */
    @PostMapping("/delete")
    public AjaxResult delete(@Valid @RequestBody StoryboardDeleteRequest request) {
        Long userId = SecurityUtils.getUserId();
        int deletedCount = storyboardWorkbenchService.deleteStoryboard(request, userId);
        return AjaxResult.success("删除成功", deletedCount);
    }

    /**
     * 批量调整分镜排序
     */
    @PostMapping("/sort")
    public AjaxResult sort(@Valid @RequestBody StoryboardSortRequest request) {
        Long userId = SecurityUtils.getUserId();
        storyboardWorkbenchService.sortStoryboards(request, userId);
        return success("排序成功");
    }

    /**
     * 保存/更新分镜图纸配置
     */
    @PostMapping("/update")
    public AjaxResult update(@Valid @RequestBody StoryboardSaveRequest request) {
        Long userId = SecurityUtils.getUserId();
        storyboardWorkbenchService.saveStoryboard(request, userId);
        return success("保存成功");
    }
}
