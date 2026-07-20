package com.aid.quartz.registry;

import java.util.List;
import java.util.Objects;

import com.aid.common.constant.ScheduleConstants;
import com.aid.common.utils.StringUtils;
import com.aid.quartz.domain.SysJob;

/**
 * 系统定时任务注册表（代码写死，不允许后台随意变更）。
 *
 * <p>分两类：</p>
 * <ul>
 *   <li><b>固定任务（jobType=1）</b>：业务系统必备，停用会导致任务卡死、计费不一致等数据错误，
 *       后台禁止暂停 / 删除 / 修改调用目标，仅允许在安全频率范围内调整 cron；</li>
 *   <li><b>可选任务（jobType=2）</b>：日志清理类等辅助任务，允许用户自由开关。</li>
 * </ul>
 *
 * <p>应用启动时由 {@code SysJobServiceImpl.init()} 按本注册表自愈：缺失自动补齐、
 * 固定任务被暂停自动恢复、频率越界自动重置为基准频率。</p>
 *
 * @author AID
 */
public final class FixedJobRegistry
{
    /** 每分钟秒数 */
    private static final int SECONDS_PER_MINUTE = 60;

    /** 按日任务的间隔秒数阈值：基准间隔达到该值视为按日任务，不参与动态频率计算 */
    public static final int DAILY_INTERVAL_SECONDS = 86400;

    private FixedJobRegistry()
    {
    }

    /**
     * 任务定义（不可变）
     */
    public static final class JobDef
    {
        /** 规范化目标（bean.method，不含参数），用于与 sys_job.invoke_target 匹配 */
        private final String target;

        /** 完整调用串（含默认参数），种子补齐时写入 sys_job */
        private final String invokeTarget;

        /** 任务名称 */
        private final String jobName;

        /** 任务类型（1固定 2可选） */
        private final String jobType;

        /** 基准 cron（推荐频率的基准，也是自愈重置值） */
        private final String defaultCron;

        /** 种子补齐时的默认状态（0正常 1暂停） */
        private final String defaultStatus;

        /** 基准间隔秒数（达到 86400 视为按日任务） */
        private final int baseIntervalSeconds;

        /** 频率安全下限（秒，仅固定任务强校验；0=不限制） */
        private final int minIntervalSeconds;

        /** 频率安全上限（秒，仅固定任务强校验；0=不限制） */
        private final int maxIntervalSeconds;

        /** 计划执行错误策略 */
        private final String misfirePolicy;

        /** 任务说明 */
        private final String remark;

        private JobDef(String target, String invokeTarget, String jobName, String jobType,
                       String defaultCron, String defaultStatus, int baseIntervalSeconds,
                       int minIntervalSeconds, int maxIntervalSeconds, String misfirePolicy, String remark)
        {
            this.target = target;
            this.invokeTarget = invokeTarget;
            this.jobName = jobName;
            this.jobType = jobType;
            this.defaultCron = defaultCron;
            this.defaultStatus = defaultStatus;
            this.baseIntervalSeconds = baseIntervalSeconds;
            this.minIntervalSeconds = minIntervalSeconds;
            this.maxIntervalSeconds = maxIntervalSeconds;
            this.misfirePolicy = misfirePolicy;
            this.remark = remark;
        }

        public String getTarget()
        {
            return target;
        }

        public String getInvokeTarget()
        {
            return invokeTarget;
        }

        public String getJobName()
        {
            return jobName;
        }

        public String getJobType()
        {
            return jobType;
        }

        public String getDefaultCron()
        {
            return defaultCron;
        }

        public String getDefaultStatus()
        {
            return defaultStatus;
        }

        public int getBaseIntervalSeconds()
        {
            return baseIntervalSeconds;
        }

        public int getMinIntervalSeconds()
        {
            return minIntervalSeconds;
        }

        public int getMaxIntervalSeconds()
        {
            return maxIntervalSeconds;
        }

        public String getMisfirePolicy()
        {
            return misfirePolicy;
        }

        public String getRemark()
        {
            return remark;
        }

        /** 是否固定任务 */
        public boolean isFixed()
        {
            return Objects.equals(ScheduleConstants.JOB_TYPE_FIXED, jobType);
        }

