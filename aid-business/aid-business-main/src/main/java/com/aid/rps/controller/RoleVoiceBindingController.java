package com.aid.rps.controller;

import java.util.HashSet;
import java.util.Set;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.rps.voice.dto.RoleVoiceBindRequest;
import com.aid.rps.voice.dto.RoleVoiceQueryRequest;
import com.aid.rps.voice.dto.RoleVoiceUnbindRequest;
import com.aid.rps.voice.service.IRoleVoiceBindingBusinessService;
import com.aid.rps.voice.vo.RoleVoiceBindingVO;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 角色音色绑定 Controller（C 端）。
 * 路径前缀 {@code /api/user/asset/rps/voice}，与现有 {@link RpsController}
 * 主资产模块同域；Controller 只做"转发到业务 Service + 统一异常文案处理"，
 * 不写业务逻辑。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/asset/rps/voice")
public class RoleVoiceBindingController extends BaseController
{
    /**
     * Service 层允许直接透出给前端的短文案白名单（均 ≤6 字）。
     * 用于防御"底层异常 / 未归一化长文案"意外绕过 Service 兜底直接落到 Controller 时，
     * 不会把英文 / 厂商原文 / 堆栈直接暴露给前端。命中白名单 → 原样透出；
     * 未命中 → 按操作类型落到 {@code 绑定失败 / 解绑失败 / 查询失败}。
     */
    private static final Set<String> SAFE_SHORT_MESSAGES = buildSafeShortMessages();

    private static Set<String> buildSafeShortMessages()
    {
        Set<String> s = new HashSet<>();
        // 角色归属 / 类型
        s.add("角色不存在");
        s.add("角色不能空");
        s.add("不是角色");
        // 音色归属 / 可用性
        s.add("音色不存在");
        s.add("音色不能空");
        s.add("音色已下架");
        // 覆盖参数
        s.add("语速越界");
        s.add("音调越界");
        s.add("情感不支持");
        s.add("音色不支持情感");
        // 终态兜底
        s.add("绑定失败");
        s.add("解绑失败");
        s.add("查询失败");
        return s;
    }

    @Resource
    private IRoleVoiceBindingBusinessService roleVoiceBindingBusinessService;

    /** 绑定 / 更换角色音色。 */
    @PostMapping("/bind")
    public AjaxResult bind(@Valid @RequestBody RoleVoiceBindRequest request)
    {
        try
        {
            // getUserId() 放 try 内：未登录 / 鉴权上下文缺失抛出的异常也走短文案兜底，
            // 避免底层 NPE / AuthenticationException 原文直接暴露给前端。
            Long userId = SecurityUtils.getUserId();
            RoleVoiceBindingVO vo = roleVoiceBindingBusinessService.bindVoice(request, userId);
            return success(vo);
        }
        catch (Exception e)
        {
            return error(normalize(e, "绑定失败"));
        }
    }

    /** 解除角色音色绑定（幂等：未绑定也返回成功）。 */
    @PostMapping("/unbind")
    public AjaxResult unbind(@Valid @RequestBody RoleVoiceUnbindRequest request)
    {
        try
        {
            Long userId = SecurityUtils.getUserId();
            roleVoiceBindingBusinessService.unbindVoice(request.getAssetId(), userId);
            return success("解除成功");
        }
        catch (Exception e)
        {
            return error(normalize(e, "解绑失败"));
        }
    }

    /** 查询单个角色的音色绑定；未绑定返回 data=null。 */
    @PostMapping("/query")
    public AjaxResult query(@Valid @RequestBody RoleVoiceQueryRequest request)
    {
        try
        {
            Long userId = SecurityUtils.getUserId();
            RoleVoiceBindingVO vo = roleVoiceBindingBusinessService.queryByAssetId(request.getAssetId(), userId);
            return success(vo);
        }
        catch (Exception e)
        {
            return error(normalize(e, "查询失败"));
        }
    }

    /**
     * 异常文案归一化：命中 {@link #SAFE_SHORT_MESSAGES} 白名单 → 原样透出；
     * 空 / 未命中白名单（底层英文 / 堆栈 / 未知异常）→ 兜底短文案。
     * 同时在兜底分支打一行 error 日志便于排查，避免异常被吞。
     */
    private String normalize(Exception e, String fallback)
    {
        String msg = e.getMessage();
        if (StrUtil.isNotBlank(msg) && SAFE_SHORT_MESSAGES.contains(msg))
        {
            return msg;
        }
        log.error("角色音色接口异常兜底: fallback={}, err={}", fallback, msg, e);
        return fallback;
    }
}
