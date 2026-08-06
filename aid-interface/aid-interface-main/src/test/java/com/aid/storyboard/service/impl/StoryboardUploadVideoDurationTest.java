package com.aid.storyboard.service.impl;

import com.aid.common.exception.ServiceException;
import com.aid.storyboard.dto.UploadStoryboardImageRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryboardUploadVideoDurationTest {

    @Test
    void shouldRejectUploadedVideoWithoutDurationBeforeDatabaseAccess() {
        StoryboardWorkbenchServiceImpl service = new StoryboardWorkbenchServiceImpl();
        UploadStoryboardImageRequest request = new UploadStoryboardImageRequest();
        request.setStoryboardId(4635L);
        request.setMediaType("video");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.uploadStoryboardImage(request, 61L));

        assertEquals("请上传秒数", exception.getMessage());
    }
}
