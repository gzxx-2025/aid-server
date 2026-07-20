package com.aid.upgrade.service;

import com.aid.upgrade.dto.OfficialApiStatusVo;
import com.aid.upgrade.dto.OfficialGatewaySaveDto;
import com.aid.upgrade.dto.OfficialGatewaySettingVo;
import com.aid.upgrade.dto.RollbackRequestDto;
import com.aid.upgrade.dto.UpgradeSourceSaveDto;
import com.aid.upgrade.dto.UpgradeSourceSettingVo;
import com.aid.upgrade.dto.UpgradeStatusVo;

/**
 * 系统升级Service接口
 *
 * @author 视觉AID
 */
public interface ISystemUpgradeService {

    /**
     * 查询系统版本与升级状态
     *
     * @param forceRefresh 是否强制回源更新清单
     * @return 系统版本与升级状态
     */
    UpgradeStatusVo getStatus(boolean forceRefresh);

    /**
     * 提交一键升级任务
     *
     * @return 提示信息
     */
    String startUpgrade();

    /**
     * 提交升级器在线升级任务（升级器下载新版并自替换重启）
     *
     * @return 提示信息
     */
    String startUpdaterUpgrade();

    /**
     * 提交系统版本回退任务
     *
     * @param requestDto 回退参数
     * @return 提示信息
     */
    String rollback(RollbackRequestDto requestDto);

    /**
     * 查询升级源配置
     *
     * @return 升级源配置
     */
    UpgradeSourceSettingVo getUpgradeSource();

    /**
     * 保存升级源配置（更新清单地址/升级器下载地址/升级器健康文件路径）
     *
     * @param saveDto 保存参数
     */
    void saveUpgradeSource(UpgradeSourceSaveDto saveDto);

    /**
     * 查询官方统一网关设置
     *
     * @return 官方统一网关设置
     */
    OfficialGatewaySettingVo getOfficialGatewaySetting();

    /**
     * 保存官方统一网关设置
     *
     * @param saveDto 保存参数
     */
    void saveOfficialGateway(OfficialGatewaySaveDto saveDto);

    /**
     * 手动获取更新清单中的官方API地址（只比对，不写入）
     *
     * @return 官方API地址同步状态
     */
    OfficialApiStatusVo fetchOfficialApi();

    /**
     * 将更新清单中的官方API地址应用到本地配置
     *
     * @return 应用后的官方API地址同步状态
     */
    OfficialApiStatusVo applyOfficialApi();
}
