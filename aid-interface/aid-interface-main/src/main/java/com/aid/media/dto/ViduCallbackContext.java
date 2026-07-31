package com.aid.media.dto;

import lombok.Data;

import java.util.Map;
import java.util.TreeMap;

/**
 * Vidu 回调请求头上下文。
 *
 * @author 视觉AID
 */
@Data
public class ViduCallbackContext {

    /** HTTP 请求头，按名称大小写不敏感存储。 */
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** 写入请求头。 */
    public void putHeader(String name, String value) {
        if (name != null) {
            headers.put(name, value);
        }
    }

    /** 读取请求头。 */
    public String getHeader(String name) {
        return name == null ? null : headers.get(name);
    }
}
