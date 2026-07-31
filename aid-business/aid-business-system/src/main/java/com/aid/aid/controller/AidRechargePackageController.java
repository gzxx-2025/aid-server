package com.aid.aid.controller;

import java.math.BigDecimal;
import java.util.List;

import com.aid.aid.domain.AidRechargePackage;
import com.aid.aid.service.IAidRechargePackageService;
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
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * 充值套餐配置Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/rechargepackage")
public class AidRechargePackageController extends BaseController
{
    @Autowired
    private IAidRechargePackageService aidRechargePackageService;

    /**
     * 查询充值套餐配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidRechargePackage aidRechargePackage)
    {
        startPage();
        List<AidRechargePackage> list = aidRechargePackageService.selectAidRechargePackageList(aidRechargePackage);
        return getDataTable(list);
    }

    /**
     * 导出充值套餐配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:export')")
    @Log(title = "充值套餐配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidRechargePackage aidRechargePackage)
    {
        List<AidRechargePackage> list = aidRechargePackageService.selectAidRechargePackageList(aidRechargePackage);
        ExcelUtil<AidRechargePackage> util = new ExcelUtil<AidRechargePackage>(AidRechargePackage.class);
        util.exportExcel(response, list, "充值套餐配置数据");
    }

    /**
     * 获取充值套餐配置详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidRechargePackageService.selectAidRechargePackageById(id));
    }

    /**
     * 新增充值套餐配置
     * 对套餐价格相关字段做基本合法性校验，防止"0 元套餐换百万积分"这类误配。
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:add')")
    @Log(title = "充值套餐配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidRechargePackage aidRechargePackage)
    {
        validatePricing(aidRechargePackage);
        log.warn("[AUDIT-PACKAGE] 新增套餐, operator={}, name={}, credits={}, payPrice={}, originalPrice={}",
                getUsername(), aidRechargePackage.getPackageName(), aidRechargePackage.getCredits(),
                aidRechargePackage.getPayPrice(), aidRechargePackage.getOriginalPrice());
        return toAjax(aidRechargePackageService.insertAidRechargePackage(aidRechargePackage));
    }

    /**
     * 修改充值套餐配置
     * 对价格 / 积分的变更打审计日志（old→new），便于事后追溯套餐价格改动。
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:edit')")
    @Log(title = "充值套餐配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidRechargePackage aidRechargePackage)
    {
        validatePricing(aidRechargePackage);
        if (aidRechargePackage != null && aidRechargePackage.getId() != null) {
            AidRechargePackage old = aidRechargePackageService.selectAidRechargePackageById(aidRechargePackage.getId());
            if (old != null) {
                log.warn("[AUDIT-PACKAGE] 修改套餐, operator={}, id={}, name: {} -> {}, credits: {} -> {}, "
                                + "payPrice: {} -> {}, originalPrice: {} -> {}, discount: {} -> {}",
                        getUsername(), old.getId(),
                        old.getPackageName(), aidRechargePackage.getPackageName(),
                        old.getCredits(), aidRechargePackage.getCredits(),
                        old.getPayPrice(), aidRechargePackage.getPayPrice(),
                        old.getOriginalPrice(), aidRechargePackage.getOriginalPrice(),
                        old.getDiscount(), aidRechargePackage.getDiscount());
            }
        }
        return toAjax(aidRechargePackageService.updateAidRechargePackage(aidRechargePackage));
    }

    /**
     * 删除充值套餐配置
     * 打审计日志，便于追踪套餐下架变动。
     */
    @PreAuthorize("@ss.hasPermi('aid:rechargepackage:remove')")
    @Log(title = "充值套餐配置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        log.warn("[AUDIT-PACKAGE] 删除套餐, operator={}, ids={}", getUsername(), java.util.Arrays.toString(ids));
        return toAjax(aidRechargePackageService.deleteAidRechargePackageByIds(ids));
    }

    /**
     * 套餐价格合法性校验。
     *  - payPrice / originalPrice 必须为正数
     *  - credits 必须为正整数
     *  - discount（0~1 折扣系数）上限 1
     * 拦截明显误配（例如 0 元对应 100 万积分）。
     */
    private void validatePricing(AidRechargePackage aidRechargePackage) {
        if (aidRechargePackage == null) {
            throw new com.aid.common.exception.ServiceException("套餐参数不能为空");
        }
        if (aidRechargePackage.getPayPrice() == null
                || aidRechargePackage.getPayPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.aid.common.exception.ServiceException("实付金额必须 > 0");
        }
        if (aidRechargePackage.getCredits() == null
                || aidRechargePackage.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.aid.common.exception.ServiceException("积分必须 > 0");
        }
        if (aidRechargePackage.getOriginalPrice() != null
                && aidRechargePackage.getOriginalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.aid.common.exception.ServiceException("原价必须 > 0");
        }
        if (aidRechargePackage.getDiscount() != null) {
            BigDecimal d = aidRechargePackage.getDiscount();
            if (d.compareTo(BigDecimal.ZERO) < 0 || d.compareTo(BigDecimal.ONE) > 0) {
                throw new com.aid.common.exception.ServiceException("折扣系数必须在 [0,1]");
            }
        }
    }
}
