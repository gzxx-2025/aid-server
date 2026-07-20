package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.mapper.AidRolePropSceneFormImageMapper;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import org.springframework.stereotype.Service;

/**
 * 角色场景道具形态图片实例 Service 实现（仅承载通用 CRUD，业务编排在 main 模块）。
 *
 * @author 视觉AID
 */
@Service
public class AidRolePropSceneFormImageServiceImpl
        extends ServiceImpl<AidRolePropSceneFormImageMapper, AidRolePropSceneFormImage>
        implements IAidRolePropSceneFormImageService
{
}