        /** 是否按日任务（不参与动态频率计算） */
        public boolean isDaily()
        {
            return baseIntervalSeconds >= DAILY_INTERVAL_SECONDS;
        }

        /** 转为种子 SysJob（缺失补齐时使用） */
        public SysJob toSeedJob()
        {
            SysJob job = new SysJob();
            job.setJobName(jobName);
            job.setJobGroup("SYSTEM");
            job.setInvokeTarget(invokeTarget);
            job.setCronExpression(defaultCron);
            job.setMisfirePolicy(misfirePolicy);
            // 统一禁止并发：所有系统任务自身都有防重入保护，双重保障
            job.setConcurrent("1");
            job.setStatus(defaultStatus);
            job.setJobType(jobType);
            job.setRemark(remark);
            job.setCreateBy("system");
            return job;
        }
    }

    /** 固定任务定义快捷构造（默认状态=正常） */
    private static JobDef fixed(String target, String invokeTarget, String jobName, String defaultCron,
                                int baseSeconds, int minSeconds, int maxSeconds, String misfirePolicy, String remark)
    {
        return new JobDef(target, invokeTarget, jobName, ScheduleConstants.JOB_TYPE_FIXED,
                defaultCron, ScheduleConstants.Status.NORMAL.getValue(),
                baseSeconds, minSeconds, maxSeconds, misfirePolicy, remark);
    }

    /** 可选任务定义快捷构造（频率不强校验） */
    private static JobDef optional(String target, String invokeTarget, String jobName, String defaultCron,
                                   int baseSeconds, String defaultStatus, String remark)
    {
        return new JobDef(target, invokeTarget, jobName, ScheduleConstants.JOB_TYPE_OPTIONAL,
                defaultCron, defaultStatus, baseSeconds, 0, 0, ScheduleConstants.MISFIRE_DO_NOTHING, remark);
    }

