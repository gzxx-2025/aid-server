package com.aid.tokendance.provider.audio;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.common.utils.spring.SpringUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.definition.ModelConfiguredRequestBody;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.util.AudioDurationProber;
import com.aid.media.util.WaveAudioAssembler;
import com.aid.tokendance.provider.common.DefaultTokenDanceTransport;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** TokenDance Ark 与 MiniMax WebSocket 配音协议的无状态支持类。 */
@Slf4j
public final class TokenDanceWebSocketAudioSupport
{
    public static final String ARK_PROTOCOL = "tokendance:ark:tts_ws";
    public static final String MINIMAX_PROTOCOL = "tokendance:minimax:t2a_v2_ws";

    private static final String ARK_PATH = "/gateway/ark/v3/tts/bidirection";
    private static final String MINIMAX_PATH = "/gateway/minimax/v1/t2a_v2_ws";
    private static final int DEFAULT_TIMEOUT_MS = 300_000;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int MAX_AUDIO_BYTES = 96 * 1024 * 1024;
    private static final int MAX_CONTROL_MESSAGE_CHARS = MAX_AUDIO_BYTES * 2 + 1024 * 1024;
    private static final int MAX_BINARY_MESSAGE_BYTES = MAX_AUDIO_BYTES + 1024 * 1024;
    private static final int MAX_CONTROL_MESSAGES = 10_000;
    private static final int MAX_JSON_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int INBOUND_QUEUE_CAPACITY = 8;
    private static final int PCM_BYTES_PER_SAMPLE = 2;

    private static final int ARK_FULL_CLIENT_REQUEST = 0x1;
    private static final int ARK_FULL_SERVER_RESPONSE = 0x9;
    private static final int ARK_AUDIO_ONLY_RESPONSE = 0xb;
    private static final int ARK_ERROR_RESPONSE = 0xf;
    private static final int ARK_WITH_EVENT = 0x4;
    private static final int ARK_JSON_SERIALIZATION = 0x1;
    private static final int ARK_START_CONNECTION = 1;
    private static final int ARK_CONNECTION_STARTED = 50;
    private static final int ARK_CONNECTION_FAILED = 51;
    private static final int ARK_START_SESSION = 100;
    private static final int ARK_FINISH_SESSION = 102;
    private static final int ARK_SESSION_STARTED = 150;
    private static final int ARK_SESSION_FINISHED = 152;
    private static final int ARK_SESSION_FAILED = 153;
    private static final int ARK_TASK_REQUEST = 200;

    private static final Set<String> PROTOCOLS = Set.of(ARK_PROTOCOL, MINIMAX_PROTOCOL);
    private static final Set<String> ARK_FORMATS = Set.of("mp3", "wav", "pcm", "ogg_opus");
    private static final Set<String> MINIMAX_FORMATS = Set.of("mp3", "pcm", "flac");
    private static final Set<Integer> ARK_SAMPLE_RATES = Set.of(
            8000, 16000, 22050, 24000, 32000, 44100, 48000);
    private static final Set<Integer> MINIMAX_SAMPLE_RATES = Set.of(
            8000, 16000, 22050, 24000, 32000, 44100);
    private static final Set<Integer> MINIMAX_BITRATES = Set.of(32000, 64000, 128000, 256000);
    private static final Set<String> MINIMAX_EMOTIONS = Set.of(
            "happy", "sad", "angry", "fearful", "disgusted", "surprised",
            "calm", "fluent", "whisper");
    private static final Set<String> MINIMAX_SUBTITLE_TYPES = Set.of(
            "sentence", "word", "word_streaming");
    private static final Set<String> MINIMAX_SOUND_EFFECTS = Set.of(
            "spacious_echo", "auditorium_echo", "lofi_telephone", "robotic");
    private static final Set<String> MINIMAX_OPTION_KEYS = Set.of(
            "channel", "bitrate", "languageBoost", "subtitleType", "pronunciationTone",
            "textNormalization", "latexRead", "voiceModifyPitch", "voiceModifyIntensity",
            "voiceModifyTimbre", "soundEffect");
    private static final Set<String> ARK_OPTION_KEYS = Set.of("silenceDurationMs");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenDanceWebSocketAudioSupport()
    {
    }

    /** 校验 TokenDance WebSocket 配音模型及已核验字段。 */
    public static void validate(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        if (config == null || request == null || !PROTOCOLS.contains(trim(config.getProtocol())))
        {
            fail("protocol or request missing", "配音协议未核验");
        }
        TokenDancePayloadSupport.requireModel(config, Set.of(config.getProtocol().trim()));
        if (request.isPreviewMode())
        {
            fail("preview bypasses task", "请创建试听任务");
        }
        if (StrUtil.isBlank(request.getTtsText()) || StrUtil.isBlank(request.getVoiceCode()))
        {
            fail("text or voice missing", "配音参数不完整");
        }
        range(request.getSpeechRate(), -50, 100, "语速无效");
        range(request.getLoudnessRate(), -50, 100, "音量无效");
        range(request.getPitch(), -12, 12, "音调无效");
        if (MINIMAX_PROTOCOL.equals(trim(config.getProtocol())))
        {
            validateMinimax(config, request);
        }
        else
        {
            validateArk(config, request);
        }
        try {
            if (MINIMAX_PROTOCOL.equals(trim(config.getProtocol()))) minimaxStart(config, request);
            else ModelConfiguredRequestBody.apply(config, arkStart(request), request);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            log.info("配音协议参数无法生成", exception);
            throw new ServiceException("配音参数无效");
        }
    }

