package com.aid.modelhealth.service.impl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidModelHealthStat;
import com.aid.aid.service.IAidModelHealthStatService;
import com.aid.modelhealth.service.ModelHealthArchiveService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型健康统计归档实现：每批按主键顺序读取过期行，逐行以制表符文本写入本地 txt，
 * 写盘成功后再按主键删除，循环直到清完。与日志归档（LogArchiveServiceImpl）同款套路。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelHealthArchiveServiceImpl implements ModelHealthArchiveService {

    /** 单批处理条数 */
    private static final int BATCH_SIZE = 1000;
    /** 防御性循环上限，避免极端情况下死循环 */
    private static final int MAX_LOOPS = 10000;
    /** txt 首行表头 */
    private static final String HEADER =
            "bucket_time\tprovider_code\tmodel_code\tmedia_type\tsuccess_count\tfail_count\ttotal_latency_ms\tlast_error_message";

    /** 归档根目录（与日志归档同根），默认项目运行目录下的 log-archive/model-health */
    @Value("${log.archive.path:./log-archive}")
    private String archiveRoot;

    private final IAidModelHealthStatService modelHealthStatService;

    @Override
    public int archiveAndCleanup(int keepDays) {
        int days = keepDays > 0 ? keepDays : 1;
        // 截止点取「keepDays 天前的当天 00:00」，保证保留窗口是整天
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        Date cutoff = calendar.getTime();

        Path dir = Paths.get(archiveRoot, "model-health");
        Path file = dir.resolve("model_health_"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        int total = 0;
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Writer writer = null;
        try {
            int loops = 0;
            while (loops++ < MAX_LOOPS) {
                // 按主键升序小批量捞取过期行（bucket_time 有索引）
                List<AidModelHealthStat> batch = modelHealthStatService.list(
                        Wrappers.<AidModelHealthStat>lambdaQuery()
                                .lt(AidModelHealthStat::getBucketTime, cutoff)
                                .orderByAsc(AidModelHealthStat::getId)
                                .last("LIMIT " + BATCH_SIZE));
                if (CollectionUtil.isEmpty(batch)) {
                    break;
                }
                // 首次有数据才建文件（无过期数据时不产生空文件）
                if (writer == null) {
                    Files.createDirectories(dir);
                    writer = new BufferedWriter(new OutputStreamWriter(
                            Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                            StandardCharsets.UTF_8));
                    writer.write(HEADER);
                    writer.write(System.lineSeparator());
                }
                List<Long> ids = new ArrayList<>(batch.size());
                for (AidModelHealthStat stat : batch) {
                    writer.write(toLine(stat, timeFormat));
                    writer.write(System.lineSeparator());
                    ids.add(stat.getId());
                }
                // 先落盘再删库，宁可重复归档也不丢数据
                writer.flush();
                modelHealthStatService.removeByIds(ids);
                total += batch.size();
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("模型健康统计归档异常, 已归档={}", total, e);
        } finally {
            closeQuietly(writer);
        }
        if (total > 0) {
            log.info("模型健康统计归档完成, keepDays={}, 归档并清理={}, 文件={}", days, total, file);
        }
        return total;
    }

    /** 单行制表符文本（错误信息内的换行/制表符替换为空格，保证一行一条） */
    private String toLine(AidModelHealthStat stat, SimpleDateFormat timeFormat) {
        String error = StrUtil.nullToEmpty(stat.getLastErrorMessage())
                .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return timeFormat.format(stat.getBucketTime()) + "\t"
                + StrUtil.nullToEmpty(stat.getProviderCode()) + "\t"
                + StrUtil.nullToEmpty(stat.getModelCode()) + "\t"
                + StrUtil.nullToEmpty(stat.getMediaType()) + "\t"
                + (stat.getSuccessCount() == null ? 0 : stat.getSuccessCount()) + "\t"
                + (stat.getFailCount() == null ? 0 : stat.getFailCount()) + "\t"
                + (stat.getTotalLatencyMs() == null ? 0 : stat.getTotalLatencyMs()) + "\t"
                + error;
    }

    /** 静默关闭写入器（存档文件可丢失，关闭异常不影响主流程） */
    private void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                log.warn("模型健康统计归档文件关闭异常: err={}", e.getMessage());
            }
        }
    }
}
