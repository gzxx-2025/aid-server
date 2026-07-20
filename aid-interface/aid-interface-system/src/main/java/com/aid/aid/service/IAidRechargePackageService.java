package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidRechargePackage;

/**
 * 充值套餐配置Service接口
 *
 * @author 视觉AID
 */
public interface IAidRechargePackageService extends IService<AidRechargePackage>
{
    /**
     * 查询充值套餐配置
     *
     * @param id 充值套餐配置主键
     * @return 充值套餐配置
     */
    public AidRechargePackage selectAidRechargePackageById(Long id);

    /**
     * 查询充值套餐配置列表
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 充值套餐配置集合
     */
    public List<AidRechargePackage> selectAidRechargePackageList(AidRechargePackage aidRechargePackage);

    /**
     * 新增充值套餐配置
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 结果
     */
    public int insertAidRechargePackage(AidRechargePackage aidRechargePackage);

    /**
     * 修改充值套餐配置
     *
     * @param aidRechargePackage 充值套餐配置
     * @return 结果
     */
    public int updateAidRechargePackage(AidRechargePackage aidRechargePackage);

    /**
     * 批量删除充值套餐配置
     *
     * @param ids 需要删除的充值套餐配置主键集合
     * @return 结果
     */
    public int deleteAidRechargePackageByIds(Long[] ids);

    /**
     * 删除充值套餐配置信息
     *
     * @param id 充值套餐配置主键
     * @return 结果
     */
    public int deleteAidRechargePackageById(Long id);
}
