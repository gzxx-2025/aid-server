package com.aid.media.provider;

import com.aid.media.dto.SpeechRecognitionResult;

/**
 * 同步语音识别客户端抽象。厂商内部可以提交并轮询，但调用方只接收一个同步终态结果。
 *
 * @author 视觉AID
 */
public interface SpeechRecognitionClient {

    /** 厂商编码。 */
    String providerCode();

    /** 当前厂商自动字幕功能是否开启。 */
    boolean isEnabled();

    /** 对一个媒体 URL 完成识别并返回标准时间戳结果。 */
    SpeechRecognitionResult recognize(String mediaUrl);

    /**
     * 对一个媒体 URL 完成识别，并在上游等待期间定期触发存活回调。
     * 默认实现兼容不需要轮询的同步厂商。
     */
    default SpeechRecognitionResult recognize(String mediaUrl, Runnable heartbeatCallback) {
        if (heartbeatCallback != null) {
            heartbeatCallback.run();
        }
        return recognize(mediaUrl);
    }
}
