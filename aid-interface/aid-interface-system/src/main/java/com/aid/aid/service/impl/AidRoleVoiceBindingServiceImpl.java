package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.mapper.AidRoleVoiceBindingMapper;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import org.springframework.stereotype.Service;

/**
 * 角色音色绑定 基础 Service 实现（仅承载裸 CRUD）。
 *
 * @author 视觉AID
 */
@Service
public class AidRoleVoiceBindingServiceImpl
        extends ServiceImpl<AidRoleVoiceBindingMapper, AidRoleVoiceBinding>
        implements IAidRoleVoiceBindingService
{
}
