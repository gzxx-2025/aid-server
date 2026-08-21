package com.aid.media.dto;

import java.util.Map;
import java.util.TreeMap;

/** 可灵 Webhook 请求头（大小写不敏感）。 */
public class KlingCallbackContext {
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public void putHeader(String name, String value) {
        if (name != null) {
            headers.put(name, value);
        }
    }

    public String getHeader(String name) {
        return name == null ? null : headers.get(name);
    }
}
