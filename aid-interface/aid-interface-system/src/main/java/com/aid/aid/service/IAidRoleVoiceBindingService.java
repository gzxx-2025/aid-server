package com.aid.aid.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidRoleVoiceBinding;

/**
 * 角色音色绑定 基础 Service 接口（仅承载裸 CRUD）。
 * 业务编排（参数校验、音色库反查、冗余字段回写、批量列表拼装等）在
 * {@code aid-interface-main} 业务 Service 层完成。
 *
 * @author 视觉AID
 */
public interface IAidRoleVoiceBindingService extends IService<AidRoleVoiceBinding>
{
}
