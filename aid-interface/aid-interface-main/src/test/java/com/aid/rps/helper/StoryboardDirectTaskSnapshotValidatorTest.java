package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aid.common.exception.ServiceException;

class StoryboardDirectTaskSnapshotValidatorTest
{
    @Test
    void rejectsScriptChangedAfterTaskSubmission()
    {
        ServiceException error = assertThrows(ServiceException.class,
                () -> StoryboardDirectTaskSnapshotValidator.validateScript(
                        "新剧本", "submitted-hash", "current-hash"));
        assertEquals("剧本已变化，请重试", error.getMessage());
        assertDoesNotThrow(() -> StoryboardDirectTaskSnapshotValidator.validateScript(
                "原剧本", "same-hash", "same-hash"));
    }

    @Test
    void rejectsSceneSnapshotWithDeletedOrUnexpectedAssets()
    {
        assertThrows(ServiceException.class,
                () -> StoryboardDirectTaskSnapshotValidator.validateSceneIds(
                        List.of(1L, 2L), List.of(1L)));
        assertThrows(ServiceException.class,
                () -> StoryboardDirectTaskSnapshotValidator.validateSceneIds(
                        List.of(1L, 2L), List.of(1L, 3L)));
        assertDoesNotThrow(() -> StoryboardDirectTaskSnapshotValidator.validateSceneIds(
                List.of(2L, 1L), List.of(1L, 2L)));
    }
}
