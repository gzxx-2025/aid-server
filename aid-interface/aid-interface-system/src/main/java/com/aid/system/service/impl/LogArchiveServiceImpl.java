package com.aid.system.service.impl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.core.domain.SysLogininfor;
import com.aid.core.domain.SysOperLog;
import com.aid.system.mapper.SysLogininforMapper;
import com.aid.system.mapper.SysOperLogMapper;
import com.aid.system.service.ILogArchiveService;

/**
 * 日志归档服务实现（需求6）。
 *
 * 每批读取早于截止时间的日志，逐条以 JSON 行写入按 2MB 滚动的本地文件，
 * 写盘成功后再按主键从库删除，循环直到无更多记录。两类日志分目录存放。
 *
 * @author 视觉AID
 */
@Service
public class LogArchiveServiceImpl implements ILogArchiveService {

    private static final Logger log = LoggerFactory.getLogger(LogArchiveServiceImpl.class);

    /** 单批处理条数 */
    private static final int BATCH_SIZE = 1000;
    /** 单文件大小上限（2MB） */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** 防御性循环上限，避免极端情况下死循环 */
    private static final int MAX_LOOPS = 100000;

    /** 归档根目录，默认项目运行目录下的 log-archive；可通过 log.archive.path 覆盖 */
    @Value("${log.archive.path:./log-archive}")
    private String archiveRoot;

    @Autowired
    private SysOperLogMapper operLogMapper;

    @Autowired
    private SysLogininforMapper logininforMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public int archiveOperLog(int keepDays) {
        Date cutoff = cutoffOf(keepDays);
        Path dir = Paths.get(archiveRoot, "oper");
        String prefix = "oper-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        int total = 0;
        try (RotatingWriter writer = new RotatingWriter(dir, prefix)) {
            int loops = 0;
            while (loops++ < MAX_LOOPS) {
                List<SysOperLog> batch = operLogMapper.selectOperLogBeforeTime(cutoff, BATCH_SIZE);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                Long[] ids = new Long[batch.size()];
                for (int i = 0; i < batch.size(); i++) {
                    SysOperLog item = batch.get(i);
                    writer.writeLine(toJson(item));
                    ids[i] = item.getOperId();
                }
                writer.flush();
                operLogMapper.deleteOperLogByIds(ids);
                total += batch.size();
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("操作日志归档异常, 已归档={}", total, e);
        }
        if (total > 0) {
            log.info("操作日志归档完成, 归档并清理={}, 目录={}", total, dir);
        }
        return total;
    }

    @Override
    public int archiveLogininfor(int keepDays) {
        Date cutoff = cutoffOf(keepDays);
        Path dir = Paths.get(archiveRoot, "login");
        String prefix = "login-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        int total = 0;
        try (RotatingWriter writer = new RotatingWriter(dir, prefix)) {
            int loops = 0;
            while (loops++ < MAX_LOOPS) {
                List<SysLogininfor> batch = logininforMapper.selectLogininforBeforeTime(cutoff, BATCH_SIZE);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                Long[] ids = new Long[batch.size()];
                for (int i = 0; i < batch.size(); i++) {
                    SysLogininfor item = batch.get(i);
                    writer.writeLine(toJson(item));
                    ids[i] = item.getInfoId();
                }
                writer.flush();
                logininforMapper.deleteLogininforByIds(ids);
                total += batch.size();
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("登录日志归档异常, 已归档={}", total, e);
        }
        if (total > 0) {
            log.info("登录日志归档完成, 归档并清理={}, 目录={}", total, dir);
        }
        return total;
    }

    /** 计算保留期截止时间：当前时间往前推 keepDays 天（不足时按 1 天兜底） */
    private Date cutoffOf(int keepDays) {
        int days = keepDays > 0 ? keepDays : 1;
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -days);
        return c.getTime();
    }

    /** 单条记录序列化为 JSON 文本，失败时降级为 toString，确保不丢数据 */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    /**
     * 按大小滚动的文件写入器：写入字节累计达到上限即关闭当前文件、开启下一个分片。
     * 文件命名：{prefix}-{partIndex}.log（如 oper-20260628-120000-1.log）。
     */
    private static final class RotatingWriter implements AutoCloseable {
        private final Path dir;
        private final String prefix;
        private int partIndex = 0;
        private long currentBytes = 0;
        private Writer writer;

        RotatingWriter(Path dir, String prefix) throws IOException {
            this.dir = dir;
            this.prefix = prefix;
            Files.createDirectories(dir);
            openNext();
        }

        private void openNext() throws IOException {
            close();
            partIndex++;
            currentBytes = 0;
            Path file = dir.resolve(prefix + "-" + partIndex + ".log");
            writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(file), StandardCharsets.UTF_8));
        }

        void writeLine(String line) throws IOException {
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            if (currentBytes > 0 && currentBytes + bytes.length > MAX_FILE_BYTES) {
                openNext();
            }
            writer.write(line);
            writer.write(System.lineSeparator());
            currentBytes += bytes.length;
        }

        void flush() throws IOException {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public void close() {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                    // 关闭异常不影响主流程
                } finally {
                    writer = null;
                }
            }
        }
    }
}
