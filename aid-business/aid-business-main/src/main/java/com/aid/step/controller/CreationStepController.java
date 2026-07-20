package com.aid.step.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.step.dto.StepAdvanceRequest;
import com.aid.step.dto.StepStatusRequest;
import com.aid.step.service.ICreationStepService;
import com.aid.step.vo.StepStatusVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 创作流水线步骤Controller。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/step")
public class CreationStepController extends BaseController {

    @Resource
    private ICreationStepService creationStepService;

    /**
     * 查询当前步骤状态(前端渲染左侧导航栏)。
     */
    @PostMapping("/status")
    public AjaxResult status(@Valid @RequestBody StepStatusRequest request) {
        Long userId = SecurityUtils.getUserId();
        StepStatusVO vo = creationStepService.getStepStatus(
                request.getProjectId(), request.getEpisodeId(), userId);
        return success(vo);
    }

    /**
     * 手动推进步骤(前端点击"下一步"按钮)。
     */
    @PostMapping("/advance")
    public AjaxResult advance(@Valid @RequestBody StepAdvanceRequest request) {
        Long userId = SecurityUtils.getUserId();
        creationStepService.tryAdvanceStep(
                request.getProjectId(), request.getEpisodeId(), userId, request.getCompletedStep());
        return success();
    }
}
