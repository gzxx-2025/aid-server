package com.aid.voice.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.voice.dto.VoiceLibraryListRequest;
import com.aid.voice.service.IVoiceLibraryBusinessService;
import com.aid.voice.vo.VoiceLibraryVO;
import com.aid.voice.vo.VoiceTagBundleVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端音色库 Controller
 * 仅提供只读查询：音色分页列表 + 一次性标签打包。
 * 所有响应仅返回 {@code status=0} 且 {@code del_flag=0} 的音色。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/voice-library")
public class VoiceLibraryController extends BaseController
{
    @Resource
    private IVoiceLibraryBusinessService voiceLibraryBusinessService;

    /**
     * 音色分页列表（C 端）
     * 硬过滤启用状态与未删除；不经过功能池，直接查询音色库。
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody VoiceLibraryListRequest request)
    {
        IPage<VoiceLibraryVO> page = voiceLibraryBusinessService.listForClient(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 一次性拉取音色筛选字典（标签 + 情感 + 基础枚举）
     */
    @PostMapping("/tags")
    public AjaxResult tags()
    {
        VoiceTagBundleVO bundle = voiceLibraryBusinessService.buildTagBundle();
        return success(bundle);
    }
}
