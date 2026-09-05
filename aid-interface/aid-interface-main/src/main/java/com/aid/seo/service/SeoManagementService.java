package com.aid.seo.service;

import com.aid.seo.model.SeoModels;

import java.util.List;

public interface SeoManagementService {
    SeoModels.Settings getSettings();
    void saveSettings(SeoModels.SettingsSave request, String operator);
    SeoModels.Overview overview();
    SeoModels.PageResult page(SeoModels.PageQuery request);
    SeoModels.PageView savePage(SeoModels.PageSave request, String operator);
    void archivePage(Long pageId, String operator);
    int scan(String triggerType, String operator);
    SeoModels.DispatchResult submit(List<Long> pageIds, String triggerType, Long operatorId, String operatorName);
    int confirmManual(List<Long> pageIds, Long operatorId, String operatorName);
    List<SeoModels.LogView> logs(Long pageId, int limit);
    SeoModels.MetaView meta(String path);
    String robots();
    String sitemap();
    void scheduledTick();
}
