package com.aid.media.dto;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 服务端解析后的参考视频输入。 */
public final class ReferenceVideoInput
{
    public static final String SOURCE_GEN_RECORD = "GEN_RECORD";

    private final Long recordId;
    private final String videoUrl;
    private final Long durationMs;
    private final Long fileSizeBytes;
    private final Integer width;
    private final Integer height;
    private final BigDecimal fps;
    private final String format;

    public ReferenceVideoInput(Long recordId, String videoUrl, Long durationMs, Long fileSizeBytes,
            Integer width, Integer height, BigDecimal fps, String format)
    {
        this.recordId = recordId;
        this.videoUrl = videoUrl;
        this.durationMs = durationMs;
        this.fileSizeBytes = fileSizeBytes;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.format = format;
    }

    public Long getRecordId()
    {
        return recordId;
    }

    public String getVideoUrl()
    {
        return videoUrl;
    }

    public Long getDurationMs()
    {
        return durationMs;
    }

    public Long getFileSizeBytes()
    {
        return fileSizeBytes;
    }

    public Integer getWidth()
    {
        return width;
    }

    public Integer getHeight()
    {
        return height;
    }

    public BigDecimal getFps()
    {
        return fps;
    }

    public String getFormat()
    {
        return format;
    }

    public String getSourceType()
    {
        return SOURCE_GEN_RECORD;
    }

    /** 该标记由服务端类型固定提供，不存在可写字段。 */
    @JsonIgnore
    public boolean isTrusted()
    {
        return true;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof ReferenceVideoInput that))
        {
            return false;
        }
        return Objects.equals(recordId, that.recordId)
                && Objects.equals(videoUrl, that.videoUrl)
                && Objects.equals(durationMs, that.durationMs)
                && Objects.equals(fileSizeBytes, that.fileSizeBytes)
                && Objects.equals(width, that.width)
                && Objects.equals(height, that.height)
                && Objects.equals(fps, that.fps)
                && Objects.equals(format, that.format);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(recordId, videoUrl, durationMs, fileSizeBytes, width, height, fps, format);
    }
}
