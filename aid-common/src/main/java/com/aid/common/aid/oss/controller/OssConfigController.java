package com.aid.common.aid.oss.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.vo.OssUploadLimitsVO;
import com.aid.common.core.domain.AjaxResult;

/**
 * OSS配置Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/oss/config")
public class OssConfigController
{
    /**
     * OSS核心操作类
     */
    @Autowired
    private OssTemplate ossTemplate;

    /**
     * 刷新OSS配置
     * 在配置页面修改后点击"刷新配置"按钮调用
     */
    @PreAuthorize("@ss.hasPermi('oss:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh()
    {
        ossTemplate.refresh();
        return AjaxResult.success();
    }

    /**
     * 获取当前生效的OSS配置
     * 在配置页面展示当前使用的参数
     */
    @PreAuthorize("@ss.hasPermi('oss:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig()
    {
        Map<String, String> config = ossTemplate.getCurrentConfig();
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", config);
        return ajax;
    }

    /**
     * 获取当前生效的上传大小限制（分类型上限 + 全局兜底）。
     * 仅需登录即可访问，供各上传组件在上传前按文件类型做大小预校验，与后台配置保持一致。
     */
    @GetMapping("/upload-limits")
    public AjaxResult getUploadLimits()
    {
        OssUploadLimitsVO data = ossTemplate.getUploadLimits();
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", data);
        return ajax;
    }
}
