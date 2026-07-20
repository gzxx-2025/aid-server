package com.aid.storyboard.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.storyboard.dto.DeleteGenRecordRequest;
import com.aid.storyboard.dto.GenRecordDetailRequest;
import com.aid.storyboard.dto.GenRecordListRequest;
import com.aid.storyboard.dto.StoryboardGenRecordListRequest;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.storyboard.vo.GenRecordVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 生成记录Controller
 * 提供给C端用户使用的生成记录查询接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/storyboard/record")
public class GenRecordController extends BaseController {

    @Resource
    private IStoryboardWorkbenchService storyboardWorkbenchService;

    /**
     * 查询生成记录列表
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody GenRecordListRequest request) {
        Long userId = SecurityUtils.getUserId();
        List<GenRecordVO> list = storyboardWorkbenchService.listGenRecords(request, userId);
        return success(list);
    }

    /**
     * 查询生成记录详情
     */
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody GenRecordDetailRequest request) {
        Long userId = SecurityUtils.getUserId();
        GenRecordVO vo = storyboardWorkbenchService.getGenRecordDetail(request, userId);
        return success(vo);
    }

    /**
     * 按"项目 + 分镜 + 类型"查询分镜下的生成记录列表。
     */
    @PostMapping("/list-by-storyboard")
    public AjaxResult listByStoryboard(@Valid @RequestBody StoryboardGenRecordListRequest request) {
        Long userId = SecurityUtils.getUserId();
        List<GenRecordVO> list = storyboardWorkbenchService.listGenRecordsByStoryboard(request, userId);
        return success(list);
    }

    /**
     * 物理删除分镜生成记录（分镜图 / 分镜视频抽卡记录）。
     *
     * @param request 删除请求（storyboardId + recordId）
     * @return 仅提示信息（删除成功）
     */
    @PostMapping("/delete")
    public AjaxResult delete(@Valid @RequestBody DeleteGenRecordRequest request) {
        Long userId = SecurityUtils.getUserId();
        storyboardWorkbenchService.deleteGenRecord(request, userId);
        return success("删除成功");
    }
}
