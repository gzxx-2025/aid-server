package com.aid.compose.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/** 校验自定义 FFmpeg 运行时是否具备成片合成所需能力。 */
@Component
public class FfmpegRuntimeValidator
{
    private static final String MINIMUM_VERSION = "5.1";
    private static final int COMMAND_TIMEOUT_SECONDS = 60;
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^ffmpeg version\\s+[^0-9]*([0-9]+(?:\\.[0-9]+)+).*$", Pattern.MULTILINE);
    private static final List<String> REQUIRED_ENCODERS = List.of("libx264", "libx265", "aac");
    private static final List<String> REQUIRED_FILTERS = List.of(
            "tpad", "apad", "scale", "pad", "fps", "setpts", "concat", "overlay", "drawtext",
            "amix", "alimiter", "aresample", "atrim", "asetpts", "aformat");
    private static final String CJK_TEST_TEXT = "中文测试，字幕正常。";
    private static final int[] REQUIRED_CJK_CODE_POINTS = CJK_TEST_TEXT.codePoints().distinct().toArray();
    private static final Pattern CHINESE_LANGUAGE_PATTERN = Pattern.compile(
            "(^|[|,\\s])zh(?:-[a-z]{2})?([|,\\s]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 校验用户填写的绝对路径。
     *
     * @param ffmpegPath FFmpeg 路径
     * @param ffprobePath FFprobe 路径
     * @throws IOException 文件或进程校验失败
     */
    public void validate(String ffmpegPath, String ffprobePath) throws IOException
    {
        Path ffmpeg = requireExecutable(ffmpegPath, "FFmpeg");
        Path ffprobe = requireExecutable(ffprobePath, "FFprobe");
        Path workDir = Files.createTempDirectory("aid-ffmpeg-check-");
        try
        {
            CommandResult versionResult = execute(workDir, ffmpeg.toString(), "-version");
            Matcher matcher = VERSION_PATTERN.matcher(versionResult.output());
            String currentVersion = versionResult.exitCode() == 0 && matcher.find()
                    ? matcher.group(1) : "无法识别";
            String versionIssue = "无法识别".equals(currentVersion)
                    ? "版本输出异常=" + summarize(versionResult.output())
                    : versionAtLeast(currentVersion, MINIMUM_VERSION) ? "" : "版本过低";
            CommandResult probeResult = execute(workDir, ffprobe.toString(), "-version");

            CommandResult encoderResult = execute(workDir, ffmpeg.toString(), "-hide_banner", "-encoders");
            CommandResult filterResult = execute(workDir, ffmpeg.toString(), "-hide_banner", "-filters");
            List<String> missingEncoders = findMissing(encoderResult.output(), REQUIRED_ENCODERS);
            List<String> missingFilters = findMissing(filterResult.output(), REQUIRED_FILTERS);
            if (!versionIssue.isEmpty() || probeResult.exitCode() != 0
                    || encoderResult.exitCode() != 0 || filterResult.exitCode() != 0
                    || !missingEncoders.isEmpty() || !missingFilters.isEmpty())
            {
                throw new IOException("FFmpeg能力不完整，当前=" + currentVersion + "，最低=" + MINIMUM_VERSION
                        + "，版本=" + StrUtil.blankToDefault(versionIssue, "符合")
                        + "，FFprobe=" + (probeResult.exitCode() == 0 ? "正常" : "无法运行")
                        + "，缺少编码器=" + (encoderResult.exitCode() == 0 ? missingEncoders : "无法读取")
                        + "，缺少滤镜=" + (filterResult.exitCode() == 0 ? missingFilters : "无法读取"));
            }
            runSmokeTest(ffmpeg, ffprobe, workDir);
        }
        finally
        {
            deleteRecursively(workDir);
        }
    }

    /** 校验字幕字体的文件权限、中文字符集与 FFmpeg drawtext 加载能力。 */
    public void validateFont(String ffmpegPath, String ffprobePath, String fontPath)
            throws FontValidationException
    {
        Path font = requireFont(fontPath);
        Path workDir = null;
        try
        {
            Path ffmpeg = requireExecutable(ffmpegPath, "FFmpeg");
            Path ffprobe = requireExecutable(ffprobePath, "FFprobe");
            workDir = Files.createTempDirectory("aid-font-check-");
            CommandResult languageResult = execute(workDir,
                    "fc-query", "--format=%{lang}\\n", font.toString());
            CommandResult charsetResult = execute(workDir,
                    "fc-query", "--format=%{charset}\\n", font.toString());
            if (languageResult.exitCode() != 0 || charsetResult.exitCode() != 0)
            {
                throw fontError("字体路径无效", "fontconfig无法读取字体，lang="
                        + summarize(languageResult.output()) + "，charset=" + summarize(charsetResult.output()), null);
            }
            if (!CHINESE_LANGUAGE_PATTERN.matcher(languageResult.output()).find()
                    || !containsRequiredCodePoints(charsetResult.output()))
            {
                throw fontError("字体不支持中文", "字体未同时覆盖lang=zh和测试文本“"
                        + CJK_TEST_TEXT + "”，lang=" + summarize(languageResult.output())
                        + "，charset=" + summarize(charsetResult.output()), null);
            }
            runFontSmokeTest(ffmpeg, ffprobe, font, workDir);
        }
        catch (FontValidationException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw fontError("FFmpeg字体加载失败", "字体校验命令执行失败: " + e.getMessage(), e);
        }
        finally
        {
            deleteRecursively(workDir);
        }
    }

    private Path requireFont(String value) throws FontValidationException
    {
        if (StrUtil.isBlank(value) || value.chars().anyMatch(Character::isISOControl))
        {
            throw fontError("字体路径无效", "字体路径为空或包含控制字符", null);
        }
        Path path;
        try
        {
            path = Path.of(value.trim()).normalize();
        }
        catch (RuntimeException e)
        {
            throw fontError("字体路径无效", "字体路径格式错误", e);
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path))
        {
            throw fontError("字体路径无效", "字体必须是普通文件或指向普通文件的有效软链接: " + path, null);
        }
        if (!Files.isReadable(path))
        {
            throw fontError("字体文件不可读", "当前Server运行账户无法读取字体: " + path, null);
        }
        return path;
    }

