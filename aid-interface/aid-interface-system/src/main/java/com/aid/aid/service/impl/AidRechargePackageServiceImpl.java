package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import com.aid.aid.mapper.AidRechargePackageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidRechargePackage;
import com.aid.aid.service.IAidRechargePackageService;

/**
 * 充值套餐配置Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidRechargePackageServiceImpl extends ServiceImpl<AidRechargePackageMapper, AidRechargePackage> implements IAidRechargePackageService
{
    @Autowired
    private AidRechargePackageMapper aidRechargePackageMapper;

    /**
     * 查询充值套餐配置
     *
     * @param id 充值套餐配置主键
     * @return 充值套餐配置
     */
    @Override
    public AidRechargePackage selectAidRechargePackageById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询充值套餐配置列表
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 充值套餐配置
     */
    @Override
    public List<AidRechargePackage> selectAidRechargePackageList(AidRechargePackage aidRechargePackage)
    {
        LambdaQueryWrapper<AidRechargePackage> wrapper = Wrappers.lambdaQuery();
        if (aidRechargePackage != null)
        {
            if (StrUtil.isNotBlank(aidRechargePackage.getPackageName()))
            {
                wrapper.like(AidRechargePackage::getPackageName, aidRechargePackage.getPackageName());
            }
            if (StrUtil.isNotBlank(aidRechargePackage.getStatus()))
            {
                wrapper.eq(AidRechargePackage::getStatus, aidRechargePackage.getStatus());
            }
        }
        wrapper.orderByAsc(AidRechargePackage::getSortOrder).orderByDesc(AidRechargePackage::getId);
        return this.list(wrapper);
    }

    /**
     * 新增充值套餐配置
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 结果
     */
    @Override
    public int insertAidRechargePackage(AidRechargePackage aidRechargePackage)
    {
        aidRechargePackage.setCreateTime(DateUtils.getNowDate());
        return this.save(aidRechargePackage) ? 1 : 0;
    }

    /**
     * 修改充值套餐配置
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 结果
     */
    @Override
    public int updateAidRechargePackage(AidRechargePackage aidRechargePackage)
    {
        aidRechargePackage.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidRechargePackage) ? 1 : 0;
    }

    /**
     * 批量删除充值套餐配置
     *
     * @param ids 需要删除的充值套餐配置主键
     * @return 结果
     */
    @Override
    public int deleteAidRechargePackageByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除充值套餐配置信息
     *
     * @param id 充值套餐配置主键
     * @return 结果
     */
    @Override
    public int deleteAidRechargePackageById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
