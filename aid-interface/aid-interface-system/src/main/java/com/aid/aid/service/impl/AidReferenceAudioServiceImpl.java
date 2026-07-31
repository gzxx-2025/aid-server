package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.mapper.AidReferenceAudioMapper;
import com.aid.aid.service.IAidReferenceAudioService;
import org.springframework.stereotype.Service;

/**
 * 用户上传参考音频 基础 Service 实现（仅承载裸 CRUD）。
 *
 * @author 视觉AID
 */
@Service
public class AidReferenceAudioServiceImpl
        extends ServiceImpl<AidReferenceAudioMapper, AidReferenceAudio>
        implements IAidReferenceAudioService
{
}
