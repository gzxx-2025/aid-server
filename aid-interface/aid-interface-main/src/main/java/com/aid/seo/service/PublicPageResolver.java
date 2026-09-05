package com.aid.seo.service;

import com.aid.seo.model.SeoModels;

/** 内容模块按当前公开权限提供可直接阅读的页面，禁止返回草稿或内部字段。 */
public interface PublicPageResolver {
    /** 不属于本模块、已删除或不可公开的路径返回 null；应使用按主键的定向查询。 */
    SeoModels.PublicDocument resolvePublicPage(String path);
}
