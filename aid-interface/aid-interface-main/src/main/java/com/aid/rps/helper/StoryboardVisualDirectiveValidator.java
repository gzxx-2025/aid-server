package com.aid.rps.helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aid.common.exception.ServiceException;
import com.aid.rps.helper.StoryboardSceneEnvelopeParser.SceneEnvelope;
import com.aid.rps.helper.StoryboardScriptCoveragePlanner.CoverageBatch;
import com.aid.rps.helper.StoryboardScriptCoveragePlanner.DirectiveType;
import com.aid.rps.helper.StoryboardScriptCoveragePlanner.VisualDirective;
import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 校验分镜正文覆盖区间及其绑定的显式视觉指令。
 *
 * @author 视觉AID
 */
@Slf4j
public final class StoryboardVisualDirectiveValidator
{
    private static final int CATASTROPHIC_MIN_EXPECTED_LENGTH = 240;
    private static final int CATASTROPHIC_MIN_UNIT_COUNT = 4;
    private static final int CATASTROPHIC_MIN_MISSING_UNITS = 2;
    private static final int CATASTROPHIC_NEGLIGIBLE_DIVISOR = 4;
    private static final int QUALITY_TARGET_UNIT_LENGTH = 48;
    private static final int QUALITY_MAX_UNIT_LENGTH = 160;
    private static final int QUALITY_MIN_ANCHOR_LENGTH = 6;
    private static final int QUALITY_MAX_ANCHOR_LENGTH = 14;
    private static final int QUALITY_ANCHOR_PADDING = 3;

    private StoryboardVisualDirectiveValidator()
    {
    }

    /**
     * 评估有效正文覆盖质量与视觉指令落实情况。
     *
     * @param envelopes 已解析的场次输出
     * @param expectedBatch 当前覆盖批次
     * @param writerOutput 是否为专业版输出
     * @param allowPartialCoverage 是否允许选择性覆盖
     */
    public static void validate(List<SceneEnvelope> envelopes, CoverageBatch expectedBatch,
                                boolean writerOutput, boolean allowPartialCoverage)
    {
        List<ShotView> shots = flattenShots(envelopes, writerOutput);
        String expectedNarrative = Objects.isNull(expectedBatch) ? "" : expectedBatch.narrativeText();
        String source = StoryboardScriptCoveragePlanner.normalizeCoverageText(expectedNarrative);
        CoverageAssessment coverage = assessCoverage(
                shots, expectedNarrative, source, allowPartialCoverage);
        if (coverage.decision() == CoverageDecision.EMPTY)
        {
            log.error("分镜内容质量告警: issue={}, blocking=true, selective={}, shotCount={}",
                    coverage.issue(), allowPartialCoverage, shots.size());
            throw new ServiceException("剧本内容缺失");
        }
        if (coverage.decision() == CoverageDecision.CATASTROPHIC_TRUNCATION)
        {
            log.error("分镜内容质量告警: issue={}, blocking=true, selective=false, expectedLength={}, "
                            + "actualLength={}, unitCount={}, evidencedUnits={}, firstEvidenceUnit={}, "
                            + "lastEvidenceUnit={}, "
                            + "minimumInformationLength={}",
                    coverage.issue(), coverage.expectedLength(), coverage.actualLength(),
                    coverage.unitCount(), coverage.evidencedUnits(), coverage.firstEvidenceUnit(),
                    coverage.lastEvidenceUnit(), coverage.minimumInformationLength());
            throw new ServiceException("剧本内容覆盖不完整");
        }
        if (coverage.decision() == CoverageDecision.WARNING)
        {
            log.warn("分镜内容质量告警: issue={}, blocking=false, selective={}, expectedLength={}, "
                            + "actualLength={}, unitCount={}, evidencedUnits={}, firstEvidenceUnit={}, "
                            + "lastEvidenceUnit={}, "
                            + "emptyShotCount={}",
                    coverage.issue(), allowPartialCoverage, coverage.expectedLength(),
                    coverage.actualLength(), coverage.unitCount(), coverage.evidencedUnits(),
                    coverage.firstEvidenceUnit(), coverage.lastEvidenceUnit(), coverage.emptyShotCount());
        }
        else if (coverage.emptyShotCount() > 0)
        {
            log.warn("分镜内容质量告警: issue=EMPTY_SHOT_CONTENT, blocking=false, selective={}, "
                    + "shotCount={}, emptyShotCount={}",
                    allowPartialCoverage, shots.size(), coverage.emptyShotCount());
        }
        if (Objects.isNull(expectedBatch) || expectedBatch.directives().isEmpty())
        {
            return;
        }
        for (VisualDirective directive : expectedBatch.directives())
        {
            ShotSpan target = findTargetSpan(coverage.spans(), directive.targetOffset());
            if (Objects.isNull(target))
            {
                log.warn("分镜内容质量告警: issue=DIRECTIVE_ANCHOR_MISSING, blocking=false, "
                                + "selective={}, type={}, value={}, offset={}",
                        allowPartialCoverage, directive.type(), directive.value(), directive.targetOffset());
                continue;
            }
            if (!matchesDirective(target.shot(), directive, writerOutput))
            {
                log.warn("分镜内容质量告警: issue=DIRECTIVE_VALUE_DIFFERENCE, blocking=false, "
                                + "selective={}, type={}, value={}, anchorBefore={}, anchorAfter={}, shotIndex={}",
                        allowPartialCoverage, directive.type(), directive.value(), directive.anchorBefore(),
                        directive.anchorAfter(), target.shot().index());
            }
        }
    }

