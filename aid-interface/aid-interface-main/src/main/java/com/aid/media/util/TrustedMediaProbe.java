package com.aid.media.util;

import com.aid.common.exception.ServiceException;
import com.aid.compose.config.MpsConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 仅探测已取得的完整本地媒体字节，禁止探测器访问网络或外部播放列表。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrustedMediaProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final MpsConfigManager config;

    public Metadata audio(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new ServiceException("样本大小不支持");
        Path file = null;
        Process process = null;
        try {
            file = Files.createTempFile("aid-media-probe-", ".bin");
            Files.write(file, bytes);
            process = new ProcessBuilder(config.getMpsProperties().getFfprobePath(), "-v", "error",
                    "-protocol_whitelist", "file,pipe", "-select_streams", "a:0", "-show_entries",
                    "stream=codec_name,sample_rate,channels,duration:format=duration,format_name", "-of", "json", file.toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) throw new ServiceException("样本解析失败");
            byte[] output = process.getInputStream().readNBytes(65537);
            if (output.length > 65536) throw new ServiceException("样本解析失败");
            JsonNode root = MAPPER.readTree(output);
            JsonNode stream = root.path("streams").path(0);
            BigDecimal seconds = positiveDuration(stream.path("duration").asText());
            if (seconds == null) seconds = positiveDuration(root.path("format").path("duration").asText());
            if (seconds == null) throw new ServiceException("样本时长不可用");
            long millis = seconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact();
            int sampleRate = Integer.parseInt(stream.path("sample_rate").asText());
            int channels = stream.path("channels").asInt();
            String format = root.path("format").path("format_name").asText();
            if (millis <= 0 || sampleRate <= 0 || channels <= 0 || format.isBlank()) throw new ServiceException("样本解析失败");
            return new Metadata(millis, bytes.length, sampleRate, channels, format, stream.path("codec_name").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("样本解析中断");
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) {
            log.info("媒体样本探测失败: {}", ex.getClass().getSimpleName());
            throw new ServiceException("样本解析失败");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (file != null) {
                try { Files.deleteIfExists(file); }
                catch (Exception ex) { log.warn("媒体探测临时文件待清理: {}", file); }
            }
        }
    }

    private static BigDecimal positiveDuration(String value) {
        try {
            BigDecimal result = new BigDecimal(value);
            return result.signum() > 0 ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Metadata(long durationMs, long sizeBytes, int sampleRate, int channels, String format, String codec) { }
}
