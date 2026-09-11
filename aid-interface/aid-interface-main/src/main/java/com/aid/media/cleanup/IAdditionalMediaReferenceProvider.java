package com.aid.media.cleanup;

import java.util.Collection;
import java.util.Set;

/** 可选业务模块向统一 OSS 清理链声明仍在使用的媒体地址。 */
public interface IAdditionalMediaReferenceProvider
{
    /** 返回入参候选集中仍被该模块有效业务数据引用的值。 */
    Set<String> findReferenced(Collection<String> candidates);
}