    private static List<ShotView> flattenShots(List<SceneEnvelope> envelopes, boolean writerOutput)
    {
        List<ShotView> result = new ArrayList<>();
        if (envelopes == null)
        {
            return result;
        }
        int index = 0;
        for (SceneEnvelope envelope : envelopes)
        {
            if (Objects.isNull(envelope) || envelope.shots() == null)
            {
                continue;
            }
            for (JsonNode shot : envelope.shots())
            {
                Map<String, String> fields = writerOutput
                        ? extractWriterFields(shot.path("content")) : extractStandardFields(shot);
                String scriptContent = writerOutput
                        ? fields.getOrDefault("剧本内容", "")
                        : shot.path("scriptContent").asText("");
                result.add(new ShotView(index++, scriptContent, fields));
            }
        }
        return result;
    }

    private static CoverageAssessment assessCoverage(List<ShotView> shots, String expectedNarrative,
                                                       String source, boolean allowPartialCoverage)
    {
        StringBuilder combined = new StringBuilder();
        int nonEmptyShotCount = 0;
        int emptyShotCount = 0;
        for (ShotView shot : shots)
        {
            String candidate = StoryboardScriptCoveragePlanner.normalizeCoverageText(shot.scriptContent());
            if (StrUtil.isBlank(candidate))
            {
                emptyShotCount++;
                continue;
            }
            nonEmptyShotCount++;
            combined.append(candidate);
        }
        String actual = combined.toString();
        QualityText expectedQuality = normalizeQualityText(source);
        QualityText actualQuality = normalizeQualityText(actual);
        List<String> units = buildQualityUnits(expectedNarrative);
        Evidence evidence = findEvidence(units, actualQuality.text());
        int minimumInformationLength = calculateMinimumInformationLength(
                expectedQuality.text().length(), units.size());
        List<ShotSpan> spans = matchCoverageSpans(shots, source);

        if (nonEmptyShotCount == 0 || StrUtil.isBlank(actualQuality.text()))
        {
            return new CoverageAssessment(CoverageDecision.EMPTY, "ALL_SHOT_CONTENT_EMPTY",
                    expectedQuality.text().length(), 0, units.size(), evidence.count(),
                    evidence.firstIndex(), evidence.lastIndex(), minimumInformationLength,
                    emptyShotCount, spans);
        }

        if (Objects.equals(expectedQuality.text(), actualQuality.text()))
        {
            return new CoverageAssessment(CoverageDecision.EQUIVALENT, "EQUIVALENT",
                    expectedQuality.text().length(), actualQuality.text().length(), units.size(),
                    evidence.count(), evidence.firstIndex(), evidence.lastIndex(), minimumInformationLength,
                    emptyShotCount, spans);
        }

        if (isCatastrophicTruncation(expectedQuality.text().length(), actualQuality.text().length(),
                units.size(), evidence, minimumInformationLength, allowPartialCoverage))
        {
            return new CoverageAssessment(CoverageDecision.CATASTROPHIC_TRUNCATION,
                    "CATASTROPHIC_FRAGMENT_TRUNCATION", expectedQuality.text().length(),
                    actualQuality.text().length(), units.size(), evidence.count(),
                    evidence.firstIndex(), evidence.lastIndex(), minimumInformationLength,
                    emptyShotCount, spans);
        }

        String issue = hasSameCharacters(expectedQuality.text(), actualQuality.text())
                ? "CONTENT_ORDER_DIFFERENCE" : "CONTENT_COVERAGE_DIFFERENCE";
        return new CoverageAssessment(CoverageDecision.WARNING, issue,
                expectedQuality.text().length(), actualQuality.text().length(), units.size(),
                evidence.count(), evidence.firstIndex(), evidence.lastIndex(), minimumInformationLength,
                emptyShotCount, spans);
    }