    /** 建立单次 WebSocket 会话，完整确认终态后上传音频并返回统一任务结果。 */
    public static ProviderSubmitResult submit(AiModelConfigVo config,
            MediaAudioGenerateRequest request)
    {
        return submit(config, request, SpringUtils.getBean(TokenDanceAudioRecoveryStore.class));
    }

    /** 使用共享恢复存储，保证转存失败后的同请求不会再次建立收费 WebSocket 会话。 */
    public static ProviderSubmitResult submit(AiModelConfigVo config,
            MediaAudioGenerateRequest request, TokenDanceAudioRecoveryStore recoveryStore)
    {
        validate(config, request);
        if (recoveryStore == null)
        {
            throw new ServiceException("配音恢复服务未就绪");
        }
        Deadline deadline = new Deadline(SubmitTimeoutResolver.resolveMs(config, DEFAULT_TIMEOUT_MS));
        return recoveryStore.recoverOrGenerate(config, request, null, () -> {
            try
            {
                TokenDanceAudioRecoveryStore.GeneratedAudio audio =
                        MINIMAX_PROTOCOL.equals(trim(config.getProtocol()))
                                ? submitMinimax(config, request, deadline)
                                : submitArk(config, request, deadline);
                return TokenDanceAudioRecoveryStore.GenerationResult.success(audio);
            }
            catch (ExplicitUpstreamFailure failure)
            {
                return TokenDanceAudioRecoveryStore.GenerationResult.failure(
                        ProviderSubmitResult.builder().rawResponse(failure.audit()).build());
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw exception;
            }
            catch (ServiceException exception)
            {
                throw exception;
            }
            catch (Exception exception)
            {
                log.warn("TokenDance WebSocket 配音结果未知, protocol={}, model={}, error={}",
                        trim(config.getProtocol()), config.getModelCode(),
                        exception.getClass().getSimpleName());
                throw exception;
            }
        });
    }

    private static TokenDanceAudioRecoveryStore.GeneratedAudio submitMinimax(AiModelConfigVo config,
            MediaAudioGenerateRequest request, Deadline deadline) throws Exception
    {
        SocketSession session = connect(config, MINIMAX_PATH, Map.of(), deadline);
        byte[] audio = null;
        boolean handedOff = false;
        try
        {
            JsonNode connected = nextMinimax(session, deadline);
            requireMinimaxEvent(connected, "connected_success");

            sendText(session.socket(), json(minimaxStart(config, request)), deadline);
            JsonNode started = waitMinimaxEvent(session, "task_started", deadline);
            requireMinimaxSuccess(started);

            sendText(session.socket(), json(Map.of(
                    "event", "task_continue", "text", request.getTtsText())), deadline);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            JsonNode resultFrame = null;
            int messages = 0;
            while (resultFrame == null)
            {
                if (++messages > MAX_CONTROL_MESSAGES)
                {
                    fail("minimax frame count exceeded", "音频响应过多");
                }
                JsonNode frame = nextMinimax(session, deadline);
                requireMinimaxSuccess(frame);
                String event = frame.path("event").asText();
                if ("task_failed".equals(event))
                {
                    throw explicit(frame, session.recoveryAction());
                }
                appendMinimaxAudio(output, frame);
                redactMinimaxAudio(frame);
                if (frame.path("is_final").asBoolean(false))
                {
                    resultFrame = frame;
                }
            }

            sendText(session.socket(), json(Map.of("event", "task_finish")), deadline);
            JsonNode finished = waitMinimaxEvent(session, "task_finished", deadline);
            requireMinimaxSuccess(finished);
            if (output.size() == 0)
            {
                fail("minimax empty audio", "音频响应未完成");
            }
            audio = output.toByteArray();
            String format = format(request, "mp3");
            validateAudio(audio, format);
            long duration = positiveLong(resultFrame.path("extra_info").path("audio_length"));
            long characters = positiveOrZeroLong(
                    resultFrame.path("extra_info").path("usage_characters"));
            ObjectNode audit = MAPPER.createObjectNode();
            audit.put("protocol", MINIMAX_PROTOCOL);
            audit.set("result", resultFrame);
            audit.set("terminal", finished);
            addRecovery(audit, session.recoveryAction());
            Map<String, Object> usage = characters >= 0
                    ? Map.of("usage_characters", characters) : Map.of();
            handedOff = true;
            return new TokenDanceAudioRecoveryStore.GeneratedAudio(audio, format,
                    duration > 0 ? duration : durationMs(audio, format, request, 32000),
                    usage, json(audit), null);
        }
        finally
        {
            close(session.socket());
            if (!handedOff && audio != null)
            {
                Arrays.fill(audio, (byte) 0);
            }
        }
    }

