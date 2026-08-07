package com.aid.upgrade.service;

import org.springframework.web.multipart.MultipartFile;

import com.aid.upgrade.dto.OfficialAssetsStatusVo;

/**
 * 官方资源包初始化服务。
 *
 * @author 视觉AID
 */
public interface IOfficialAssetsService {

    /**
     * 查询本地官方资源初始化状态。
     *
     * @return 初始化状态
     */
    OfficialAssetsStatusVo getStatus();

    /**
     * 校验并安装官方资源包。
     *
     * @param file 官方资源包
     * @return 安装后的状态
     */
    OfficialAssetsStatusVo install(MultipartFile file);
}
