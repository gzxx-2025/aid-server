package com.aid.asset.audio.service;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.asset.audio.dto.AudioAssetDeleteRequest;
import com.aid.asset.audio.dto.AudioAssetListRequest;
import com.aid.asset.audio.dto.AudioAssetRenameRequest;
import com.aid.asset.audio.vo.AudioAssetVO;

/**
 * 音频资产业务 Service 接口
 * 承载 C 端列表 / 详情 / 重命名 / 软删（归属校验 + 字段裁剪），
 * 以及后台管理端的完整列表与软删。
 *
 * @author 视觉AID
 */
public interface IAudioAssetBusinessService {

    /**
     * C 端音频资产分页列表（硬过滤当前用户 + del_flag=0）。
     *
     * @param request 查询条件
     * @param userId  当前登录用户ID
     * @return 分页结果
     */
    IPage<AudioAssetVO> listForClient(AudioAssetListRequest request, Long userId);

    /**
     * 后台管理音频资产分页列表（del_flag=0；不限用户）。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    IPage<AudioAssetVO> listForAdmin(AudioAssetListRequest request);

    /**
     * C 端查询资产详情。校验归属；不存在 / 不属于当前用户抛"记录不存在"。
     *
     * @param id     资产ID
     * @param userId 当前用户ID
     * @return 资产详情
     */
    AudioAssetVO getDetailForClient(Long id, Long userId);

    /**
     * 后台查询资产详情（不做归属校验）。
     *
     * @param id 资产ID
     * @return 资产详情
     */
    AudioAssetVO getDetailForAdmin(Long id);

    /**
     * C 端重命名资产（只能改自己名下）。
     *
     * @param request 重命名请求
     * @param userId  当前用户ID
     */
    void renameForClient(AudioAssetRenameRequest request, Long userId);

    /**
     * C 端软删除资产（只能删自己名下）。
     *
     * @param request 删除请求
     * @param userId  当前用户ID
     */
    void deleteForClient(AudioAssetDeleteRequest request, Long userId);

    /**
     * 后台批量软删除资产。
     *
     * @param ids 主键集合
     */
    void deleteForAdmin(Long[] ids);

    /**
     * 后台导出：按列表筛选条件全量拉取实体。
     * 与 {@link #listForAdmin(AudioAssetListRequest)} 共用同一套 wrapper 构造逻辑，
     * 避免"列表看到 / 导出不到"的条件分叉。
     *
     * @param request 查询条件
     * @return 实体集合（供 {@code ExcelUtil} 导出）
     */
    List<AidAudioAsset> listEntitiesForExport(AudioAssetListRequest request);
}
