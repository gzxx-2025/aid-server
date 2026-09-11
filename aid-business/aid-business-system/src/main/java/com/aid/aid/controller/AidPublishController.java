package com.aid.aid.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.aid.aid.domain.vo.AidPublishItemVo;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.publish.dto.AdminPublishActionRequest;
import com.aid.publish.dto.AdminPublishListRequest;
import com.aid.publish.dto.AdminPublishPermissionRequest;
import com.aid.publish.dto.AdminPublishUserSearchRequest;
import com.aid.publish.dto.AdminWhitelistAddRequest;
import com.aid.publish.dto.AdminWhitelistQueryRequest;
import com.aid.publish.dto.AdminWhitelistRemoveRequest;
import com.aid.publish.service.IAdminPublishBusinessService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * 后台-作品发布管理Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/publish")
public class AidPublishController extends BaseController
{
    @Resource
    private IAdminPublishBusinessService adminPublishBusinessService;

    /**
     * 发布管理列表
     * publishState：approved=审核通过未发布，published=已发布，不传查两类合集；
     * 支持作品名称/类型与作者关键字（昵称/邮箱/手机号）筛选，数据放 data 字段，total 为总数。
     *
     * @param request 查询条件
     * @return 发布管理列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:list')")
    @PostMapping("/list")
    public AjaxResult list(@RequestBody AdminPublishListRequest request)
    {
        // 分页参数取自 POST 请求体（紧邻查询开启，确保 PageHelper 仅拦截这一条）
        PageHelper.startPage(request.resolvePageNum(), request.resolvePageSize());
        List<AidPublishItemVo> list = adminPublishBusinessService.selectPublishList(request);
        return buildPageResult(list);
    }

    /**
     * 上架作品
     * 将「审核通过未发布」的作品置为已发布并记录发布时间，原因可选。
     *
     * @param request 操作请求（项目ID + 原因可选）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "作品上架", businessType = BusinessType.UPDATE)
    @PostMapping("/online")
    public AjaxResult online(@Valid @RequestBody AdminPublishActionRequest request)
    {
        adminPublishBusinessService.forceOnline(request, SecurityUtils.getUsername());
        return AjaxResult.success("上架成功");
    }

    /**
     * 下架作品
     * 将「已发布」的作品关闭公开，审核状态保留，原因必填。
     *
     * @param request 操作请求（项目ID + 原因必填）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "作品下架", businessType = BusinessType.UPDATE)
    @PostMapping("/offline")
    public AjaxResult offline(@Valid @RequestBody AdminPublishActionRequest request)
    {
        adminPublishBusinessService.forceOffline(request, SecurityUtils.getUsername());
        return AjaxResult.success("下架成功");
    }

    /**
     * 回撤审核
     * 撤销「审核通过」并同步下架，状态转审核失败，原因必填（用户可修改后重新提审）。
     *
     * @param request 操作请求（项目ID + 原因必填）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "审核回撤", businessType = BusinessType.UPDATE)
    @PostMapping("/revoke")
    public AjaxResult revoke(@Valid @RequestBody AdminPublishActionRequest request)
    {
        adminPublishBusinessService.revokeAudit(request, SecurityUtils.getUsername());
        return AjaxResult.success("回撤成功");
    }

    /**
     * 发布白名单列表
     * 名单内用户不受发布总开关限制；keyword 匹配昵称/邮箱/手机号。
     *
     * @param request 查询条件
     * @return 白名单列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:whitelist')")
    @PostMapping("/whitelist/list")
    public AjaxResult whitelistList(@RequestBody AdminWhitelistQueryRequest request)
    {
        // 分页参数取自 POST 请求体（紧邻查询开启，确保 PageHelper 仅拦截这一条）
        PageHelper.startPage(request.resolvePageNum(), request.resolvePageSize());
        List<AidPublishWhitelistVo> list = adminPublishBusinessService.selectWhitelist(request);
        return buildPageResult(list);
    }

    /**
     * 添加发布白名单
     * 用户须先通过用户搜索接口获取用户ID；已在名单内会提示。
     *
     * @param request 添加请求（用户ID + 备注可选）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:whitelist')")
    @Log(title = "发布白名单添加", businessType = BusinessType.INSERT)
    @PostMapping("/whitelist/add")
    public AjaxResult whitelistAdd(@Valid @RequestBody AdminWhitelistAddRequest request)
    {
        adminPublishBusinessService.addWhitelist(request, SecurityUtils.getUsername());
        return AjaxResult.success("添加成功");
    }

    /**
     * 移除发布白名单
     *
     * @param request 移除请求（白名单记录ID）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:whitelist')")
    @Log(title = "发布白名单移除", businessType = BusinessType.DELETE)
    @PostMapping("/whitelist/remove")
    public AjaxResult whitelistRemove(@Valid @RequestBody AdminWhitelistRemoveRequest request)
    {
        adminPublishBusinessService.removeWhitelist(request, SecurityUtils.getUsername());
        return AjaxResult.success("移除成功");
    }

    /**
     * 用户搜索
     * 按 昵称/邮箱/手机号 模糊搜索（最多50条），昵称按「昵称(ID)」返回，
     * 带出用户级发布权限与是否已在白名单，供白名单添加与权限设置使用。
     *
     * @param request 搜索请求（关键字必填）
     * @return 用户列表
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:query')")
    @PostMapping("/user/search")
    public AjaxResult userSearch(@Valid @RequestBody AdminPublishUserSearchRequest request)
    {
        List<AidPublishUserVo> list = adminPublishBusinessService.searchUsers(request);
        return AjaxResult.success(list);
    }

    /**
     * 设置用户发布权限
     * publishEnabled：1允许 0禁止；禁止后该用户不能发布作品（白名单也不豁免）。
     *
     * @param request 权限设置请求（用户ID + 权限值）
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "用户发布权限设置", businessType = BusinessType.UPDATE)
    @PostMapping("/user/permission")
    public AjaxResult userPermission(@Valid @RequestBody AdminPublishPermissionRequest request)
    {
        adminPublishBusinessService.setUserPublishPermission(request, SecurityUtils.getUsername());
        return AjaxResult.success("设置成功");
    }

    /**
     * 组装分页返回：数据统一放 data 字段，total 为总数
     *
     * @param list 数据列表
     * @return 分页结果
     */
    private AjaxResult buildPageResult(List<?> list)
    {
        AjaxResult result = AjaxResult.success();
        result.put("total", new PageInfo<>(list).getTotal());
        result.put("data", list);
        return result;
    }
}
