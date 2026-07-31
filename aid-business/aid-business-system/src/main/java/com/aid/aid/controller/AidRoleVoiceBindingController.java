package com.aid.aid.controller;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.poi.ExcelUtil;

import cn.hutool.core.util.StrUtil;

/**
 * 角色音色绑定 后台管理 Controller。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/role-voice-binding")
public class AidRoleVoiceBindingController extends BaseController
{
    /** 软删标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 软删标志：删除 */
    private static final String DEL_FLAG_DELETED = "2";

    @Autowired
    private IAidRoleVoiceBindingService bindingService;

    /**
     * 查询角色音色绑定列表（分页）。
     * 默认仅返回活跃行（{@code del_flag='0'}）；如需查历史软删行，传 {@code delFlag='2'}。
     */
    @PreAuthorize("@ss.hasPermi('aid:role-voice-binding:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidRoleVoiceBinding query)
    {
        startPage();
        return getDataTable(bindingService.list(buildQueryWrapper(query)));
    }

    /**
     * 导出角色音色绑定列表。
     */
    @PreAuthorize("@ss.hasPermi('aid:role-voice-binding:export')")
    @Log(title = "角色音色绑定", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidRoleVoiceBinding query)
    {
        List<AidRoleVoiceBinding> list = bindingService.list(buildQueryWrapper(query));
        ExcelUtil<AidRoleVoiceBinding> util = new ExcelUtil<>(AidRoleVoiceBinding.class);
        util.exportExcel(response, list, "角色音色绑定");
    }

    /**
     * 查询单条绑定详情。
     */
    @PreAuthorize("@ss.hasPermi('aid:role-voice-binding:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        if (Objects.isNull(id))
        {
            return error("参数无效");
        }
        AidRoleVoiceBinding entity = bindingService.getById(id);
        if (Objects.isNull(entity))
        {
            return error("记录不存在");
        }
        return success(entity);
    }

    /**
     * 编辑绑定（运营干预）。
     * 只允许修改：status / overrideSpeed / overridePitch / overrideEmotion / remark；
     * 其它字段（asset / voice 关联、冗余展示字段）由 C 端 bind 接口维护，后台不开放编辑。
     */
    @PreAuthorize("@ss.hasPermi('aid:role-voice-binding:edit')")
    @Log(title = "角色音色绑定", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidRoleVoiceBinding request)
    {
        if (Objects.isNull(request) || Objects.isNull(request.getId()))
        {
            return error("参数无效");
        }
        AidRoleVoiceBinding existing = bindingService.getById(request.getId());
        if (Objects.isNull(existing) || !Objects.equals(DEL_FLAG_NORMAL, existing.getDelFlag()))
        {
            return error("记录不存在");
        }
        // 只覆盖运营字段，其余字段保持 existing 原值（避免前端漏传字段清零）
        AidRoleVoiceBinding update = new AidRoleVoiceBinding();
        update.setId(request.getId());
        update.setStatus(StrUtil.isNotBlank(request.getStatus()) ? request.getStatus() : existing.getStatus());
        update.setOverrideSpeed(request.getOverrideSpeed());
        update.setOverridePitch(request.getOverridePitch());
        update.setOverrideEmotion(request.getOverrideEmotion());
        if (Objects.nonNull(request.getRemark()))
        {
            update.setRemark(request.getRemark());
        }
        update.setUpdateBy(SecurityUtils.getUsername());
        update.setUpdateTime(DateUtils.getNowDate());
        return toAjax(bindingService.updateById(update) ? 1 : 0);
    }

    /**
     * 软删除绑定（支持批量）。
     * 实现为 {@code del_flag='2'}，不会物理删；活跃行删除后 {@code live_asset_id} 生成列变 NULL，
     * C 端可以重新为同一角色绑定新音色。
     */
    @PreAuthorize("@ss.hasPermi('aid:role-voice-binding:remove')")
    @Log(title = "角色音色绑定", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return error("参数无效");
        }
        int count = 0;
        String operator = SecurityUtils.getUsername();
        for (Long id : ids)
        {
            if (Objects.isNull(id))
            {
                continue;
            }
            AidRoleVoiceBinding update = new AidRoleVoiceBinding();
            update.setId(id);
            update.setDelFlag(DEL_FLAG_DELETED);
            update.setUpdateBy(operator);
            update.setUpdateTime(DateUtils.getNowDate());
            if (bindingService.updateById(update))
            {
                count++;
            }
        }
        return toAjax(count);
    }
    /**
     * 构建列表/导出通用查询条件。
     */
    private LambdaQueryWrapper<AidRoleVoiceBinding> buildQueryWrapper(AidRoleVoiceBinding q)
    {
        LambdaQueryWrapper<AidRoleVoiceBinding> wrapper = Wrappers.lambdaQuery();
        // del_flag：默认只看活跃；显式传了就按指定值
        if (Objects.nonNull(q) && StrUtil.isNotBlank(q.getDelFlag()))
        {
            wrapper.eq(AidRoleVoiceBinding::getDelFlag, q.getDelFlag());
        }
        else
        {
            wrapper.eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL);
        }
        if (Objects.nonNull(q))
        {
            if (Objects.nonNull(q.getUserId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getUserId, q.getUserId());
            }
            if (Objects.nonNull(q.getProjectId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getProjectId, q.getProjectId());
            }
            if (Objects.nonNull(q.getEpisodeId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getEpisodeId, q.getEpisodeId());
            }
            if (Objects.nonNull(q.getAssetId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getAssetId, q.getAssetId());
            }
            if (Objects.nonNull(q.getVoiceLibraryId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getVoiceLibraryId, q.getVoiceLibraryId());
            }
            if (Objects.nonNull(q.getModelId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getModelId, q.getModelId());
            }
            if (Objects.nonNull(q.getProviderId()))
            {
                wrapper.eq(AidRoleVoiceBinding::getProviderId, q.getProviderId());
            }
            if (StrUtil.isNotBlank(q.getStatus()))
            {
                wrapper.eq(AidRoleVoiceBinding::getStatus, q.getStatus());
            }
            if (StrUtil.isNotBlank(q.getGender()))
            {
                wrapper.eq(AidRoleVoiceBinding::getGender, q.getGender());
            }
            if (StrUtil.isNotBlank(q.getLanguage()))
            {
                wrapper.eq(AidRoleVoiceBinding::getLanguage, q.getLanguage());
            }
            if (StrUtil.isNotBlank(q.getVoiceName()))
            {
                wrapper.like(AidRoleVoiceBinding::getVoiceName, q.getVoiceName());
            }
            if (StrUtil.isNotBlank(q.getVoiceCode()))
            {
                wrapper.like(AidRoleVoiceBinding::getVoiceCode, q.getVoiceCode());
            }
        }
        wrapper.orderByDesc(AidRoleVoiceBinding::getId);
        return wrapper;
    }
}