    private static List<ShotSpan> matchCoverageSpans(List<ShotView> shots, String source)
    {
        List<ShotSpan> spans = new ArrayList<>();
        QualityText qualitySource = normalizeQualityText(source);
        int strictCursor = 0;
        int qualityCursor = 0;
        for (ShotView shot : shots)
        {
            String candidate = StoryboardScriptCoveragePlanner.normalizeCoverageText(shot.scriptContent());
            if (StrUtil.isBlank(candidate))
            {
                continue;
            }
            int matchedAt = source.indexOf(candidate, strictCursor);
            if (matchedAt >= 0)
            {
                spans.add(new ShotSpan(shot, matchedAt, matchedAt + candidate.length()));
                strictCursor = matchedAt + candidate.length();
                qualityCursor = qualitySource.indexAtOrAfter(strictCursor);
                continue;
            }

            String qualityCandidate = normalizeQualityText(candidate).text();
            int qualityAt = StrUtil.isBlank(qualityCandidate)
                    ? -1 : qualitySource.text().indexOf(qualityCandidate, qualityCursor);
            if (qualityAt < 0)
            {
                continue;
            }
            int start = qualitySource.sourceStart(qualityAt);
            int end = qualitySource.sourceEnd(qualityAt + qualityCandidate.length() - 1);
            spans.add(new ShotSpan(shot, start, end));
            strictCursor = end;
            qualityCursor = qualityAt + qualityCandidate.length();
        }
        return spans;
    }

    private static boolean isCatastrophicTruncation(int expectedLength, int actualLength,
                                                      int unitCount, Evidence evidence,
                                                      int minimumInformationLength,
                                                      boolean allowPartialCoverage)
    {
        if (allowPartialCoverage || expectedLength < CATASTROPHIC_MIN_EXPECTED_LENGTH
                || unitCount < CATASTROPHIC_MIN_UNIT_COUNT
                || actualLength >= minimumInformationLength)
        {
            return false;
        }
        int averageUnitLength = (int) Math.ceil((double) expectedLength / unitCount);
        int missingLength = expectedLength - actualLength;
        boolean missesMultipleUnits = missingLength
                > averageUnitLength * CATASTROPHIC_MIN_MISSING_UNITS;
        int evidenceSpan = evidence.count() == 0
                ? unitCount : evidence.lastIndex() - evidence.firstIndex() + 1;
        boolean evidenceInNarrowFragment = evidence.count() > 0
                && evidenceSpan * 2 < unitCount;
        int negligibleOutputLimit = Math.min(QUALITY_TARGET_UNIT_LENGTH / 2,
                (int) Math.ceil((double) minimumInformationLength
                        / CATASTROPHIC_NEGLIGIBLE_DIVISOR));
        boolean negligibleOutput = actualLength < negligibleOutputLimit;
        return missesMultipleUnits && (evidenceInNarrowFragment || negligibleOutput);
    }

    private static int calculateMinimumInformationLength(int expectedLength, int unitCount)
    {
        if (expectedLength <= 0 || unitCount <= 0)
        {
            return 0;
        }
        return (int) Math.ceil(expectedLength / Math.sqrt(unitCount));
    }

    private static List<String> buildQualityUnits(String value)
    {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        String text = StrUtil.blankToDefault(value, "");
        for (int offset = 0; offset < text.length();)
        {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCoverageBoundary(codePoint))
            {
                addQualitySegment(segments, segment);
            }
            else
            {
                segment.appendCodePoint(codePoint);
            }
        }
        addQualitySegment(segments, segment);

