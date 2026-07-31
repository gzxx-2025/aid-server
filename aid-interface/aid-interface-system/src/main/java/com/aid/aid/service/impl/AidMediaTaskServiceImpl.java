package com.aid.aid.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidMediaTaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 媒体任务 Service 实现。
 *
 * @author 视觉AID
 */
@Service
public class AidMediaTaskServiceImpl extends ServiceImpl<AidMediaTaskMapper, AidMediaTask>
        implements IAidMediaTaskService
{
}
