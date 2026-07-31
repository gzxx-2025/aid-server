package com.aid.asset.audio.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.asset.audio.dto.ReferenceAudioDeleteRequest;
import com.aid.asset.audio.dto.ReferenceAudioListRequest;
import com.aid.asset.audio.dto.ReferenceAudioRenameRequest;
import com.aid.asset.audio.dto.ReferenceAudioUploadRequest;
import com.aid.asset.audio.service.IReferenceAudioBusinessService;
import com.aid.asset.audio.vo.ReferenceAudioVO;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端参考音频 Controller。
 * 参考音频的第三条来源：用户上传的音频文件，按用户 + 项目隔离。
 * 文件本身走 {@code /api/user/oss/upload} 上传拿相对路径，本控制器只做登记 / 浏览 / 重命名 / 删除。
 * 登记后的音频可绑定到角色（{@code /api/user/asset/rps/voice/bind}）作为隐式来源，
 * 也可在出片时通过 {@code referenceAudioIds} 显式选用。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/reference-audio")
public class ReferenceAudioController extends BaseController {

    @Resource
    private IReferenceAudioBusinessService referenceAudioBusinessService;

    /**
     * 登记一条上传的参考音频：校验本站路径 / 格式 / 时长 / 配额后落库。
     */
    @PostMapping("/upload")
    public AjaxResult upload(@Valid @RequestBody ReferenceAudioUploadRequest request) {
        Long userId = SecurityUtils.getUserId();
        ReferenceAudioVO vo = referenceAudioBusinessService.upload(request, userId);
        return success(vo);
    }

    /**
     * 分页列表：硬过滤当前用户 + 项目 + del_flag=0。
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody ReferenceAudioListRequest request) {
        Long userId = SecurityUtils.getUserId();
        IPage<ReferenceAudioVO> page = referenceAudioBusinessService.list(request, userId);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 重命名：仅限本人上传的参考音频。
     */
    @PostMapping("/rename")
    public AjaxResult rename(@Valid @RequestBody ReferenceAudioRenameRequest request) {
        Long userId = SecurityUtils.getUserId();
        referenceAudioBusinessService.rename(request, userId);
        return success();
    }

    /**
     * 软删除：仅限本人上传的参考音频，同时清空引用它的角色绑定冗余列。
     */
    @PostMapping("/delete")
    public AjaxResult delete(@Valid @RequestBody ReferenceAudioDeleteRequest request) {
        Long userId = SecurityUtils.getUserId();
        referenceAudioBusinessService.delete(request, userId);
        return success();
    }
}
