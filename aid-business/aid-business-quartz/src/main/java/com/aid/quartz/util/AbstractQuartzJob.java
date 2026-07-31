package com.aid.quartz.util;

import com.aid.common.constant.Constants;
import com.aid.common.constant.ScheduleConstants;
import com.aid.common.utils.ExceptionUtil;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.bean.BeanUtils;
import com.aid.common.utils.spring.SpringUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aid.quartz.domain.SysJob;
import com.aid.quartz.domain.SysJobLog;
import com.aid.quartz.service.ISysJobLogService;

import java.util.Date;

/**
 * 抽象quartz调用
 *
 * @author AID
 */
public abstract class AbstractQuartzJob implements Job
{
    private static final Logger log = LoggerFactory.getLogger(AbstractQuartzJob.class);

    /**
     * 线程本地变量
     */
    private static ThreadLocal<Date> threadLocal = new ThreadLocal<>();

    @Override
    public void execute(JobExecutionContext context)
    {
        SysJob sysJob = new SysJob();
        BeanUtils.copyBeanProp(sysJob, context.getMergedJobDataMap().get(ScheduleConstants.TASK_PROPERTIES));
        try
        {
            before(context, sysJob);
            if (sysJob != null)
            {
                doExecute(context, sysJob);
            }
            after(context, sysJob, null);
        }
        catch (Exception e)
        {
            log.error("任务执行异常  - ：", e);
            after(context, sysJob, e);
        }
    }

    /**
     * 执行前
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void before(JobExecutionContext context, SysJob sysJob)
    {
        threadLocal.set(new Date());
    }

    /**
     * 执行后
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void after(JobExecutionContext context, SysJob sysJob, Exception e)
    {
        Date startTime = threadLocal.get();
        threadLocal.remove();

        final SysJobLog sysJobLog = new SysJobLog();
        sysJobLog.setJobName(sysJob.getJobName());
        sysJobLog.setJobGroup(sysJob.getJobGroup());
        sysJobLog.setInvokeTarget(sysJob.getInvokeTarget());
        sysJobLog.setStartTime(startTime);
        sysJobLog.setStopTime(new Date());
        long runMs = sysJobLog.getStopTime().getTime() - sysJobLog.getStartTime().getTime();
        sysJobLog.setJobMessage(sysJobLog.getJobName() + " 总共耗时：" + runMs + "毫秒");
        if (e != null)
        {
            sysJobLog.setStatus(Constants.FAIL);
            String errorMsg = StringUtils.substring(ExceptionUtil.getExceptionMessage(e), 0, 2000);
            sysJobLog.setExceptionInfo(errorMsg);
        }
        else
        {
            sysJobLog.setStatus(Constants.SUCCESS);
        }

        // 需求11：定时任务日志降噪。高频任务（如每秒轮询）成功日志意义不大却产生海量记录，
        // 这里按配置决定是否落库，默认仅记录“失败 + 耗时较长”的执行，大幅减少 sys_job_log 体量。
        //   aid.jobLog.mode : all=全部记录 / failAndSlow=失败或慢任务（默认）/ failOnly=仅失败
        //   aid.jobLog.slowMs: 慢任务阈值（毫秒，默认5000），failAndSlow 模式下成功且超过该耗时才记录
        if (!shouldPersist(e, runMs))
        {
            return;
        }

        // 写入数据库当中
        SpringUtils.getBean(ISysJobLogService.class).addJobLog(sysJobLog);
    }

    /**
     * 是否需要把本次执行写入 sys_job_log（需求11 降噪）。
     *
     * @param e     执行异常（null 表示成功）
     * @param runMs 执行耗时（毫秒）
     * @return true=落库；false=丢弃
     */
    private boolean shouldPersist(Exception e, long runMs)
    {
        // 失败永远记录，便于排障
        if (e != null)
        {
            return true;
        }
        String mode;
        long slowMs;
        try
        {
            org.springframework.core.env.Environment env = SpringUtils.getBean(org.springframework.core.env.Environment.class);
            mode = env.getProperty("aid.jobLog.mode", "failAndSlow");
            slowMs = Long.parseLong(env.getProperty("aid.jobLog.slowMs", "5000"));
        }
        catch (Exception ex)
        {
            // 读取配置异常时退回保守策略：仅记录失败（此处为成功）→ 不记录
            mode = "failAndSlow";
            slowMs = 5000L;
        }
        if ("all".equalsIgnoreCase(mode))
        {
            return true;
        }
        if ("failOnly".equalsIgnoreCase(mode))
        {
            return false;
        }
        // 默认 failAndSlow：成功且耗时超过阈值才记录
        return runMs >= slowMs;
    }

    /**
     * 执行方法，由子类重载
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     * @throws Exception 执行过程中的异常
     */
    protected abstract void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception;
}