    private boolean containsRequiredCodePoints(String charset)
    {
        for (int codePoint : REQUIRED_CJK_CODE_POINTS)
        {
            if (!charsetContains(charset, codePoint))
            {
                return false;
            }
        }
        return true;
    }

    private boolean charsetContains(String charset, int codePoint)
    {
        for (String token : StrUtil.blankToDefault(charset, "").split("\\s+"))
        {
            if (!token.matches("(?i)[0-9a-f]+(?:-[0-9a-f]+)?"))
            {
                continue;
            }
            try
            {
                int separator = token.indexOf('-');
                int start = Integer.parseInt(separator < 0 ? token : token.substring(0, separator), 16);
                int end = Integer.parseInt(separator < 0 ? token : token.substring(separator + 1), 16);
                if (start <= codePoint && codePoint <= end)
                {
                    return true;
                }
            }
            catch (NumberFormatException ignored)
            {
                // 跳过fontconfig输出中的异常片段，最终由缺字错误统一收口。
            }
        }
        return false;
    }

    private void runFontSmokeTest(Path ffmpeg, Path ffprobe, Path font, Path workDir)
            throws IOException
    {
        Path output = workDir.resolve("aid-cjk-font-smoke.mp4");
        String filter = "drawtext=fontfile='" + escapeFilterValue(font.toString())
                + "':text='" + CJK_TEST_TEXT + "':fontcolor=white:fontsize=24:x=10:y=10";
        CommandResult result = execute(workDir,
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=320x180:d=0.5",
                "-vf", filter, "-an", "-t", "0.5", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                output.toString());
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L)
        {
            throw fontError("FFmpeg字体加载失败", "drawtext中文字体验证失败: "
                    + summarize(result.output()), null);
        }
        try
        {
            assertStream(ffprobe, workDir, output, "v:0", "video");
        }
        catch (IOException e)
        {
            throw fontError("FFmpeg字体加载失败", "ffprobe无法读取字体测试产物: " + e.getMessage(), e);
        }
    }

    private String escapeFilterValue(String value)
    {
        return StrUtil.blankToDefault(value, "").replace("\\", "\\\\")
                .replace(":", "\\:").replace("'", "\\'")
                .replace(",", "\\,").replace(";", "\\;")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("%", "\\%").replace("\n", " ").replace("\r", " ");
    }

    private FontValidationException fontError(String userMessage, String detail, Throwable cause)
    {
        return new FontValidationException(userMessage, detail, cause);
    }

    private Path requireExecutable(String value, String label) throws IOException
    {
        if (StrUtil.isBlank(value))
        {
            throw new IOException(label + "路径为空");
        }
        Path path;
        try
        {
            path = Path.of(value.trim()).normalize();
        }
        catch (RuntimeException e)
        {
            throw new IOException(label + "路径格式错误", e);
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path))
        {
            throw new IOException(label + "必须是可执行绝对路径: " + path);
        }
        return path;
    }

    private void runSmokeTest(Path ffmpeg, Path ffprobe, Path workDir) throws IOException
    {
        Path output = workDir.resolve("aid-ffmpeg-smoke.mp4");
        CommandResult compose = execute(workDir,
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=0.6",
                "-f", "lavfi", "-i", "anullsrc=sample_rate=48000:channel_layout=stereo",
                "-filter_complex",
                "[0:v]tpad=stop_mode=clone:stop_duration=0.4,scale=160:90:force_original_aspect_ratio=decrease,"
                        + "pad=160:90:(ow-iw)/2:(oh-ih)/2,fps=25,setpts=PTS-STARTPTS[v];"
                        + "[1:a]apad=whole_dur=1,atrim=duration=1,asetpts=PTS-STARTPTS,"
                        + "aformat=sample_rates=48000:channel_layouts=stereo[a]",
                "-map", "[v]", "-map", "[a]", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-movflags", "+faststart", output.toString());
        if (compose.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L)
        {
            throw new IOException("FFmpeg最小合成失败: " + summarize(compose.output()));
        }
        assertStream(ffprobe, workDir, output, "v:0", "video");
        assertStream(ffprobe, workDir, output, "a:0", "audio");
    }

    private void assertStream(Path ffprobe, Path workDir, Path output, String selector, String expected)
            throws IOException
    {
        CommandResult result = execute(workDir, ffprobe.toString(), "-v", "error", "-select_streams", selector,
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", output.toString());
        if (result.exitCode() != 0 || !result.output().lines().anyMatch(expected::equals))
        {
            throw new IOException("FFmpeg最小合成缺少" + expected + "流: " + summarize(result.output()));
        }
    }

    private CommandResult execute(Path workDir, String... command) throws IOException
    {
        Path output = Files.createTempFile(workDir, "command-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean completed;
        try
        {
            completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            terminate(process);
            throw new IOException("FFmpeg校验被中断", e);
        }
        if (!completed)
        {
            terminate(process);
            throw new IOException("FFmpeg校验超时");
        }
        return new CommandResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    private void terminate(Process process)
    {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        if (process.isAlive())
        {
            process.destroy();
        }
        waitForExit(process, 2);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive())
        {
            process.destroyForcibly();
            waitForExit(process, 2);
        }
    }

    private void waitForExit(Process process, int seconds)
    {
        try
        {
            process.waitFor(seconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> findMissing(String output, List<String> required)
    {
        List<String> missing = new ArrayList<>();
        for (String name : required)
        {
            Pattern pattern = Pattern.compile("(?m)\\s" + Pattern.quote(name) + "(?:\\s|$)");
            if (!pattern.matcher(output).find())
            {
                missing.add(name);
            }
        }
        return missing;
    }

    private boolean versionAtLeast(String current, String minimum)
    {
        String[] currentParts = current.split("\\.");
        String[] minimumParts = minimum.split("\\.");
        int currentMajor = Integer.parseInt(currentParts[0]);
        int currentMinor = Integer.parseInt(currentParts[1]);
        int minimumMajor = Integer.parseInt(minimumParts[0]);
        int minimumMinor = Integer.parseInt(minimumParts[1]);
        return currentMajor > minimumMajor || currentMajor == minimumMajor && currentMinor >= minimumMinor;
    }

    private String summarize(String output)
    {
        String normalized = StrUtil.blankToDefault(output, "空").replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private void deleteRecursively(Path root)
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var paths = Files.walk(root))
        {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // 临时目录清理由操作系统兜底，不覆盖主校验结果。
                }
            });
        }
        catch (IOException ignored)
        {
            // 临时目录清理由操作系统兜底，不覆盖主校验结果。
        }
    }

    private record CommandResult(int exitCode, String output)
    {
    }

    /** 对外仅暴露简短提示，详细原因由调用方写入服务端日志。 */
    public static final class FontValidationException extends IOException
    {
        private final String userMessage;

        private FontValidationException(String userMessage, String detail, Throwable cause)
        {
            super(detail, cause);
            this.userMessage = userMessage;
        }

        public String getUserMessage()
        {
            return userMessage;
        }
    }
}
