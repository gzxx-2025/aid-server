package com.aid.aid.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.upgrade.dto.OfficialGatewaySaveDto;
import com.aid.upgrade.dto.RollbackRequestDto;
import com.aid.upgrade.dto.UpgradeSourceSaveDto;
import com.aid.upgrade.service.ISystemUpgradeService;

/**
 * 系统升级与官方网关Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aidconfig/upgrade")
public class SystemUpgradeController extends BaseController {

    @Autowired
    private ISystemUpgradeService systemUpgradeService;

    /**
     * 查询系统版本与升级状态（走缓存，供侧边栏与升级页展示）
     */
    @GetMapping("/status")
    public AjaxResult status() {
        return success(systemUpgradeService.getStatus(false));
    }

    /**
     * 手动检查更新（强制回源更新清单）
     */
    @PostMapping("/check")
    public AjaxResult check() {
        return success(systemUpgradeService.getStatus(true));
    }

    /**
     * 提交一键升级任务（升级器可用时才受理）
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:start')")
    @Log(title = "系统升级", businessType = BusinessType.UPDATE)
    @PostMapping("/start")
    public AjaxResult start() {
        return AjaxResult.success(systemUpgradeService.startUpgrade());
    }

    /**
     * 提交升级器在线升级任务（升级器下载新版并自替换重启）
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:updater')")
    @Log(title = "升级器在线升级", businessType = BusinessType.UPDATE)
    @PostMapping("/updater/upgrade")
    public AjaxResult upgradeUpdater() {
        return AjaxResult.success(systemUpgradeService.startUpdaterUpgrade());
    }

    /**
     * 提交系统版本回退任务，目标版本必须在发布清单允许列表中
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:rollback')")
    @Log(title = "系统版本回退", businessType = BusinessType.UPDATE)
    @PostMapping("/rollback")
    public AjaxResult rollback(@RequestBody RollbackRequestDto requestDto) {
        return AjaxResult.success(systemUpgradeService.rollback(requestDto));
    }

    /**
     * 查询升级源配置（更新清单地址/升级器下载地址/升级器健康文件路径）
     */
    @GetMapping("/source")
    public AjaxResult getSource() {
        return success(systemUpgradeService.getUpgradeSource());
    }

    /**
     * 保存升级源配置（全部地址均为灵活配置，修改后立即生效）
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:source')")
    @Log(title = "升级源配置", businessType = BusinessType.UPDATE)
    @PostMapping("/source")
    public AjaxResult saveSource(@RequestBody UpgradeSourceSaveDto saveDto) {
        systemUpgradeService.saveUpgradeSource(saveDto);
        return AjaxResult.success("保存成功");
    }

    /**
     * 查询官方统一网关设置（密钥脱敏返回）
     */
    @GetMapping("/official-gateway")
    public AjaxResult getOfficialGateway() {
        return success(systemUpgradeService.getOfficialGatewaySetting());
    }

    /**
     * 保存官方统一网关设置（密钥留空表示不修改）
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:gateway')")
    @Log(title = "官方统一网关", businessType = BusinessType.UPDATE)
    @PostMapping("/official-gateway")
    public AjaxResult saveOfficialGateway(@RequestBody OfficialGatewaySaveDto saveDto) {
        systemUpgradeService.saveOfficialGateway(saveDto);
        return AjaxResult.success("保存成功");
    }

    /**
     * 手动获取更新清单中的官方API地址（只比对，不写入本地配置）
     */
    @PostMapping("/official-api/fetch")
    public AjaxResult fetchOfficialApi() {
        return success(systemUpgradeService.fetchOfficialApi());
    }

    /**
     * 将更新清单中的官方API地址应用到本地配置
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:official')")
    @Log(title = "官方API地址同步", businessType = BusinessType.UPDATE)
    @PostMapping("/official-api/apply")
    public AjaxResult applyOfficialApi() {
        return success(systemUpgradeService.applyOfficialApi());
    }
}
