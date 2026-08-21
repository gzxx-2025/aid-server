package com.aid.media.service;

/** MiniMax H3 回调处理；返回非空 challenge 时由控制器原样应答。 */
public interface MinimaxH3CallbackService {
    String handleCallback(String rawBody);
}
