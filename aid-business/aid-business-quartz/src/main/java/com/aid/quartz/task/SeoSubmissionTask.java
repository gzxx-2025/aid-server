package com.aid.quartz.task;

import com.aid.seo.service.SeoManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SEO 页面扫描与搜索引擎提交调度入口。 */
@Component("seoSubmissionTask")
@RequiredArgsConstructor
public class SeoSubmissionTask {
    private final SeoManagementService seoManagementService;

    public void tick() {
        seoManagementService.scheduledTick();
    }
}
