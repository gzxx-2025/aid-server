package com.aid.system.service;

/**
 * 日志归档服务（需求6）。
 *
 * @author 视觉AID
 */
public interface ILogArchiveService {

    /**
     * 归档操作日志：把早于 keepDays 天的记录写入本地文件并从库删除。
     *
     * @param keepDays 数据库保留天数（早于该天数的将被归档）
     * @return 本次归档并删除的记录数
     */
    int archiveOperLog(int keepDays);

    /**
     * 归档登录日志：把早于 keepDays 天的记录写入本地文件并从库删除。
     *
     * @param keepDays 数据库保留天数（早于该天数的将被归档）
     * @return 本次归档并删除的记录数
     */
    int archiveLogininfor(int keepDays);
}
