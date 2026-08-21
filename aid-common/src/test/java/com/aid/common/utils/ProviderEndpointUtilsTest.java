package com.aid.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderEndpointUtilsTest
{
    @Test
    void buildsProxyPrefixedSubmitAndQueryUrls()
    {
        assertEquals("https://proxy.example.com/gate/api/v3/tasks",
                ProviderEndpointUtils.buildSubmitUrl(
                        "https://proxy.example.com/", "/gate/api/v3/tasks"));
        assertEquals("https://proxy.example.com/gate/tasks/a%2Fb%20c",
                ProviderEndpointUtils.buildTaskQueryUrl(
                        "https://proxy.example.com", "/gate/tasks/%s", "a/b c"));
    }

    @Test
    void supportsTaskIdInQueryString()
    {
        assertEquals("https://api.example.com/tasks?task_ids=a%2Fb",
                ProviderEndpointUtils.buildTaskQueryUrl(
                        "https://api.example.com", "/tasks?task_ids=%s", "a/b"));
    }

    @Test
    void replacesOnlyTheControlledModelPlaceholder()
    {
        assertEquals("https://proxy.example.com/gemini/v9/models/gemini%2Ftest:generateContent",
                ProviderEndpointUtils.buildModelSubmitUrl(
                        "https://proxy.example.com",
                        "/gemini/v9/models/{model}:generateContent", "gemini/test"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.buildModelSubmitUrl(
                        "https://proxy.example.com", "/models/plain", "gemini-test"));
    }

    @Test
    void rejectsHostTraversalAndCrLfInjection()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("https://evil.example/tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("//evil.example/tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("/api/../tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("/api/%2e%2e/tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("/tasks\r\nX-Test: true"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("/tasks%0d%0aX-Test:true"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeSubmitPath("/api//tasks"));
    }

    @Test
    void requiresExactlyOneTaskPlaceholder()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeTaskQueryTemplate("/tasks"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointUtils.normalizeTaskQueryTemplate("/tasks/%s/%s"));
        assertEquals("/tasks/%s", ProviderEndpointUtils.normalizeTaskQueryTemplate(" /tasks/%s "));
    }
}
