package com.aid.seo.service;

import com.aid.seo.model.SeoModels.ContentCandidate;

import java.util.List;

/**
 * 公开内容模块实现此接口即可被 SEO 定时扫描发现。
 * 实现不得返回草稿、待审核、已下线或无权公开的内容。
 * 内容变化需要重新提交时，应为 ContentCandidate 提供稳定、真实的 sourceUpdateTime。
 */
public interface SeoPageContributor {
    /** 同一内容来源的稳定类型标识，例如 ARTICLE；不能使用 MANUAL 或 STATIC。 */
    String sourceType();

    List<ContentCandidate> listIndexablePages();
}
