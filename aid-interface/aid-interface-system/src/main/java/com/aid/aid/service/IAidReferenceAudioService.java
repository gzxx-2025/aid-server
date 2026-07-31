package com.aid.aid.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidReferenceAudio;

/**
 * 用户上传参考音频 基础 Service 接口（仅承载裸 CRUD）。
 * 业务编排（路径校验、格式白名单、时长探测、配额、绑定联动等）在
 * {@code aid-interface-main} 业务 Service 层完成。
 *
 * @author 视觉AID
 */
public interface IAidReferenceAudioService extends IService<AidReferenceAudio>
{
}
