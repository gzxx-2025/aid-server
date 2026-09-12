package com.aid.media.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

class ModelCapabilitySceneTest {

    private static final String IMAGE_CAPABILITY = """
            {"sizeOptions":["1K","2K","4K"],"defaultSize":"2K",
             "aspectRatioOptions":["1:1","16:9"],"defaultAspectRatio":"1:1",
             "sceneRules":{"textToImage":{"sizeOptions":["1K","2K","4K"]},
             "imageToImage":{"sizeOptions":["1K","2K"],"defaultSize":"2K"}}}
            """;

    @Test
    void textToImageAllowsFourK() {
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setSize("4K");

        assertDoesNotThrow(() -> ModelCapabilityValidator.validateImage(imageModel(), request));
    }

    @Test
    void imageToImageRejectsFourK() {
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setSize("4K");
        request.setReferenceImageUrl("https://example.test/reference.png");

        assertThrows(ServiceException.class,
                () -> ModelCapabilityValidator.validateImage(imageModel(), request));
    }

    @Test
    void staleSceneSizeFallsBackToSceneDefault() {
        assertEquals("2K", ModelCapabilityResolver.coerceImageSceneSize(
                imageModel(), "imageToImage", "1080p"));
        assertEquals(List.of("1K", "2K"), ModelCapabilityResolver.readImageSceneOptions(
                imageModel(), "imageToImage", ModelCapabilityResolver.KEY_SIZE_OPTIONS));
    }

    @Test
    void followInputVideoKeepsInternalTargetRatio() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("follow-input-video");
        model.setSupportsAspectRatio(Boolean.FALSE);
        model.setCapabilityJson("{\"videoAspectRatioMode\":\"FOLLOW_INPUT\","
                + "\"defaultAspectRatio\":\"16:9\"}");
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setAspectRatio("16:9");

        ModelCapabilityValidator.normalizeVideoAspectRatio(model, request);

        assertEquals("16:9", request.getAspectRatio());
    }

    @Test
    void fixedPixelImageAcceptsMatchingDisplayRatioAndOmitsProviderRatio() {
        AiModelConfigVo model = fixedPixelImageModel();

        assertDoesNotThrow(() -> ModelCapabilityResolver.validateImageOutputSelection(
                model, "1024X1024", "1:1"));
        assertDoesNotThrow(() -> ModelCapabilityResolver.validateImageOutputSelection(
                model, "1024x768", "4:3"));
        assertDoesNotThrow(() -> ModelCapabilityResolver.validateImageOutputSelection(
                fixedPixelImageModel("854x480"), "854x480", "16:9"));

        ModelCapabilityResolver.ImageSizeSpec spec = ModelCapabilityResolver.resolveImageSpec(
                model, "768x1024", "3:4");
        assertEquals("768x1024", spec.size());
        assertNull(spec.aspectRatio());
    }

    @Test
    void fixedPixelImageRejectsRatioThatConflictsWithSelectedSize() {
        AiModelConfigVo model = fixedPixelImageModel();

        assertThrows(ServiceException.class, () -> ModelCapabilityResolver.validateImageOutputSelection(
                model, "1024x768", "1:1"));
        assertThrows(ServiceException.class, () -> ModelCapabilityResolver.resolveImageSpec(
                model, "1024x768", "16:9"));
    }

    @Test
    void ratioCapableImageKeepsIndependentSizeAndRatio() {
        ModelCapabilityResolver.ImageSizeSpec spec = ModelCapabilityResolver.resolveImageSpec(
                imageModel(), "2K", "16:9");

        assertEquals("2K", spec.size());
        assertEquals("16:9", spec.aspectRatio());
    }

    private AiModelConfigVo imageModel() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("scene-image");
        model.setCapabilityJson(IMAGE_CAPABILITY);
        return model;
    }

    private AiModelConfigVo fixedPixelImageModel() {
        return fixedPixelImageModel("1024x768", "1024x1024", "768x1024");
    }

    private AiModelConfigVo fixedPixelImageModel(String... sizes) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("fixed-pixel-image");
        model.setSupportsAspectRatio(Boolean.FALSE);
        model.setCapabilityJson("{\"sizeOptions\":" + cn.hutool.json.JSONUtil.toJsonStr(sizes) + ","
                + "\"defaultSize\":\"" + sizes[0] + "\",\"aspectRatioOptions\":[],"
                + "\"supportsAspectRatio\":false,\"allowCustomWH\":false}");
        return model;
    }
}
