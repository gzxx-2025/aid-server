package com.aid.aid.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.aid.domain.AidUserSocial;
import com.aid.aid.service.IAidUserSocialService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 用户第三方登录授权Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/thridsocial")
public class AidUserSocialController extends BaseController
{
    @Autowired
    private IAidUserSocialService aidUserSocialService;

    /**
     * 查询用户第三方登录授权列表
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidUserSocial aidUserSocial)
    {
        startPage();
        List<AidUserSocial> list = aidUserSocialService.selectAidUserSocialList(aidUserSocial);
        return getDataTable(list);
    }

    /**
     * 导出用户第三方登录授权列表
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:export')")
    @Log(title = "用户第三方登录授权", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidUserSocial aidUserSocial)
    {
        List<AidUserSocial> list = aidUserSocialService.selectAidUserSocialList(aidUserSocial);
        ExcelUtil<AidUserSocial> util = new ExcelUtil<AidUserSocial>(AidUserSocial.class);
        util.exportExcel(response, list, "用户第三方登录授权数据");
    }

    /**
     * 获取用户第三方登录授权详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidUserSocialService.selectAidUserSocialById(id));
    }

    /**
     * 新增用户第三方登录授权
     * 第三方登录绑定表禁止手动新增，否则可预注入 openId 让特定用户扫码后被绑定到攻击者账号。
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:add')")
    @Log(title = "用户第三方登录授权", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidUserSocial aidUserSocial)
    {
        return AjaxResult.error(403, "为保障账号安全，第三方绑定关系禁止手动新增");
    }

    /**
     * 修改用户第三方登录授权
     * 禁止篡改 openId/userId 映射关系。
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:edit')")
    @Log(title = "用户第三方登录授权", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidUserSocial aidUserSocial)
    {
        return AjaxResult.error(403, "为保障账号安全，第三方绑定关系禁止手动修改");
    }

    /**
     * 删除用户第三方登录授权
     * 禁止抹掉他人绑定触发登录失败。
     */
    @PreAuthorize("@ss.hasPermi('aid:thridsocial:remove')")
    @Log(title = "用户第三方登录授权", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "为保障账号安全，第三方绑定关系禁止手动删除");
    }
}