        List<String> units = new ArrayList<>();
        StringBuilder unit = new StringBuilder();
        for (String current : segments)
        {
            int cursor = 0;
            while (cursor < current.length())
            {
                int remainingCapacity = QUALITY_MAX_UNIT_LENGTH - unit.length();
                int take = Math.min(remainingCapacity, current.length() - cursor);
                if (take > 0 && cursor + take < current.length()
                        && Character.isHighSurrogate(current.charAt(cursor + take - 1))
                        && Character.isLowSurrogate(current.charAt(cursor + take)))
                {
                    take--;
                }
                unit.append(current, cursor, cursor + take);
                cursor += take;
                if (unit.length() >= QUALITY_TARGET_UNIT_LENGTH
                        || unit.length() >= QUALITY_MAX_UNIT_LENGTH)
                {
                    units.add(unit.toString());
                    unit.setLength(0);
                }
            }
        }
        if (!unit.isEmpty())
        {
            if (!units.isEmpty() && unit.length() < QUALITY_TARGET_UNIT_LENGTH / 2)
            {
                int lastIndex = units.size() - 1;
                units.set(lastIndex, units.get(lastIndex) + unit);
            }
            else
            {
                units.add(unit.toString());
            }
        }
        return units;
    }

    private static void addQualitySegment(List<String> segments, StringBuilder segment)
    {
        String normalized = normalizeQualityText(segment.toString()).text();
        if (StrUtil.isNotBlank(normalized))
        {
            segments.add(normalized);
        }
        segment.setLength(0);
    }

    private static boolean isCoverageBoundary(int codePoint)
    {
        return codePoint == '\n' || codePoint == '\r' || codePoint == '。'
                || codePoint == '！' || codePoint == '？' || codePoint == '!'
                || codePoint == '?' || codePoint == '；' || codePoint == ';';
    }

    private static Evidence findEvidence(List<String> units, String actual)
    {
        int count = 0;
        int firstIndex = -1;
        int lastIndex = -1;
        for (int i = 0; i < units.size(); i++)
        {
            if (containsUnitEvidence(actual, units.get(i)))
            {
                if (firstIndex < 0)
                {
                    firstIndex = i;
                }
                count++;
                lastIndex = i;
            }
        }
        return new Evidence(count, firstIndex, lastIndex);
    }

    private static boolean containsUnitEvidence(String actual, String unit)
    {
        if (StrUtil.isBlank(actual) || StrUtil.isBlank(unit))
        {
            return false;
        }
        int anchorLength = Math.min(QUALITY_MAX_ANCHOR_LENGTH,
                Math.max(QUALITY_MIN_ANCHOR_LENGTH,
                        (int) Math.ceil(Math.sqrt(unit.length())) + QUALITY_ANCHOR_PADDING));
        if (unit.length() <= anchorLength)
        {
            return actual.contains(unit);
        }
        int lastStart = unit.length() - anchorLength;
        int middleStart = lastStart / 2;
        return actual.contains(unit.substring(0, anchorLength))
                || actual.contains(unit.substring(middleStart, middleStart + anchorLength))
                || actual.contains(unit.substring(lastStart));
    }

    private static boolean hasSameCharacters(String expected, String actual)
    {
        int[] expectedCharacters = expected.codePoints().toArray();
        int[] actualCharacters = actual.codePoints().toArray();
        if (expectedCharacters.length != actualCharacters.length)
        {
            return false;
        }
        Arrays.sort(expectedCharacters);
        Arrays.sort(actualCharacters);
        return Arrays.equals(expectedCharacters, actualCharacters);
    }

    private static QualityText normalizeQualityText(String value)
    {
        String text = StrUtil.blankToDefault(value, "");
        StringBuilder normalized = new StringBuilder(text.length());
        List<Integer> sourceStarts = new ArrayList<>();
        List<Integer> sourceEnds = new ArrayList<>();
        for (int offset = 0; offset < text.length();)
        {
            int codePoint = text.codePointAt(offset);
            int sourceEnd = offset + Character.charCount(codePoint);
            String compatible = Normalizer.normalize(
                    new String(Character.toChars(codePoint)), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            for (int normalizedOffset = 0; normalizedOffset < compatible.length();)
            {
                int normalizedCodePoint = compatible.codePointAt(normalizedOffset);
                normalizedOffset += Character.charCount(normalizedCodePoint);
                if (!isIgnoredQualityCodePoint(normalizedCodePoint))
                {
                    normalized.appendCodePoint(normalizedCodePoint);
                    int normalizedWidth = Character.charCount(normalizedCodePoint);
                    for (int i = 0; i < normalizedWidth; i++)
                    {
                        sourceStarts.add(offset);
                        sourceEnds.add(sourceEnd);
                    }
                }
            }
            offset = sourceEnd;
        }
        return new QualityText(normalized.toString(), toIntArray(sourceStarts), toIntArray(sourceEnds));
    }

    private static boolean isIgnoredQualityCodePoint(int codePoint)
    {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                || codePoint == 0x2060 || codePoint == 0xFEFF)
        {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || codePoint == '*' || codePoint == '#' || codePoint == '~' || codePoint == '`'
                || codePoint == '>' || codePoint == '<' || codePoint == '|' || codePoint == '+';
    }

    private static int[] toIntArray(List<Integer> values)
    {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++)
        {
            result[i] = values.get(i);
        }
        return result;
    }

    private static ShotSpan findTargetSpan(List<ShotSpan> spans, int targetOffset)
    {
        for (ShotSpan span : spans)
        {
            if (targetOffset >= span.start() && targetOffset < span.end())
            {
                return span;
            }
        }
        return null;
    }

    private static boolean matchesDirective(ShotView shot, VisualDirective directive,
                                            boolean writerOutput)
    {
        if (writerOutput)
        {
            String shotScript = shot.fields().getOrDefault("镜头脚本", "");
            String visualDescription = shot.fields().getOrDefault("画面说明", "");
            return switch (directive.type())
            {
                case SHOT_SIZE -> matchesLabeledEnum(shotScript, "景别", directive.value());
                case CAMERA_ANGLE -> matchesLabeledEnum(shotScript, "视角", directive.value());
                case CAMERA_MOVEMENT -> matchesLabeledCanonicalToken(
                        shotScript, "机位", directive.value());
                case FREEZE_FRAME -> containsFreezeInstruction(shotScript);
                case TRANSITION -> containsTransition(shotScript, directive.value());
                case SCREEN_TEXT -> containsExactText(shotScript, directive.value())
                        || containsExactText(visualDescription, directive.value());
            };
        }

        return switch (directive.type())
        {
            case SHOT_SIZE -> equalsEnumValue(shot.fields().get("shotSize"), directive.value());
            case CAMERA_ANGLE -> equalsEnumValue(shot.fields().get("cameraAngle"), directive.value());
            case CAMERA_MOVEMENT -> equalsEnumValue(shot.fields().get("cameraMovement"), directive.value());
            case FREEZE_FRAME -> containsFreezeInstruction(shot.fields().get("visualDescription"))
                    || containsFreezeInstruction(shot.fields().get("actionState"));
            case TRANSITION -> containsTransition(shot.fields().get("visualDescription"), directive.value())
                    || containsTransition(shot.fields().get("actionState"), directive.value());
            case SCREEN_TEXT -> containsExactText(shot.fields().get("visualDescription"), directive.value())
                    || containsExactText(shot.fields().get("actionState"), directive.value());
        };
    }

    private static boolean matchesLabeledEnum(String text, String label, String expected)
    {
        if (StrUtil.isBlank(text) || StrUtil.isBlank(expected))
        {
            return false;
        }
        Pattern labelPattern = Pattern.compile(Pattern.quote(label) + "\\s*[：:]\\s*([^，,；;。\\n]+)");
        Matcher matcher = labelPattern.matcher(text);
        while (matcher.find())
        {
            String rawValue = matcher.group(1).trim();
            String expectedValue = normalizeEnumValue(expected);
            String normalizedValue = normalizeEnumValue(rawValue);
            if (Objects.equals(normalizedValue, expectedValue)
                    || normalizedValue.startsWith(expectedValue + "/")
                    || normalizedValue.startsWith(expectedValue + "|")
                    || normalizedValue.startsWith(expectedValue + "-")
                    || normalizedValue.startsWith(expectedValue + "—"))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsEnumValue(String actual, String expected)
    {
        return StrUtil.isNotBlank(actual) && Objects.equals(
                normalizeEnumValue(actual), normalizeEnumValue(expected));
    }

    private static boolean containsFreezeInstruction(String value)
    {
        String normalized = normalizeSearchText(value);
        return normalized.contains("画面定格") || normalized.contains("定格画面")
                || normalized.contains("冻结画面") || normalized.contains("定格");
    }

    private static boolean containsExactText(String source, String expected)
    {
        String normalizedExpected = normalizeSearchText(expected);
        return StrUtil.isNotBlank(normalizedExpected)
                && normalizeSearchText(source).contains(normalizedExpected);
    }

    private static boolean containsTransition(String source, String expected)
    {
        String normalizedExpected = normalizeSearchText(expected).toLowerCase(java.util.Locale.ROOT);
        return StrUtil.isNotBlank(normalizedExpected)
                && normalizeSearchText(source).toLowerCase(java.util.Locale.ROOT)
                        .contains(normalizedExpected);
    }

    private static boolean containsCanonicalToken(String source, String canonical)
    {
        if (StrUtil.isBlank(source) || StrUtil.isBlank(canonical))
        {
            return false;
        }
        String boundary = "[\\s：:,，；;/|()（）\\[\\]。]";
        Pattern token = Pattern.compile("(?:^|" + boundary + ")"
                + Pattern.quote(canonical) + "(?:$|" + boundary + ")");
        return token.matcher(source).find();
    }

    private static boolean matchesLabeledCanonicalToken(String text, String label, String canonical)
    {
        if (StrUtil.isBlank(text) || StrUtil.isBlank(canonical))
        {
            return false;
        }
        Pattern fieldPattern = Pattern.compile(
                Pattern.quote(label) + "\\s*[：:]\\s*(.*?)(?=(?:[；;/]\\s*)?"
                        + "(?:景别|视角|场景参考|入画主体|动作|台词|画面文字)\\s*[：:]|$)",
                Pattern.DOTALL);
        Matcher matcher = fieldPattern.matcher(text);
        while (matcher.find())
        {
            if (containsCanonicalToken(matcher.group(1), canonical))
            {
                return true;
            }
        }
        return false;
    }

    private static String normalizeEnumValue(String value)
    {
        return Normalizer.normalize(StrUtil.blankToDefault(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").trim();
    }

    private static String normalizeSearchText(String value)
    {
        return StoryboardScriptCoveragePlanner.normalizeCoverageText(value);
    }

    private static Map<String, String> extractStandardFields(JsonNode shot)
    {
        Map<String, String> fields = new LinkedHashMap<>();
        shot.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asText("")));
        return fields;
    }

    private static Map<String, String> extractWriterFields(JsonNode content)
    {
        return StoryboardWriterContentParser.parse(content);
    }

    private record ShotView(int index, String scriptContent, Map<String, String> fields) { }

    private record ShotSpan(ShotView shot, int start, int end) { }

    private record Evidence(int count, int firstIndex, int lastIndex) { }

    private record CoverageAssessment(CoverageDecision decision, String issue,
                                      int expectedLength, int actualLength,
                                      int unitCount, int evidencedUnits,
                                      int firstEvidenceUnit, int lastEvidenceUnit,
                                      int minimumInformationLength,
                                      int emptyShotCount, List<ShotSpan> spans) { }

    private record QualityText(String text, int[] sourceStarts, int[] sourceEnds)
    {
        private int sourceStart(int index)
        {
            return index >= 0 && index < sourceStarts.length ? sourceStarts[index] : 0;
        }

        private int sourceEnd(int index)
        {
            return index >= 0 && index < sourceEnds.length ? sourceEnds[index] : 0;
        }

        private int indexAtOrAfter(int sourceOffset)
        {
            int low = 0;
            int high = sourceStarts.length;
            while (low < high)
            {
                int middle = (low + high) >>> 1;
                if (sourceStarts[middle] < sourceOffset)
                {
                    low = middle + 1;
                }
                else
                {
                    high = middle;
                }
            }
            return low;
        }
    }

    private enum CoverageDecision
    {
        EQUIVALENT,
        WARNING,
        EMPTY,
        CATASTROPHIC_TRUNCATION
    }
}
