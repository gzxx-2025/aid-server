package com.aid.media.service;

import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;

/** 复用正式生成前置校验的媒体计费输入准备器；不创建任务、不调用上游。 */
public interface MediaBillingQuotePreparer
{
    PreparedMediaBillingInput prepareImageBilling(MediaImageGenerateRequest request);

    /** 提示词将由前一只读计划产出的图片报价；仅校验/归一已知规格与输入，不伪造未知 prompt。 */
    PreparedMediaBillingInput preparePlannedImageBilling(MediaImageGenerateRequest request);

    PreparedMediaBillingInput prepareVideoBilling(MediaVideoGenerateRequest request);

    /** 提示词将由前一只读计划产出的视频报价；仅校验/归一已知规格与输入，不伪造未知 prompt。 */
    PreparedMediaBillingInput preparePlannedVideoBilling(MediaVideoGenerateRequest request);

    PreparedMediaBillingInput prepareTextBilling(MediaTextGenerateRequest request);

    PreparedMediaBillingInput prepareAudioBilling(MediaAudioGenerateRequest request);
}
