package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidStoryboardBatch;
import com.aid.aid.mapper.AidStoryboardBatchMapper;
import com.aid.aid.service.IAidStoryboardBatchService;
import org.springframework.stereotype.Service;

/**
 * 分镜脚本批次 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class AidStoryboardBatchServiceImpl extends ServiceImpl<AidStoryboardBatchMapper, AidStoryboardBatch>
        implements IAidStoryboardBatchService
{
}
