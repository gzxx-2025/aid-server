package com.aid.asset.audio.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.asset.audio.dto.AudioAssetDeleteRequest;
import com.aid.asset.audio.dto.AudioAssetDetailRequest;
import com.aid.asset.audio.dto.AudioAssetListRequest;
import com.aid.asset.audio.dto.AudioAssetRenameRequest;
import com.aid.asset.audio.service.IAudioAssetBusinessService;
import com.aid.asset.audio.vo.AudioAssetVO;
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
 * C 端音频资产 Controller
 * 配音生成成功（OSS URL 就绪）后自动入库。本控制器只做浏览 / 试听 / 重命名 / 删除，
 * 不承担生成入口，生成走 {@code /api/user/storyboard/generate/audio}。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/audio-asset")
public class AudioAssetController extends BaseController {

    @Resource
    private IAudioAssetBusinessService audioAssetBusinessService;

    /**
     * C 端分页列表：硬过滤当前用户 + del_flag=0。
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody AudioAssetListRequest request) {
        Long userId = SecurityUtils.getUserId();
        IPage<AudioAssetVO> page = audioAssetBusinessService.listForClient(request, userId);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * C 端详情：只能看自己名下的资产，不属于当前用户返回"无权访问"。
     * 按仓库规范 C 端统一走 POST + 请求体。
     */
    @PostMapping("/detail")
    public AjaxResult getDetail(@Valid @RequestBody AudioAssetDetailRequest request) {
        Long userId = SecurityUtils.getUserId();
        AudioAssetVO vo = audioAssetBusinessService.getDetailForClient(request.getId(), userId);
        return success(vo);
    }

    /**
     * C 端重命名：仅限本人资产。
     */
    @PostMapping("/rename")
    public AjaxResult rename(@Valid @RequestBody AudioAssetRenameRequest request) {
        Long userId = SecurityUtils.getUserId();
        audioAssetBusinessService.renameForClient(request, userId);
        return success();
    }

    /**
     * C 端软删除：仅限本人资产。
     */
    @PostMapping("/delete")
    public AjaxResult delete(@Valid @RequestBody AudioAssetDeleteRequest request) {
        Long userId = SecurityUtils.getUserId();
        audioAssetBusinessService.deleteForClient(request, userId);
        return success();
    }
}
