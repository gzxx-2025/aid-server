package com.aid.compose.util;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.voice.util.DialogueSubtitleFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 根据分镜结构化台词为语音识别片段匹配人物名称，并统一清洗展示标点。 */
public final class SubtitleSpeakerMatcher {

    private static final Pattern DISPLAY_LINE = Pattern.compile("^([^：:\\r\\n]{1,40})[：:](.+)$");
    private static final double MIN_MATCH_SCORE = 0.35D;

    private SubtitleSpeakerMatcher() {
    }

    public static List<TimedSubtitleCue> match(List<TimedSubtitleCue> rawCues, String script,
                                                double mediaDurationSeconds) {
        if (CollectionUtil.isEmpty(rawCues) || mediaDurationSeconds <= 0) {
            return List.of();
        }
        List<ScriptLine> scriptLines = parseScript(script);
        List<TimedSubtitleCue> result = new ArrayList<>();
        int cursor = 0;
        int consumedCharacters = 0;
        for (TimedSubtitleCue rawCue : rawCues) {
            if (rawCue == null || rawCue.getStartSeconds() == null || rawCue.getEndSeconds() == null) {
                continue;
            }
            double start = Math.max(0D, Math.min(rawCue.getStartSeconds(), mediaDurationSeconds));
            double end = Math.max(start, Math.min(rawCue.getEndSeconds(), mediaDurationSeconds));
            String body = DialogueSubtitleFormatter.sanitizeSpokenText(rawCue.getText());
            if (StrUtil.isBlank(body) || end <= start) {
                continue;
            }
            int chosenIndex = chooseScriptLine(body, scriptLines, cursor);
            String speaker = resolveSpeaker(rawCue.getSpeaker(), scriptLines, chosenIndex);

            TimedSubtitleCue cue = new TimedSubtitleCue();
            cue.setStartSeconds(start);
            cue.setEndSeconds(end);
            cue.setSpeaker(speaker);
            cue.setText(body);
            cue.setSource("ASR");
            result.add(cue);

            if (chosenIndex >= cursor && chosenIndex < scriptLines.size()) {
                if (chosenIndex > cursor) {
                    cursor = chosenIndex;
                    consumedCharacters = 0;
                }
                consumedCharacters += normalize(body).length();
                int currentLength = normalize(scriptLines.get(cursor).text()).length();
                if (cursor < scriptLines.size() - 1
                        && consumedCharacters >= Math.max(1, (int) Math.ceil(currentLength * 0.7D))) {
                    cursor++;
                    consumedCharacters = 0;
                }
            }
        }
        return result;
    }

    private static List<ScriptLine> parseScript(String script) {
        String formatted = DialogueSubtitleFormatter.format(script);
        if (StrUtil.isBlank(formatted)) {
            return List.of();
        }
        List<ScriptLine> result = new ArrayList<>();
        for (String line : formatted.split("\\R")) {
            Matcher matcher = DISPLAY_LINE.matcher(line.trim());
            if (matcher.matches() && StrUtil.isNotBlank(matcher.group(2))) {
                result.add(new ScriptLine(matcher.group(1).trim(), matcher.group(2).trim()));
            }
        }
        return result;
    }

    private static int chooseScriptLine(String cueText, List<ScriptLine> scriptLines, int cursor) {
        if (scriptLines.isEmpty()) {
            return -1;
        }
        String normalizedCue = normalize(cueText);
        int bestIndex = Math.min(cursor, scriptLines.size() - 1);
        double bestScore = 0D;
        for (int index = bestIndex; index < scriptLines.size(); index++) {
            String normalizedScript = normalize(scriptLines.get(index).text());
            double score = similarity(normalizedCue, normalizedScript);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore >= MIN_MATCH_SCORE ? bestIndex : Math.min(cursor, scriptLines.size() - 1);
    }

    private static String resolveSpeaker(String providerSpeaker, List<ScriptLine> lines, int index) {
        if (index >= 0 && index < lines.size()) {
            return StrUtil.blankToDefault(lines.get(index).speaker(), "旁白");
        }
        if (StrUtil.isNotBlank(providerSpeaker)
                && !providerSpeaker.toLowerCase().startsWith("speaker")) {
            return providerSpeaker.trim();
        }
        return "旁白";
    }

    private static double similarity(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0D;
        }
        if (left.contains(right) || right.contains(left)) {
            return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length());
        }
        int lcs = longestCommonSubsequence(left, right);
        return (double) lcs / Math.min(left.length(), right.length());
    }

    private static int longestCommonSubsequence(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            for (int j = 1; j <= right.length(); j++) {
                current[j] = left.charAt(i - 1) == right.charAt(j - 1)
                        ? previous[j - 1] + 1 : Math.max(previous[j], current[j - 1]);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static String normalize(String text) {
        String normalized = DialogueSubtitleFormatter.sanitizeSpokenText(text);
        return StrUtil.isBlank(normalized) ? "" : normalized.replace(" ", "").toLowerCase();
    }

    private record ScriptLine(String speaker, String text) {
    }
}
