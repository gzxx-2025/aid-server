package com.aid.asset.audio.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import com.aid.asset.audio.dto.ReferenceAudioDeleteRequest;
import com.aid.asset.audio.dto.ReferenceAudioListRequest;
import com.aid.asset.audio.dto.ReferenceAudioRenameRequest;
import com.aid.asset.audio.dto.ReferenceAudioUploadRequest;
import com.aid.asset.audio.vo.ReferenceAudioVO;

/**
 * 参考音频业务 Service 接口。
 * 承载用户上传参考音频的登记 / 列表 / 重命名 / 软删，全部按 userId + projectId 硬隔离。
 *
 * @author 视觉AID
 */
public interface IReferenceAudioBusinessService {

    /**
     * 登记一条用户上传的参考音频。
     * 文件已由 {@code /api/user/oss/upload} 上传，本方法只校验并落库：
     * 项目归属 → 本站相对路径 → 可探测格式白名单 → 时长探测与边界 → 项目配额。
     *
     * @param request 登记请求
     * @param userId  当前登录用户ID
     * @return 登记后的参考音频
     */
    ReferenceAudioVO upload(ReferenceAudioUploadRequest request, Long userId);

    /**
     * 参考音频分页列表（硬过滤当前用户 + 项目 + del_flag=0）。
     *
     * @param request 查询条件
     * @param userId  当前登录用户ID
     * @return 分页结果
     */
    IPage<ReferenceAudioVO> list(ReferenceAudioListRequest request, Long userId);

    /**
     * 重命名参考音频（只能改自己名下）。
     *
     * @param request 重命名请求
     * @param userId  当前登录用户ID
     */
    void rename(ReferenceAudioRenameRequest request, Long userId);

    /**
     * 软删除参考音频（只能删自己名下），同时清空引用它的角色绑定冗余列，
     * 避免绑定行残留已删音频的 URL 继续下发给厂商。
     *
     * @param request 删除请求
     * @param userId  当前登录用户ID
     */
    void delete(ReferenceAudioDeleteRequest request, Long userId);
}
