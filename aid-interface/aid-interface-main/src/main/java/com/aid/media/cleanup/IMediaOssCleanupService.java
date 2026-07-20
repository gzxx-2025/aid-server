package com.aid.media.cleanup;

import java.util.Collection;

/**
 * 媒体文件 OSS 清理服务：硬删除数据时清理其关联的 OSS/COS/本地文件。
 * 文件删除一律在数据库事务提交成功后（afterCommit）于后台线程执行，为 best-effort，失败仅记日志不抛异常。
 *
 * @author 视觉AID
 */
public interface IMediaOssCleanupService
{
    /**
     * 登记一批文件待清理：事务中注册到 afterCommit 后台执行，无事务则立即后台执行。不抛异常、不阻断业务。
     *
     * @param fileUrls 文件 URL 或相对路径集合（内部自动清洗去重）
     */
    void cleanupFiles(Collection<String> fileUrls);
}
