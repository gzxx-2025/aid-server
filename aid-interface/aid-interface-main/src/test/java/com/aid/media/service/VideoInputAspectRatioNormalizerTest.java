package com.aid.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.moderation.fetch.ImageBytesFetcher;
import com.aid.common.moderation.fetch.ImageBytesFetcher.FetchOutcome;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MediaInternalOptionKeys;
import com.aid.media.dto.MediaVideoGenerateRequest;

class VideoInputAspectRatioNormalizerTest {

    @Test
    void normalizesFollowInputImageAndCleansTemporaryObject() throws Exception {
        ImageBytesFetcher fetcher = mock(ImageBytesFetcher.class);
        OssTemplate ossTemplate = mock(OssTemplate.class);
        MediaUrlResolver mediaUrlResolver = mock(MediaUrlResolver.class);
        VideoInputAspectRatioNormalizer normalizer =
                new VideoInputAspectRatioNormalizer(fetcher, ossTemplate, mediaUrlResolver);
        String sourceUrl = "https://example.test/source.jpg";
        String storedPath = "/media/video-input-aspect/temp.jpg";
        String providerUrl = "https://cdn.example.test/media/video-input-aspect/temp.jpg";
        when(fetcher.resolve(sourceUrl)).thenReturn(FetchOutcome.ofBytes(squareImage()));
        when(ossTemplate.uploadBytes(any(byte[].class), eq("video-input.jpg"),
                eq("media/video-input-aspect"))).thenReturn(storedPath);
        when(mediaUrlResolver.toFullUrl(storedPath)).thenReturn(providerUrl);
        when(mediaUrlResolver.toProviderUrl(providerUrl)).thenReturn(providerUrl);
        when(ossTemplate.deleteByUrl(providerUrl)).thenReturn(true);

        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setImageUrl(sourceUrl);
        request.setAspectRatio("16:9");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("resolution", "720p");
        request.setOptions(options);

        assertTrue(normalizer.normalize(model(), request));
        assertEquals(providerUrl, request.getImageUrl());
        assertTrue(request.getOptions().containsKey(MediaInternalOptionKeys.NORMALIZED_VIDEO_INPUTS));

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ossTemplate).uploadBytes(bytesCaptor.capture(), eq("video-input.jpg"),
                eq("media/video-input-aspect"));
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertEquals(1280, normalized.getWidth());
        assertEquals(720, normalized.getHeight());

        assertTrue(normalizer.restoreAndCleanup(request));
        assertEquals(sourceUrl, request.getImageUrl());
        verify(ossTemplate).deleteByUrl(providerUrl);
    }

    private AiModelConfigVo model() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("follow-input-video");
        model.setCapabilityJson("{\"videoAspectRatioMode\":\"FOLLOW_INPUT\","
                + "\"inputAspectRatioFit\":\"CONTAIN\"}");
        return model;
    }

    private byte[] squareImage() throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
