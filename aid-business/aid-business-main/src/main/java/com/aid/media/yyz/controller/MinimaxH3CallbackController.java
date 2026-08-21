package com.aid.media.yyz.controller;

import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.annotation.Anonymous;
import com.aid.media.service.MinimaxH3CallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** MiniMax H3 challenge 验证与任务状态通知接收端点。 */
@RestController
@RequestMapping("/api/media/callback")
@RequiredArgsConstructor
public class MinimaxH3CallbackController {

    private final MinimaxH3CallbackService minimaxH3CallbackService;

    @Anonymous
    @CryptoIgnore
    @PostMapping("/minimax-h3")
    public ResponseEntity<Map<String, String>> onCallback(@RequestBody(required = false) String rawBody) {
        String challenge = minimaxH3CallbackService.handleCallback(rawBody);
        if (challenge != null) {
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }
        return ResponseEntity.ok(Collections.emptyMap());
    }
}
