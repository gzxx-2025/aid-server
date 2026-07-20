package com.aid.voice.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.voice.dto.VoiceLibraryListRequest;
import com.aid.voice.dto.VoiceLibraryStatusRequest;
import com.aid.voice.dto.VoiceLibraryUpsertRequest;
import com.aid.voice.vo.VoiceLibraryVO;
import com.aid.voice.vo.VoiceTagBundleVO;

/**
 * 音色库业务 Service 接口
 * 承载列表过滤、标签命中校验、provider 回填、C 端一次性标签打包等业务编排。
 *
 * @author 视觉AID
 */
public interface IVoiceLibraryBusinessService
{
    /**
     * 后台管理分页列表（可返回所有状态音色，受 status 入参控制）
     */
    IPage<VoiceLibraryVO> listForAdmin(VoiceLibraryListRequest request);

    /**
     * C 端分页列表（硬过滤 status=0 且 del_flag=0；响应 VO 中不包含敏感字段）
     */
    IPage<VoiceLibraryVO> listForClient(VoiceLibraryListRequest request);

    /**
     * 单条详情（后台用，包含状态等管理字段）
     */
    VoiceLibraryVO getDetail(Long id);

    /**
     * 新增音色
     *
     * @return 新增记录主键
     */
    Long createVoice(VoiceLibraryUpsertRequest request);

    /**
     * 更新音色
     */
    void updateVoice(VoiceLibraryUpsertRequest request);

    /**
     * 启用 / 停用切换
     */
    void updateVoiceStatus(VoiceLibraryStatusRequest request);

    /**
     * 批量软删除
     */
    void deleteVoices(Long[] ids);

    /**
     * C 端一次性拉取全量筛选字典（包含三类标签 + 情感 + 基础枚举）
     */
    VoiceTagBundleVO buildTagBundle();
}