    /**
     * 系统任务清单（代码写死）。
     * 固定任务：核心调度 / 计费补偿 / 支付兜底 / 僵尸回收，停用会造成业务数据错误。
     * 可选任务：日志清理归档类，允许用户开关。
     */
    private static final List<JobDef> DEFS = List.of(
            // ==================== 固定任务（禁止关闭） ====================
            fixed("mediaTask.dispatch", "mediaTask.dispatch", "媒体任务统一调度中心",
                    "0 * * * * ?", 60, 10, 300, ScheduleConstants.MISFIRE_IGNORE_MISFIRES,
                    "异步图片/视频/音频任务推进核心：到期轮询、回调超时转轮询、超时关闭、僵尸收口、排队兜底拉起。停用后任务无法到达终态"),
            fixed("systemCoreTask.queueDispatchTick", "systemCoreTask.queueDispatchTick", "任务队列调度拍",
                    "0/2 * * * * ?", 2, 1, 15, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "驱动提取任务排队放行与并发名额回收。停用后用户任务将永久排队"),
            fixed("systemCoreTask.leaseHeartbeat", "systemCoreTask.leaseHeartbeat", "任务执行租约心跳",
                    "0/30 * * * * ?", 30, 10, 30, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "为执行中任务续 Redis 租约（TTL 90秒），间隔不得超过30秒，否则长任务会被误判失败并退款"),
            fixed("systemCoreTask.reapLeaselessProcessing", "systemCoreTask.reapLeaselessProcessing", "租约失活僵尸任务回收",
                    "0/30 * * * * ?", 30, 15, 300, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "回收进程崩溃后租约失活的 PROCESSING 僵尸任务并退款，停用后用户会被【处理中】永久卡住"),
            fixed("extractZombieReclaimTask.reclaim", "extractZombieReclaimTask.reclaim()", "提取僵尸任务阈值自愈",
                    "0 0/2 * * * ?", 120, 60, 600, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "按任务类型阈值清理卡死的提取任务：标记失败 + 退款 + 释放业务锁"),
            fixed("payOrderTask.syncPendingExpiredOrders", "payOrderTask.syncPendingExpiredOrders", "支付订单兜底查单",
                    "0 0/2 * * * ?", 120, 60, 600, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "扫描超时待支付订单并向支付渠道查单，回调丢失时补入账或关单，停用会导致用户已付款不到账"),
            fixed("mediaTask.billingCompensate", "mediaTask.billingCompensate()", "媒体计费补偿",
                    "0 0/2 * * * ?", 120, 60, 600, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "推进卡在 SETTLING/REFUNDING/FROZEN 的媒体计费到终态，停用会导致用户余额冻结不释放"),
            fixed("mediaTask.extraChargeCompensate", "mediaTask.extraChargeCompensate()", "媒体TOKEN追补",
                    "0 0/5 * * * ?", 300, 120, 1800, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "扫描部分扣费(PARTIAL_DONE)的媒体任务，从可用余额追补差额，保证账目一致"),
            fixed("extractBillingTask.compensate", "extractBillingTask.compensate()", "提取计费补偿",
                    "0 0/2 * * * ?", 120, 60, 600, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "推进卡在 SETTLING/REFUNDING/FROZEN 的提取任务计费到终态，停用会导致冻结资金不释放"),
            fixed("extractBillingTask.extraChargeCompensate", "extractBillingTask.extraChargeCompensate()", "提取TOKEN追补",
                    "0 0/5 * * * ?", 300, 120, 1800, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "扫描部分扣费(PARTIAL_SUCCESS)的提取任务，从可用余额追补差额，保证账目一致"),
            fixed("mediaTask.ossCompensate", "mediaTask.ossCompensate()", "媒体OSS持久化补偿",
                    "0 0/5 * * * ?", 300, 120, 1800, ScheduleConstants.MISFIRE_DO_NOTHING,
                    "修复已成功但 oss_url 为空的媒体任务：重试下载上游产物并落地 OSS，停用会导致用户看不到产物"),

            // ==================== 可选任务（允许开关） ====================
            optional("logArchiveTask.archive", "logArchiveTask.archive(10)", "日志归档",
                    "0 30 3 * * ?", DAILY_INTERVAL_SECONDS, ScheduleConstants.Status.NORMAL.getValue(),
                    "每日归档并删除超过保留天数的操作日志/登录日志，控制日志表体量"),
            optional("moderationLogTask.cleanExpired", "moderationLogTask.cleanExpired()", "图片审查日志清理",
                    "0 30 3 * * ?", DAILY_INTERVAL_SECONDS, ScheduleConstants.Status.NORMAL.getValue(),
                    "每日清理超过保留天数的图片审查日志，防止日志表无限增长"),
            optional("modelHealthTask.archive", "modelHealthTask.archive(30)", "模型健康统计归档",
                    "0 40 3 * * ?", DAILY_INTERVAL_SECONDS, ScheduleConstants.Status.NORMAL.getValue(),
                    "每日把超过保留天数的模型健康统计导出为本地txt存档后删除，保持统计表体量恒定"),
            optional("mediaTask.compensate", "mediaTask.compensate()", "媒体补偿轮询(降级备用)",
                    "0 0/5 * * * ?", 300, ScheduleConstants.Status.PAUSE.getValue(),
                    "旧版补偿轮询，仅作为统一调度中心失效时的降级手段，平时保持暂停")
    );

    /**
     * 全部任务定义
     */
    public static List<JobDef> all()
    {
        return DEFS;
    }

    /**
     * 规范化调用目标：截断参数、去空白，得到 bean.method 形式用于匹配
     */
    public static String normalize(String invokeTarget)
    {
        if (StringUtils.isEmpty(invokeTarget))
        {
            return "";
        }
        return StringUtils.substringBefore(invokeTarget, "(").trim();
    }

    /**
     * 按调用目标查找任务定义（忽略参数）
     */
    public static JobDef find(String invokeTarget)
    {
        String target = normalize(invokeTarget);
        for (JobDef def : DEFS)
        {
            if (Objects.equals(def.getTarget(), target))
            {
                return def;
            }
        }
        return null;
    }

    /**
     * 是否为固定任务（禁止暂停/删除）
     */
    public static boolean isFixed(String invokeTarget)
    {
        JobDef def = find(invokeTarget);
        return def != null && def.isFixed();
    }
}
