package com.aid.quartz.service.impl;

import com.alibaba.druid.pool.DruidDataSource;
import com.aid.common.constant.ScheduleConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.exception.job.TaskException;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.spring.SpringUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.aid.quartz.domain.SysJob;
import com.aid.quartz.domain.SysJobDbPressure;
import com.aid.quartz.domain.SysJobFrequencyAdvice;
import com.aid.quartz.mapper.SysJobMapper;
import com.aid.quartz.registry.FixedJobRegistry;
import com.aid.quartz.service.ISysJobService;
import com.aid.quartz.util.CronIntervalUtils;
import com.aid.quartz.util.CronUtils;
import com.aid.quartz.util.ScheduleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 定时任务调度信息 服务层
 * 
 * @author AID
 */
@Slf4j
@Service
public class SysJobServiceImpl implements ISysJobService
{
    /** 压力评分：连接池使用率权重 */
    private static final double PRESSURE_WEIGHT_USAGE = 0.7;

    /** 压力评分：等待线程数权重 */
    private static final double PRESSURE_WEIGHT_WAIT = 0.3;

    /** 压力级别阈值：低压力上限 */
    private static final double PRESSURE_LOW_LIMIT = 0.4;

    /** 压力级别阈值：中压力上限 */
    private static final double PRESSURE_MEDIUM_LIMIT = 0.7;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SysJobMapper jobMapper;