    private static TokenDanceAudioRecoveryStore.GeneratedAudio submitArk(AiModelConfigVo config,
            MediaAudioGenerateRequest request, Deadline deadline) throws Exception
    {
        String resourceId = TokenDancePayloadSupport.upstreamModel(config, request.getModelName());
        SocketSession session = connect(config, ARK_PATH, Map.of(
                "X-Api-Resource-Id", resourceId,
                "X-Control-Require-Usage-Tokens-Return", "*"), deadline);
        String sessionId = UUID.randomUUID().toString();
        byte[] audio = null;
        boolean handedOff = false;
        try
        {
            sendBinary(session.socket(), encodeArkClient(
                    ARK_START_CONNECTION, null, Map.of()), deadline);
            ArkCollector collector = new ArkCollector(format(request, "mp3"));
            waitArkEvent(session, ARK_CONNECTION_STARTED, collector, deadline);

            sendBinary(session.socket(), encodeArkClient(
                    ARK_START_SESSION, sessionId, ModelConfiguredRequestBody.apply(config, arkStart(request), request)), deadline);
            waitArkEvent(session, ARK_SESSION_STARTED, collector, deadline);

            sendBinary(session.socket(), encodeArkClient(ARK_TASK_REQUEST, sessionId,
                    Map.of("event", ARK_TASK_REQUEST,
                            "req_params", Map.of("text", request.getTtsText()))), deadline);
            sendBinary(session.socket(), encodeArkClient(
                    ARK_FINISH_SESSION, sessionId, Map.of()), deadline);
            waitArkEvent(session, ARK_SESSION_FINISHED, collector, deadline);
            if (collector.size() == 0)
            {
                fail("ark empty audio", "音频响应未完成");
            }
            audio = collector.finish();
            String format = format(request, "mp3");
            validateAudio(audio, format);
            ObjectNode audit = MAPPER.createObjectNode();
            audit.put("protocol", ARK_PROTOCOL);
            audit.put("event", ARK_SESSION_FINISHED);
            if (collector.terminal() != null)
            {
                audit.set("terminal", collector.terminal());
            }
            addRecovery(audit, session.recoveryAction());
            Map<String, Object> usage = collector.usageCharacters() == null
                    ? Map.of() : Map.of("usage_characters", collector.usageCharacters());
            handedOff = true;
            return new TokenDanceAudioRecoveryStore.GeneratedAudio(audio, format,
                    collector.durationMs() == null
                            ? durationMs(audio, format, request, 24000) : collector.durationMs(),
                    usage, json(audit), null);
        }
        finally
        {
            close(session.socket());
            if (!handedOff && audio != null)
            {
                Arrays.fill(audio, (byte) 0);
            }
        }
    }

