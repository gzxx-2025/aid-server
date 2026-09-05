package com.aid.billing.util;

import com.aid.media.dto.MediaTextGenerateRequest;

import java.util.List;

/** 文本请求 token 估算器；保守口径按 UTF-8 字节和消息协议开销计算。 */
public final class TextTokenEstimator {

    private static final int REQUEST_FRAMING_TOKENS = 8;
    private static final int MESSAGE_FRAMING_TOKENS = 12;

    private TextTokenEstimator() {
    }

    public static int estimateRequestConservative(MediaTextGenerateRequest request) {
        long tokens = REQUEST_FRAMING_TOKENS;
        if (request == null) {
            return saturatingInt(tokens);
        }
        List<MediaTextGenerateRequest.TextMessageItem> messages = request.getMessages();
        if (messages != null) {
            for (MediaTextGenerateRequest.TextMessageItem message : messages) {
                if (message == null) {
                    continue;
                }
                tokens = safeAdd(tokens, MESSAGE_FRAMING_TOKENS);
                tokens = safeAdd(tokens, utf8Length(message.getRole()));
                tokens = safeAdd(tokens, utf8Length(message.getContent()));
                tokens = safeAdd(tokens, estimateMediaTokens(message.getParts()));
            }
        }
        if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
            tokens = safeAdd(tokens, MESSAGE_FRAMING_TOKENS + 4L);
            tokens = safeAdd(tokens, utf8Length(request.getPrompt()));
        }
        return saturatingInt(tokens);
    }

    /** 允许补扣时使用的平衡估算，兼顾英文、CJK、标点和长连续串。 */
    public static int estimateRequestBalanced(MediaTextGenerateRequest request) {
        long quarterTokens = REQUEST_FRAMING_TOKENS * 4L;
        if (request == null) {
            return saturatingInt(ceilDiv(quarterTokens, 4L));
        }
        List<MediaTextGenerateRequest.TextMessageItem> messages = request.getMessages();
        if (messages != null) {
            for (MediaTextGenerateRequest.TextMessageItem message : messages) {
                if (message == null) {
                    continue;
                }
                quarterTokens = safeAdd(quarterTokens, MESSAGE_FRAMING_TOKENS * 4L);
                quarterTokens = safeAdd(quarterTokens, balancedQuarterTokens(message.getRole()));
                quarterTokens = safeAdd(quarterTokens, balancedQuarterTokens(message.getContent()));
                quarterTokens = safeAdd(quarterTokens, estimateMediaTokens(message.getParts()) * 4L);
            }
        }
        if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
            quarterTokens = safeAdd(quarterTokens, (MESSAGE_FRAMING_TOKENS + 4L) * 4L);
            quarterTokens = safeAdd(quarterTokens, balancedQuarterTokens(request.getPrompt()));
        }
        return saturatingInt(ceilDiv(quarterTokens, 4L));
    }

    /** 仅有字符数时使用的保守估算。 */
    public static int estimateUnknownTextConservative(int chars, int messageCount) {
        if (chars <= 0) {
            return Math.max(0, messageCount) * MESSAGE_FRAMING_TOKENS + REQUEST_FRAMING_TOKENS;
        }
        long tokens = (long) chars * 4L;
        tokens = safeAdd(tokens, (long) Math.max(0, messageCount) * MESSAGE_FRAMING_TOKENS);
        tokens = safeAdd(tokens, REQUEST_FRAMING_TOKENS);
        return saturatingInt(tokens);
    }

    public static int saturatedCharacterCount(MediaTextGenerateRequest request) {
        long chars = 0L;
        if (request != null) {
            if (request.getPrompt() != null) {
                chars = safeAdd(chars, request.getPrompt().length());
            }
            if (request.getMessages() != null) {
                for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
                    if (message != null && message.getContent() != null) {
                        chars = safeAdd(chars, message.getContent().length());
                    }
                    if (message != null && message.getParts() != null) {
                        for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                            if (part != null && "text".equalsIgnoreCase(part.getType()) && part.getText() != null) {
                                chars = safeAdd(chars, part.getText().length());
                            }
                        }
                    }
                }
            }
        }
        return saturatingInt(chars);
    }

    private static long balancedQuarterTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long units = 0L;
        int asciiRun = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp <= 0x7f && Character.isLetterOrDigit(cp)) {
                asciiRun++;
                units = safeAdd(units, 1L);
                continue;
            }
            if (asciiRun > 0) {
                units = safeAdd(units, 1L + (asciiRun >= 32 ? asciiRun : 0L));
                asciiRun = 0;
            }
            if (cp <= 0x7f && Character.isWhitespace(cp)) {
                continue;
            }
            if (cp <= 0x7f) {
                units = safeAdd(units, 4L);
            } else if (Character.isSupplementaryCodePoint(cp)) {
                units = safeAdd(units, 8L);
            } else {
                units = safeAdd(units, 6L);
            }
        }
        if (asciiRun > 0) {
            units = safeAdd(units, 1L + (asciiRun >= 32 ? asciiRun : 0L));
        }
        return units;
    }

    private static long utf8Length(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long bytes = 0L;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            bytes = safeAdd(bytes, cp <= 0x7f ? 1L : cp <= 0x7ff ? 2L
                    : Character.isSupplementaryCodePoint(cp) ? 4L : 3L);
        }
        return bytes;
    }

    /** 多模态预授权使用保守内部估算；最终结算以供应商 usage 为准。 */
    private static long estimateMediaTokens(List<MediaTextGenerateRequest.TextContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (MediaTextGenerateRequest.TextContentPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            String type = part.getType().trim().toLowerCase();
            switch (type) {
                case "text" -> total = safeAdd(total, utf8Length(part.getText()));
                case "image" -> {
                    long pixels = part.getWidth() == null || part.getHeight() == null ? 0L
                            : (long) part.getWidth() * part.getHeight();
                    total = safeAdd(total, pixels > 0L ? 2L + ceilDiv(pixels, 1024L) : 2048L);
                }
                case "video" -> total = safeAdd(total, part.getDurationSeconds() == null
                        ? 18000L : Math.max(1L, (long) Math.ceil(part.getDurationSeconds() * 300D)));
                case "audio" -> total = safeAdd(total, part.getDurationSeconds() == null
                        ? 1920L : Math.max(1L, (long) Math.ceil(part.getDurationSeconds() * 32D)));
                case "document" -> total = safeAdd(total, part.getPageCount() == null
                        ? 2580L : Math.max(1L, (long) part.getPageCount() * 258L));
                default -> {
                }
            }
        }
        return total;
    }

    private static long ceilDiv(long value, long divisor) {
        return value <= 0L ? 0L : 1L + (value - 1L) / divisor;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(0L, Math.min(value, Integer.MAX_VALUE));
    }
}