    /**
     * 项目启动时，初始化定时器 主要是防止手动修改数据库导致未同步到定时任务处理（注：不能手动修改数据库ID和任务组名，否则会导致脏数据）
     * 同时按 FixedJobRegistry 注册表自愈：缺失的系统任务自动补齐种子数据、
     * 固定任务被暂停自动恢复、频率越界自动重置为基准频率、jobType 自动矫正。
     */
    @PostConstruct
    public void init() throws SchedulerException, TaskException
    {
        scheduler.clear();
        // 先按注册表自愈数据库中的系统任务，再统一装载调度器
        healRegistryJobs();
        List<SysJob> jobList = jobMapper.selectJobAll();
        for (SysJob job : jobList)
        {
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
    }

    /**
     * 按注册表自愈系统任务：补齐缺失种子、矫正 jobType、恢复被暂停的固定任务、重置越界频率
     */
    private void healRegistryJobs()
    {
        List<SysJob> jobList = jobMapper.selectJobAll();
        for (FixedJobRegistry.JobDef def : FixedJobRegistry.all())
        {
            SysJob existing = null;
            for (SysJob job : jobList)
            {
                if (Objects.equals(FixedJobRegistry.normalize(job.getInvokeTarget()), def.getTarget()))
                {
                    existing = job;
                    break;
                }
            }
            if (Objects.isNull(existing))
            {
                // 缺失：按注册表种子补齐（创建时间由 Mapper sysdate() 填充，创建者=system）
                SysJob seed = def.toSeedJob();
                jobMapper.insertJob(seed);
                log.info("系统任务缺失，已按注册表补齐: {} ({})", def.getJobName(), def.getInvokeTarget());
                continue;
            }
            boolean changed = false;
            // 矫正任务类型
            if (!Objects.equals(existing.getJobType(), def.getJobType()))
            {
                existing.setJobType(def.getJobType());
                changed = true;
            }
            if (def.isFixed())
            {
                // 固定任务被暂停：强制恢复运行
                if (Objects.equals(ScheduleConstants.Status.PAUSE.getValue(), existing.getStatus()))
                {
                    existing.setStatus(ScheduleConstants.Status.NORMAL.getValue());
                    changed = true;
                    log.warn("固定任务处于暂停状态，已自动恢复: {}", existing.getJobName());
                }
                // 频率越界：重置为基准频率
                if (!CronIntervalUtils.isWithinRange(existing.getCronExpression(),
                        def.getMinIntervalSeconds(), def.getMaxIntervalSeconds()))
                {
                    log.warn("固定任务频率越界，已重置为基准频率: {} [{} -> {}]",
                            existing.getJobName(), existing.getCronExpression(), def.getDefaultCron());
                    existing.setCronExpression(def.getDefaultCron());
                    changed = true;
                }
            }
            if (changed)
            {
                existing.setUpdateBy("system");
                jobMapper.updateJob(existing);
            }
        }
    }

    /**
     * 获取quartz调度器的计划任务列表
     * 
     * @param job 调度信息
     * @return
     */
    @Override
    public List<SysJob> selectJobList(SysJob job)
    {
        return jobMapper.selectJobList(job);
    }

    /**
     * 通过调度任务ID查询调度信息
     * 
     * @param jobId 调度任务ID
     * @return 调度任务对象信息
     */
    @Override
    public SysJob selectJobById(Long jobId)
    {
        return jobMapper.selectJobById(jobId);
    }

    /**
     * 暂停任务（固定任务禁止暂停）
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int pauseJob(SysJob job) throws SchedulerException
    {
        // 固定任务为系统必备，暂停会导致任务卡死、计费不一致等数据错误
        assertNotFixed(job, "暂停");
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 恢复任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resumeJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        job.setStatus(ScheduleConstants.Status.NORMAL.getValue());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            scheduler.resumeJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 删除任务后，所对应的trigger也将被删除（固定任务禁止删除）
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteJob(SysJob job) throws SchedulerException
    {
        // 固定任务为系统必备，删除会造成业务数据错误
        assertNotFixed(job, "删除");
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        int rows = jobMapper.deleteJobById(jobId);
        if (rows > 0)
        {
            scheduler.deleteJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 批量删除调度信息
     * 
     * @param jobIds 需要删除的任务ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobByIds(Long[] jobIds) throws SchedulerException
    {
        for (Long jobId : jobIds)
        {
            SysJob job = jobMapper.selectJobById(jobId);
            if (Objects.isNull(job))
            {
                continue;
            }
            deleteJob(job);
        }
    }

    /**
     * 任务调度状态修改（固定任务禁止暂停）
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(SysJob job) throws SchedulerException
    {
        int rows = 0;
        String status = job.getStatus();
        if (ScheduleConstants.Status.NORMAL.getValue().equals(status))
        {
            rows = resumeJob(job);
        }
        else if (ScheduleConstants.Status.PAUSE.getValue().equals(status))
        {
            rows = pauseJob(job);
        }
        return rows;
    }

    /**
     * 立即运行任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean run(SysJob job) throws SchedulerException
    {
        boolean result = false;
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        SysJob properties = selectJobById(job.getJobId());
        // 参数
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ScheduleConstants.TASK_PROPERTIES, properties);
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
        if (scheduler.checkExists(jobKey))
        {
            result = true;
            scheduler.triggerJob(jobKey, dataMap);
        }
        return result;
    }

    /**
     * 新增任务（系统注册表内的任务由启动自愈统一管理，禁止手工重复创建）
     * 
     * @param job 调度信息 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertJob(SysJob job) throws SchedulerException, TaskException
    {
        // 注册表内任务由启动自愈统一创建，手工重复添加会导致重复调度
        FixedJobRegistry.JobDef def = FixedJobRegistry.find(job.getInvokeTarget());
        if (Objects.nonNull(def))
        {
            log.error("新增定时任务被拒绝：调用目标 {} 属于系统注册表任务，由启动自愈统一管理", job.getInvokeTarget());
            throw new ServiceException("系统任务禁止手工创建");
        }
        // 手工新建的任务一律标记为可选任务
        job.setJobType(ScheduleConstants.JOB_TYPE_OPTIONAL);
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        int rows = jobMapper.insertJob(job);
        if (rows > 0)
        {
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
        return rows;
    }

    /**
     * 更新任务的时间表达式（固定任务仅允许在安全频率范围内调整 cron，禁止修改调用目标/状态/类型）
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateJob(SysJob job) throws SchedulerException, TaskException
    {
        SysJob properties = selectJobById(job.getJobId());
        if (Objects.isNull(properties))
        {
            log.error("修改定时任务失败：任务不存在, jobId={}", job.getJobId());
            throw new ServiceException("任务不存在");
        }
        FixedJobRegistry.JobDef def = FixedJobRegistry.find(properties.getInvokeTarget());
        boolean fixed = isFixedJob(properties, def);
        if (fixed)
        {
            // 固定任务禁止修改调用目标，防止把系统必备任务改成别的逻辑
            if (StringUtils.isNotEmpty(job.getInvokeTarget())
                    && !Objects.equals(FixedJobRegistry.normalize(job.getInvokeTarget()),
                            FixedJobRegistry.normalize(properties.getInvokeTarget())))
            {
                log.error("固定任务禁止修改调用目标: jobId={}, {} -> {}",
                        job.getJobId(), properties.getInvokeTarget(), job.getInvokeTarget());
                throw new ServiceException("固定任务禁止改目标");
            }
            // 固定任务禁止借更新接口暂停
            if (Objects.equals(ScheduleConstants.Status.PAUSE.getValue(), job.getStatus()))
            {
                log.error("固定任务禁止暂停: jobId={}, jobName={}", job.getJobId(), properties.getJobName());
                throw new ServiceException("固定任务禁止暂停");
            }
            // 固定任务频率必须落在安全范围内
            if (Objects.nonNull(def) && StringUtils.isNotEmpty(job.getCronExpression())
                    && !CronIntervalUtils.isWithinRange(job.getCronExpression(),
                            def.getMinIntervalSeconds(), def.getMaxIntervalSeconds()))
            {
                log.error("固定任务频率越界: jobId={}, cron={}, 安全范围=[{}s, {}s]", job.getJobId(),
                        job.getCronExpression(), def.getMinIntervalSeconds(), def.getMaxIntervalSeconds());
                throw new ServiceException("频率超出安全范围");
            }
        }
        // 任务类型以数据库/注册表为准，不允许前端篡改
        job.setJobType(Objects.nonNull(def) ? def.getJobType() : properties.getJobType());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            // 重建调度需要完整字段，重新查库获取更新后的任务
            SysJob updated = selectJobById(job.getJobId());
            updateSchedulerJob(updated, properties.getJobGroup());
        }
        return rows;
    }

    /**
     * 更新任务
     * 
     * @param job 任务对象
     * @param jobGroup 任务组名
     */
    public void updateSchedulerJob(SysJob job, String jobGroup) throws SchedulerException, TaskException
    {
        Long jobId = job.getJobId();
        // 判断是否存在
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
        if (scheduler.checkExists(jobKey))
        {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(jobKey);
        }
        ScheduleUtils.createScheduleJob(scheduler, job);
    }

    /**
     * 校验cron表达式是否有效
     * 
     * @param cronExpression 表达式
     * @return 结果
     */
    @Override
    public boolean checkCronExpressionIsValid(String cronExpression)
    {
        return CronUtils.isValid(cronExpression);
    }

    /**
     * 采集当前数据库承载压力（Druid 主数据源连接池指标），采集失败按低压力兜底
     */
    @Override
    public SysJobDbPressure getDbPressure()
    {
        SysJobDbPressure pressure = new SysJobDbPressure();
        try
        {
            Object bean = SpringUtils.getBean("masterDataSource");
            if (!(bean instanceof DruidDataSource))
            {
                // 非 Druid 数据源无法采集，按低压力兜底
                pressure.setAvailable(false);
                pressure.setScore(0d);
                pressure.setLevel("LOW");
                return pressure;
            }
            DruidDataSource ds = (DruidDataSource) bean;
            int active = ds.getActiveCount();
            int maxActive = Math.max(1, ds.getMaxActive());
            int pooling = ds.getPoolingCount();
            int waitThread = ds.getWaitThreadCount();
            // 使用率 = 活跃连接 / 最大连接；等待线程按最大连接归一后加权
            double usage = Math.min(1d, active / (double) maxActive);
            double waitRatio = Math.min(1d, waitThread / (double) maxActive);
            double score = Math.min(1d, PRESSURE_WEIGHT_USAGE * usage + PRESSURE_WEIGHT_WAIT * waitRatio);
            pressure.setAvailable(true);
            pressure.setActiveCount(active);
            pressure.setMaxActive(maxActive);
            pressure.setPoolingCount(pooling);
            pressure.setWaitThreadCount(waitThread);
            pressure.setPoolUsagePercent(Math.round(usage * 10000d) / 100d);
            pressure.setScore(Math.round(score * 100d) / 100d);
            pressure.setLevel(resolvePressureLevel(score));
        }
        catch (Exception e)
        {
            // 采集失败不影响功能，按低压力兜底
            log.warn("采集数据库压力失败，按低压力兜底: {}", e.getMessage());
            pressure.setAvailable(false);
            pressure.setScore(0d);
            pressure.setLevel("LOW");
        }
        return pressure;
    }

    /**
     * 按当前数据库压力动态计算系统任务的推荐频率。
     * 推荐间隔 = 基准间隔 × (1 + 压力评分)，即压力越高任务越稀疏（最高放缓一倍），
     * 固定任务的推荐值再收敛到 [min, max] 安全范围内；按日任务不参与动态计算。
     */
    @Override
    public List<SysJobFrequencyAdvice> listFrequencyAdvice()
    {
        SysJobDbPressure pressure = getDbPressure();
        double score = Objects.isNull(pressure.getScore()) ? 0d : pressure.getScore();
        List<SysJob> jobList = jobMapper.selectJobAll();
        List<SysJobFrequencyAdvice> result = new ArrayList<>();
        for (FixedJobRegistry.JobDef def : FixedJobRegistry.all())
        {
            SysJobFrequencyAdvice advice = new SysJobFrequencyAdvice();
            advice.setJobName(def.getJobName());
            advice.setTarget(def.getTarget());
            advice.setJobType(def.getJobType());
            advice.setBaseCron(def.getDefaultCron());
            advice.setMinIntervalSeconds(def.getMinIntervalSeconds());
            advice.setMaxIntervalSeconds(def.getMaxIntervalSeconds());
            advice.setDaily(def.isDaily());
            advice.setRemark(def.getRemark());
            // 关联数据库中的实际任务，带出当前 cron / 状态
            for (SysJob job : jobList)
            {
                if (Objects.equals(FixedJobRegistry.normalize(job.getInvokeTarget()), def.getTarget()))
                {
                    advice.setJobId(job.getJobId());
                    advice.setCurrentCron(job.getCronExpression());
                    advice.setCurrentStatus(job.getStatus());
                    advice.setCurrentIntervalSeconds(
                            CronIntervalUtils.estimateIntervalSeconds(job.getCronExpression()));
                    break;
                }
            }
            if (def.isDaily())
            {
                // 按日任务频率固定，推荐即基准
                advice.setRecommendedCron(def.getDefaultCron());
                advice.setRecommendedIntervalSeconds((long) def.getBaseIntervalSeconds());
            }
            else
            {
                // 压力越高间隔越大：基准 × (1 + score)，再收敛到安全范围
                int recommended = (int) Math.round(def.getBaseIntervalSeconds() * (1d + score));
                if (def.getMinIntervalSeconds() > 0)
                {
                    recommended = Math.max(recommended, def.getMinIntervalSeconds());
                }
                if (def.getMaxIntervalSeconds() > 0)
                {
                    recommended = Math.min(recommended, def.getMaxIntervalSeconds());
                }
                String recommendedCron = CronIntervalUtils.buildCron(recommended);
                advice.setRecommendedCron(recommendedCron);
                advice.setRecommendedIntervalSeconds(
                        CronIntervalUtils.estimateIntervalSeconds(recommendedCron));
            }
            result.add(advice);
        }
        return result;
    }

    /**
     * 压力评分转压力级别
     */
    private String resolvePressureLevel(double score)
    {
        if (score < PRESSURE_LOW_LIMIT)
        {
            return "LOW";
        }
        if (score < PRESSURE_MEDIUM_LIMIT)
        {
            return "MEDIUM";
        }
        return "HIGH";
    }

    /**
     * 判定是否固定任务：注册表定义优先，其次数据库 job_type 字段
     */
    private boolean isFixedJob(SysJob job, FixedJobRegistry.JobDef def)
    {
        if (Objects.nonNull(def))
        {
            return def.isFixed();
        }
        return Objects.equals(ScheduleConstants.JOB_TYPE_FIXED, job.getJobType());
    }

    /**
     * 固定任务保护断言：禁止暂停/删除
     */
    private void assertNotFixed(SysJob job, String action)
    {
        SysJob record = job;
        // 传入对象可能只有 jobId，查库拿完整记录
        if (StringUtils.isEmpty(record.getInvokeTarget()) && Objects.nonNull(record.getJobId()))
        {
            SysJob db = jobMapper.selectJobById(record.getJobId());
            if (Objects.nonNull(db))
            {
                record = db;
            }
        }
        FixedJobRegistry.JobDef def = FixedJobRegistry.find(record.getInvokeTarget());
        if (isFixedJob(record, def))
        {
            log.error("固定任务禁止{}: jobId={}, jobName={}", action, record.getJobId(), record.getJobName());
            throw new ServiceException("固定任务禁止" + action);
        }
    }
}