    private static SocketSession connect(AiModelConfigVo config, String path,
            Map<String, String> protocolHeaders, Deadline deadline) throws Exception
    {
        URI endpoint = websocketUri(config.getBaseUrl(), path);
        QueueListener listener = new QueueListener();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(CONNECT_TIMEOUT_MS, deadline.remainingMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(CONNECT_TIMEOUT_MS, deadline.remainingMs())))
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .header("X-App-URL", DefaultTokenDanceTransport.APP_URL);
        for (Map.Entry<String, String> header : protocolHeaders.entrySet())
        {
            builder.header(header.getKey(), header.getValue());
        }
        try
        {
            WebSocket socket = builder.buildAsync(endpoint, listener)
                    .get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
            return new SocketSession(socket, listener, null);
        }
        catch (ExecutionException exception)
        {
            Throwable cause = exception.getCause();
            if (cause instanceof WebSocketHandshakeException handshake)
            {
                HttpResponse<?> response = handshake.getResponse();
                String recovery = response.headers().firstValue(
                        DefaultTokenDanceTransport.RECOVERY_ACTION_HEADER).orElse(null);
                ObjectNode audit = MAPPER.createObjectNode();
                audit.put("status", response.statusCode());
                addRecovery(audit, recovery);
                throw new ExplicitUpstreamFailure(json(audit));
            }
            throw exception;
        }
    }

    private static JsonNode nextMinimax(SocketSession session, Deadline deadline)
            throws Exception
    {
        Inbound inbound = session.listener().next(deadline);
        if (inbound.error() != null)
        {
            throw new ExecutionException(inbound.error());
        }
        if (inbound.closed())
        {
            fail("minimax closed before terminal", "配音结果待确认");
        }
        if (inbound.text() == null)
        {
            fail("minimax binary response", "音频响应无效");
        }
        JsonNode frame = MAPPER.readTree(inbound.text());
        if (frame == null || !frame.isObject())
        {
            fail("minimax json invalid", "音频响应无效");
        }
        return frame;
    }

    private static JsonNode waitMinimaxEvent(SocketSession session, String expected,
            Deadline deadline) throws Exception
    {
        for (int count = 0; count < MAX_CONTROL_MESSAGES; count++)
        {
            JsonNode frame = nextMinimax(session, deadline);
            requireMinimaxSuccess(frame);
            String event = frame.path("event").asText();
            if ("task_failed".equals(event))
            {
                throw explicit(frame, session.recoveryAction());
            }
            if (expected.equals(event))
            {
                return frame;
            }
        }
        fail("minimax expected event missing", "音频响应过多");
        return null;
    }

    private static void requireMinimaxEvent(JsonNode frame, String expected)
    {
        requireMinimaxSuccess(frame);
        if (!expected.equals(frame.path("event").asText()))
        {
            if ("task_failed".equals(frame.path("event").asText()))
            {
                throw explicit(frame, null);
            }
            fail("minimax unexpected event", "音频响应无效");
        }
    }

    private static void requireMinimaxSuccess(JsonNode frame)
    {
        JsonNode code = frame.path("base_resp").path("status_code");
        if (code.isNumber() && code.asLong() != 0)
        {
            throw explicit(frame, null);
        }
    }

    private static void appendMinimaxAudio(ByteArrayOutputStream output, JsonNode frame)
    {
        JsonNode node = frame.path("data").path("audio");
        if (!node.isTextual() || node.textValue().isEmpty())
        {
            return;
        }
        String encoded = node.textValue();
        long remaining = MAX_AUDIO_BYTES - (long) output.size();
        if ((encoded.length() & 1) != 0 || encoded.length() > remaining * 2L)
        {
            fail("minimax hex too large", "音频响应过大");
        }
        byte[] chunk;
        try
        {
            chunk = HexFormat.of().parseHex(encoded);
        }
        catch (IllegalArgumentException exception)
        {
            fail("minimax hex invalid", "音频响应无效");
            return;
        }
        output.writeBytes(chunk);
        Arrays.fill(chunk, (byte) 0);
    }

    private static void redactMinimaxAudio(JsonNode frame)
    {
        if (frame.path("data") instanceof ObjectNode data && data.has("audio"))
        {
            data.put("audio", "[omitted]");
        }
    }

    private static void waitArkEvent(SocketSession session, int expected,
            ArkCollector collector, Deadline deadline) throws Exception
    {
        for (int count = 0; count < MAX_CONTROL_MESSAGES; count++)
        {
            Inbound inbound = session.listener().next(deadline);
            if (inbound.error() != null)
            {
                throw new ExecutionException(inbound.error());
            }
            if (inbound.closed())
            {
                fail("ark closed before terminal", "配音结果待确认");
            }
            if (inbound.binary() == null)
            {
                fail("ark text response", "音频响应无效");
            }
            ArkFrame frame = decodeArkServer(inbound.binary());
            if (frame.messageType() == ARK_ERROR_RESPONSE)
            {
                throw new ExplicitUpstreamFailure(
                        arkErrorAudit(frame, session.recoveryAction()));
            }
            if (frame.messageType() == ARK_AUDIO_ONLY_RESPONSE)
            {
                collector.appendAudio(frame.payload());
            }
            else if (frame.messageType() != ARK_FULL_SERVER_RESPONSE)
            {
                fail("ark message type invalid", "音频响应无效");
            }
            collector.capture(frame);
            if (frame.event() == ARK_CONNECTION_FAILED || frame.event() == ARK_SESSION_FAILED)
            {
                throw new ExplicitUpstreamFailure(
                        arkErrorAudit(frame, session.recoveryAction()));
            }
            if (frame.event() == expected)
            {
                return;
            }
        }
        fail("ark expected event missing", "音频响应过多");
    }

    private static byte[] encodeArkClient(int event, String sessionId, Object payload)
            throws Exception
    {
        byte[] session = sessionId == null ? null : sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] json = MAPPER.writeValueAsBytes(payload == null ? Map.of() : payload);
        if (json.length > MAX_JSON_REQUEST_BYTES)
        {
            fail("ark request too large", "配音文本过长");
        }
        int size = 4 + Integer.BYTES + (session == null ? 0 : Integer.BYTES + session.length)
                + Integer.BYTES + json.length;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) 0x11);
        buffer.put((byte) ((ARK_FULL_CLIENT_REQUEST << 4) | ARK_WITH_EVENT));
        buffer.put((byte) (ARK_JSON_SERIALIZATION << 4));
        buffer.put((byte) 0);
        buffer.putInt(event);
        if (session != null)
        {
            putBlob(buffer, session);
        }
        putBlob(buffer, json);
        Arrays.fill(json, (byte) 0);
        return buffer.array();
    }

    private static ArkFrame decodeArkServer(byte[] bytes)
    {
        if (bytes == null || bytes.length < 4)
        {
            fail("ark frame too short", "音频响应无效");
        }
        int headerBytes = (bytes[0] & 0x0f) * 4;
        int version = (bytes[0] >>> 4) & 0x0f;
        int type = (bytes[1] >>> 4) & 0x0f;
        int flags = bytes[1] & 0x0f;
        int compression = bytes[2] & 0x0f;
        if (version != 1 || headerBytes < 4 || headerBytes > bytes.length || compression != 0)
        {
            fail("ark frame header invalid", "音频响应无效");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(headerBytes);
        Integer errorCode = null;
        Integer sequence = null;
        Integer event = null;
        String scopeId = null;
        if (type == ARK_ERROR_RESPONSE)
        {
            errorCode = takeInt(buffer);
        }
        else if (flags == 1 || flags == 3)
        {
            sequence = takeInt(buffer);
        }
        if (flags == ARK_WITH_EVENT)
        {
            event = takeInt(buffer);
            if (event == ARK_CONNECTION_STARTED || event == ARK_CONNECTION_FAILED || event == 52)
            {
                scopeId = utf8(takeBlob(buffer));
            }
            else
            {
                scopeId = utf8(takeBlob(buffer));
            }
        }
        byte[] payload = buffer.hasRemaining() ? takeBlob(buffer) : new byte[0];
        if (buffer.hasRemaining())
        {
            fail("ark frame trailing bytes", "音频响应无效");
        }
        return new ArkFrame(type, flags, sequence, event, errorCode, scopeId, payload,
                (bytes[2] >>> 4) & 0x0f);
    }

    private static Map<String, Object> minimaxStart(AiModelConfigVo config,
            MediaAudioGenerateRequest request)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "task_start");
        body.put("model", TokenDancePayloadSupport.upstreamModel(config, request.getModelName()));
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("voice_id", request.getVoiceCode().trim());
        voice.put("speed", speed(request.getSpeechRate()));
        voice.put("vol", volume(request.getLoudnessRate()));
        voice.put("pitch", request.getPitch() == null ? 0 : request.getPitch());
        if (StrUtil.isNotBlank(request.getEmotion()))
        {
            voice.put("emotion", request.getEmotion().trim());
        }
        Object englishNormalization = option(request, "textNormalization");
        if (englishNormalization != null)
        {
            voice.put("english_normalization", englishNormalization);
        }
        Object latexRead = option(request, "latexRead");
        if (latexRead != null)
        {
            voice.put("latex_read", latexRead);
        }
        body.put("voice_setting", voice);
        body.put("audio_setting", Map.of(
                "sample_rate", request.getSampleRate() == null ? 32000 : request.getSampleRate(),
                "bitrate", optionInteger(request, "bitrate", 128000),
                "format", format(request, "mp3"),
                "channel", optionInteger(request, "channel", 1)));
        String languageBoost = optionText(request, "languageBoost");
        if (languageBoost != null)
        {
            body.put("language_boost", languageBoost);
        }
        List<String> pronunciation = optionTextList(request, "pronunciationTone");
        if (pronunciation != null)
        {
            body.put("pronunciation_dict", Map.of("tone", pronunciation));
        }
        Map<String, Object> modify = minimaxVoiceModify(request);
        if (!modify.isEmpty())
        {
            body.put("voice_modify", modify);
        }
        if (Boolean.TRUE.equals(request.getEnableTimestamp()))
        {
            body.put("subtitle_enable", true);
            String subtitleType = optionText(request, "subtitleType");
            if (subtitleType != null)
            {
                body.put("subtitle_type", subtitleType);
            }
        }
        return ModelConfiguredRequestBody.apply(config, body, request);
    }

    private static Map<String, Object> arkStart(MediaAudioGenerateRequest request)
            throws Exception
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("speaker", request.getVoiceCode().trim());
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", format(request, "mp3"));
        audio.put("sample_rate", request.getSampleRate() == null ? 24000 : request.getSampleRate());
        if (request.getSpeechRate() != null)
        {
            audio.put("speech_rate", request.getSpeechRate());
        }
        if (request.getLoudnessRate() != null)
        {
            audio.put("loudness_rate", request.getLoudnessRate());
        }
        params.put("audio_params", audio);
        Map<String, Object> additions = new LinkedHashMap<>();
        if (request.getPitch() != null)
        {
            additions.put("post_process", Map.of("pitch", request.getPitch()));
        }
        Integer silence = optionIntegerNullable(request, "silenceDurationMs");
        if (silence != null)
        {
            additions.put("silence_duration", silence);
        }
        if (!additions.isEmpty())
        {
            params.put("additions", MAPPER.writeValueAsString(additions));
        }
        return Map.of("req_params", params);
    }

    private static void validateMinimax(AiModelConfigVo config,
            MediaAudioGenerateRequest request)
    {
        if (request.getTtsText().codePointCount(0, request.getTtsText().length()) > 10_000)
        {
            fail("minimax text too long", "配音文本过长");
        }
        String format = format(request, "mp3");
        if (!MINIMAX_FORMATS.contains(format))
        {
            fail("minimax format unsupported", "音频格式不支持");
        }
        if (request.getSampleRate() != null && !MINIMAX_SAMPLE_RATES.contains(request.getSampleRate()))
        {
            fail("minimax sample rate unsupported", "采样率不支持");
        }
        if (request.getEmotionScale() != null)
        {
            fail("minimax emotion scale unsupported", "情感强度不支持");
        }
        if (StrUtil.isNotBlank(request.getEmotion()))
        {
            String emotion = request.getEmotion().trim();
            String model = TokenDancePayloadSupport.upstreamModel(config, request.getModelName());
            if (!MINIMAX_EMOTIONS.contains(emotion)
                    || model.contains("2.8") && "whisper".equals(emotion))
            {
                fail("minimax emotion unsupported", "音色情感不支持");
            }
        }
        rejectUnknownOptions(config, request, MINIMAX_OPTION_KEYS);
        int channel = optionInteger(request, "channel", 1);
        int bitrate = optionInteger(request, "bitrate", 128000);
        if (!Set.of(1, 2).contains(channel) || !MINIMAX_BITRATES.contains(bitrate))
        {
            fail("minimax audio option invalid", "音频参数无效");
        }
        requireBooleanOption(request, "textNormalization");
        requireBooleanOption(request, "latexRead");
        String subtitle = optionText(request, "subtitleType");
        if (subtitle != null && !MINIMAX_SUBTITLE_TYPES.contains(subtitle))
        {
            fail("minimax subtitle invalid", "字幕类型不支持");
        }
        if (subtitle != null && !Boolean.TRUE.equals(request.getEnableTimestamp()))
        {
            fail("subtitle type without subtitle", "字幕参数未启用");
        }
        optionTextList(request, "pronunciationTone");
        Map<String, Object> modify = minimaxVoiceModify(request);
        if (!modify.isEmpty() && !"mp3".equals(format))
        {
            fail("stream voice modify format", "音效仅支持MP3");
        }
    }

    private static void validateArk(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        if (!ARK_FORMATS.contains(format(request, "mp3")))
        {
            fail("ark format unsupported", "音频格式不支持");
        }
        if (request.getSampleRate() != null && !ARK_SAMPLE_RATES.contains(request.getSampleRate()))
        {
            fail("ark sample rate unsupported", "采样率不支持");
        }
        if (StrUtil.isNotBlank(request.getEmotion()) || request.getEmotionScale() != null)
        {
            fail("ark emotion unverified", "音色情感未核验");
        }
        if (Boolean.TRUE.equals(request.getEnableTimestamp()))
        {
            fail("ark timestamps unimplemented", "字幕回传尚未接通");
        }
        rejectUnknownOptions(config, request, ARK_OPTION_KEYS);
        Integer silence = optionIntegerNullable(request, "silenceDurationMs");
        if (silence != null && (silence < 0 || silence > 30_000))
        {
            fail("ark silence invalid", "静音时长无效");
        }
    }

    private static void rejectUnknownOptions(AiModelConfigVo config, MediaAudioGenerateRequest request, Set<String> allowed)
    {
        TokenDancePayloadSupport.rejectUnknownRequestOptions(config, options(request), allowed);
    }

    private static Map<String, Object> minimaxVoiceModify(MediaAudioGenerateRequest request)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : Map.of(
                "voiceModifyPitch", "pitch",
                "voiceModifyIntensity", "intensity",
                "voiceModifyTimbre", "timbre").entrySet())
        {
            Integer value = optionIntegerNullable(request, entry.getKey());
            if (value != null)
            {
                if (value < -100 || value > 100)
                {
                    fail("minimax voice modify range", "音效参数无效");
                }
                result.put(entry.getValue(), value);
            }
        }
        String effect = optionText(request, "soundEffect");
        if (effect != null)
        {
            if (!MINIMAX_SOUND_EFFECTS.contains(effect))
            {
                fail("minimax sound effect invalid", "音效类型不支持");
            }
            result.put("sound_effects", effect);
        }
        return result;
    }

    private static URI websocketUri(String baseUrl, String path)
    {
        try
        {
            URI http = URI.create(ProviderEndpointUtils.buildSubmitUrl(baseUrl, path));
            String scheme = switch (http.getScheme().toLowerCase(Locale.ROOT))
            {
                case "https" -> "wss";
                case "http" -> "ws";
                default -> throw new IllegalArgumentException("基础网关无效");
            };
            return new URI(scheme, http.getUserInfo(), http.getHost(), http.getPort(),
                    http.getPath(), http.getQuery(), null);
        }
        catch (URISyntaxException | IllegalArgumentException exception)
        {
            fail("websocket endpoint invalid", "模型配置错误");
            return null;
        }
    }

    private static void sendText(WebSocket socket, String text, Deadline deadline)
            throws Exception
    {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_REQUEST_BYTES)
        {
            fail("minimax request too large", "配音文本过长");
        }
        socket.sendText(text, true).get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
    }

    private static void sendBinary(WebSocket socket, byte[] body, Deadline deadline)
            throws Exception
    {
        try
        {
            socket.sendBinary(ByteBuffer.wrap(body), true)
                    .get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        }
        finally
        {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static void close(WebSocket socket)
    {
        if (socket == null)
        {
            return;
        }
        try
        {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                    .orTimeout(1, TimeUnit.SECONDS).exceptionally(error -> null).join();
        }
        catch (Exception ignored)
        {
            socket.abort();
        }
    }

    private static void validateAudio(byte[] audio, String format)
    {
        boolean valid = switch (format)
        {
            case "mp3" -> hasPrefix(audio, 0, "ID3")
                    || audio.length >= 2 && (audio[0] & 0xff) == 0xff && (audio[1] & 0xe0) == 0xe0;
            case "wav" -> hasPrefix(audio, 0, "RIFF") && hasPrefix(audio, 8, "WAVE");
            case "flac" -> hasPrefix(audio, 0, "fLaC");
            case "ogg_opus" -> hasPrefix(audio, 0, "OggS");
            case "pcm" -> (audio.length & 1) == 0;
            default -> false;
        };
        if (!valid)
        {
            fail("audio signature mismatch", "音频响应无效");
        }
    }

    private static Long durationMs(byte[] audio, String format,
            MediaAudioGenerateRequest request, int defaultSampleRate)
    {
        if ("pcm".equals(format))
        {
            int sampleRate = request.getSampleRate() == null
                    ? defaultSampleRate : request.getSampleRate();
            long samples = audio.length / PCM_BYTES_PER_SAMPLE;
            return Math.max(1L, (samples * 1000L + sampleRate - 1L) / sampleRate);
        }
        Integer duration = AudioDurationProber.probeDurationMs(audio);
        return duration == null || duration <= 0 ? null : duration.longValue();
    }

    private static String format(MediaAudioGenerateRequest request, String fallback)
    {
        return StrUtil.blankToDefault(request.getAudioFormat(), fallback)
                .trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> options(MediaAudioGenerateRequest request)
    {
        return request.getOptions() == null ? Map.of() : request.getOptions();
    }

    private static Object option(MediaAudioGenerateRequest request, String key)
    {
        return options(request).get(key);
    }

    private static Integer optionIntegerNullable(MediaAudioGenerateRequest request, String key)
    {
        Object raw = option(request, key);
        if (raw == null)
        {
            return null;
        }
        if (!(raw instanceof Number))
        {
            fail("integer option invalid", "音频参数无效");
        }
        Number number = (Number) raw;
        if (number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE)
        {
            fail("integer option invalid", "音频参数无效");
        }
        return number.intValue();
    }

    private static int optionInteger(MediaAudioGenerateRequest request, String key, int fallback)
    {
        Integer value = optionIntegerNullable(request, key);
        return value == null ? fallback : value;
    }

    private static String optionText(MediaAudioGenerateRequest request, String key)
    {
        Object raw = option(request, key);
        if (raw == null)
        {
            return null;
        }
        if (!(raw instanceof String))
        {
            fail("text option invalid", "音频参数无效");
        }
        String text = (String) raw;
        if (StrUtil.isBlank(text) || text.length() > 512)
        {
            fail("text option invalid", "音频参数无效");
        }
        return text.trim();
    }

    private static List<String> optionTextList(MediaAudioGenerateRequest request, String key)
    {
        Object raw = option(request, key);
        if (raw == null)
        {
            return null;
        }
        if (!(raw instanceof List<?>))
        {
            fail("text list option invalid", "发音规则无效");
        }
        List<?> list = (List<?>) raw;
        if (list.isEmpty() || list.size() > 100)
        {
            fail("text list option invalid", "发音规则无效");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list)
        {
            if (!(item instanceof String))
            {
                fail("pronunciation item invalid", "发音规则无效");
            }
            String text = (String) item;
            if (StrUtil.isBlank(text) || text.length() > 256)
            {
                fail("pronunciation item invalid", "发音规则无效");
            }
            values.add(text.trim());
        }
        return values;
    }

    private static void requireBooleanOption(MediaAudioGenerateRequest request, String key)
    {
        Object raw = option(request, key);
        if (raw != null && !(raw instanceof Boolean))
        {
            fail("boolean option invalid", "音频开关无效");
        }
    }

    private static double speed(Integer rate)
    {
        return rate == null ? 1.0 : 1.0 + rate / 100.0;
    }

    private static double volume(Integer rate)
    {
        if (rate == null)
        {
            return 1.0;
        }
        return rate >= 0 ? 1.0 + rate * 0.09 : 1.0 + rate / 100.0;
    }

    private static void range(Integer value, int min, int max, String message)
    {
        if (value != null && (value < min || value > max))
        {
            fail("common control outside range", message);
        }
    }

    private static long positiveLong(JsonNode node)
    {
        return node.isIntegralNumber() && node.longValue() > 0 ? node.longValue() : -1;
    }

    private static long positiveOrZeroLong(JsonNode node)
    {
        return node.isIntegralNumber() && node.longValue() >= 0 ? node.longValue() : -1;
    }

    private static boolean hasPrefix(byte[] bytes, int offset, String value)
    {
        if (bytes == null || offset < 0 || bytes.length < offset + value.length())
        {
            return false;
        }
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < expected.length; index++)
        {
            if (bytes[offset + index] != expected[index])
            {
                return false;
            }
        }
        return true;
    }

    private static String trim(String value)
    {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String json(Object value)
    {
        try
        {
            return MAPPER.writeValueAsString(value);
        }
        catch (Exception exception)
        {
            throw new ServiceException("音频响应无效");
        }
    }

    private static void putBlob(ByteBuffer buffer, byte[] value)
    {
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private static int takeInt(ByteBuffer buffer)
    {
        if (buffer.remaining() < Integer.BYTES)
        {
            fail("ark integer truncated", "音频响应无效");
        }
        return buffer.getInt();
    }

    private static byte[] takeBlob(ByteBuffer buffer)
    {
        int size = takeInt(buffer);
        if (size < 0 || size > buffer.remaining())
        {
            fail("ark blob size invalid", "音频响应无效");
        }
        byte[] value = new byte[size];
        buffer.get(value);
        return value;
    }

    private static String utf8(byte[] bytes)
    {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ExplicitUpstreamFailure explicit(JsonNode node, String recovery)
    {
        JsonNode copy = node == null ? MAPPER.createObjectNode() : node.deepCopy();
        if (copy instanceof ObjectNode object)
        {
            redactMinimaxAudio(object);
            addRecovery(object, recovery);
        }
        return new ExplicitUpstreamFailure(json(copy));
    }

    private static String arkErrorAudit(ArkFrame frame, String recovery)
    {
        ObjectNode audit = MAPPER.createObjectNode();
        audit.put("protocol", ARK_PROTOCOL);
        if (frame.event() != null)
        {
            audit.put("event", frame.event());
        }
        if (frame.errorCode() != null)
        {
            audit.put("error_code", frame.errorCode());
        }
        JsonNode payload = frame.jsonPayload();
        if (payload != null)
        {
            audit.set("payload", payload);
        }
        addRecovery(audit, recovery);
        return json(audit);
    }

    private static void addRecovery(ObjectNode audit, String recovery)
    {
        String safe = StrUtil.trimToNull(recovery);
        if (safe != null && safe.length() <= 64
                && safe.indexOf('\r') < 0 && safe.indexOf('\n') < 0)
        {
            audit.put("tokendance_recovery_action", safe);
        }
    }

    private static void fail(String reason, String message)
    {
        log.info("TokenDance WebSocket 配音请求拒绝: {}", reason);
        throw new ServiceException(message);
    }

    private record SocketSession(WebSocket socket, QueueListener listener,
                                 String recoveryAction)
    {
    }

    private record Inbound(String text, byte[] binary, Throwable error, boolean closed)
    {
        private static Inbound text(String text)
        {
            return new Inbound(text, null, null, false);
        }

        private static Inbound binary(byte[] bytes)
        {
            return new Inbound(null, bytes, null, false);
        }

        private static Inbound error(Throwable error)
        {
            return new Inbound(null, null, error, false);
        }

        private static Inbound closedEvent()
        {
            return new Inbound(null, null, null, true);
        }
    }

    private static final class QueueListener implements WebSocket.Listener
    {
        private final BlockingQueue<Inbound> messages =
                new ArrayBlockingQueue<>(INBOUND_QUEUE_CAPACITY);
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private volatile WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket)
        {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            if ((long) text.length() + data.length() > MAX_CONTROL_MESSAGE_CHARS)
            {
                reject(new IllegalArgumentException("上游文本帧过大"));
                return CompletableFuture.completedFuture(null);
            }
            text.append(data);
            if (last)
            {
                enqueue(Inbound.text(text.toString()));
                text.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
        {
            int length = data.remaining();
            if ((long) binary.size() + length > MAX_BINARY_MESSAGE_BYTES)
            {
                reject(new IllegalArgumentException("上游二进制帧过大"));
                return CompletableFuture.completedFuture(null);
            }
            byte[] part = new byte[length];
            data.get(part);
            binary.writeBytes(part);
            Arrays.fill(part, (byte) 0);
            if (last)
            {
                enqueue(Inbound.binary(binary.toByteArray()));
                binary.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            enqueue(Inbound.closedEvent());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            enqueue(Inbound.error(error));
        }

        private Inbound next(Deadline deadline) throws InterruptedException, TimeoutException
        {
            Inbound inbound = messages.poll(deadline.remainingMs(), TimeUnit.MILLISECONDS);
            if (inbound == null)
            {
                throw new TimeoutException("websocket receive timeout");
            }
            return inbound;
        }

        private void reject(Throwable error)
        {
            enqueue(Inbound.error(error));
            WebSocket current = socket;
            if (current != null)
            {
                current.abort();
            }
        }

        private void enqueue(Inbound inbound)
        {
            try
            {
                messages.put(inbound);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                WebSocket current = socket;
                if (current != null)
                {
                    current.abort();
                }
            }
        }
    }

    private static final class Deadline
    {
        private final long deadlineNanos;

        private Deadline(int timeoutMs)
        {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        private long remainingMs() throws TimeoutException
        {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0)
            {
                throw new TimeoutException("websocket deadline exceeded");
            }
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
        }
    }

    private record ArkFrame(int messageType, int flags, Integer sequence, Integer event,
                            Integer errorCode, String scopeId, byte[] payload, int serialization)
    {
        private JsonNode jsonPayload()
        {
            if (payload == null || payload.length == 0 || serialization != ARK_JSON_SERIALIZATION)
            {
                return null;
            }
            try
            {
                return MAPPER.readTree(payload);
            }
            catch (Exception exception)
            {
                fail("ark json payload invalid", "音频响应无效");
                return null;
            }
        }
    }

    private static final class ArkCollector
    {
        private final ByteArrayOutputStream audio;
        private final WaveAudioAssembler wave;
        private JsonNode terminal;
        private Long usageCharacters;
        private Long durationMs;

        private ArkCollector(String format)
        {
            wave = "wav".equals(format) ? new WaveAudioAssembler() : null;
            audio = wave == null ? new ByteArrayOutputStream() : null;
        }

        private void appendAudio(byte[] chunk)
        {
            if (chunk == null || chunk.length == 0)
            {
                return;
            }
            if (chunk.length > MAX_AUDIO_BYTES - size())
            {
                fail("ark audio too large", "音频响应过大");
            }
            if (wave != null)
            {
                wave.append(chunk);
            }
            else
            {
                audio.writeBytes(chunk);
            }
        }

        private void capture(ArkFrame frame)
        {
            JsonNode payload = frame.jsonPayload();
            if (payload == null)
            {
                return;
            }
            terminal = payload;
            long words = positiveOrZeroLong(payload.path("usage").path("text_words"));
            if (words >= 0)
            {
                usageCharacters = words;
            }
            long duration = positiveLong(payload.path("audio_info").path("duration"));
            if (duration <= 0)
            {
                duration = positiveLong(payload.path("duration"));
            }
            if (duration > 0)
            {
                durationMs = duration;
            }
        }

        private int size()
        {
            return wave == null ? audio.size() : wave.size();
        }

        private byte[] finish()
        {
            return wave == null ? audio.toByteArray() : wave.finish();
        }

        private JsonNode terminal()
        {
            return terminal;
        }

        private Long usageCharacters()
        {
            return usageCharacters;
        }

        private Long durationMs()
        {
            return durationMs;
        }
    }

    private static final class ExplicitUpstreamFailure extends RuntimeException
    {
        private final String audit;

        private ExplicitUpstreamFailure(String audit)
        {
            super("explicit upstream failure", null, false, false);
            this.audit = audit;
        }

        private String audit()
        {
            return audit;
        }
    }
}
