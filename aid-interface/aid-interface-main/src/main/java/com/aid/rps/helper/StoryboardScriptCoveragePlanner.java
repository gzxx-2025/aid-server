package com.aid.rps.helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * 将原始剧本文档规划为可严格覆盖的叙事批次与独立视觉约束。
 *
 * @author 视觉AID
 */
@Component
public class StoryboardScriptCoveragePlanner
{
    public static final String PLAN_VERSION = "semantic-v1";

    private static final int DEFAULT_TARGET_CHUNK_SIZE = 3000;
    private static final int ABSOLUTE_HARD_CHUNK_SIZE = 6000;
    private static final int ANCHOR_LENGTH = 48;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*(.*?)\\s*#*\\s*$");
    private static final Pattern BODY_HEADING = Pattern.compile(
            "^(?:(?:正片|正文|剧本正文|故事正文|影片正文|电影正文|分镜正文|本集正文|本片正文)(?:内容)?|"
                    + "剧本内容|剧情正文|剧情内容)$");
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "^(?:角色|人物|角色设定|人物设定|角色表|人物表|场景|场景设定|场景表|道具|道具设定|道具表|资产|视觉资产)(?:资料|信息|列表|说明)?$");
    private static final Pattern TIME_SECTION_HEADING = Pattern.compile(
            "^(?:\\d{1,4}\\s*[-—~～至]\\s*\\d{1,4}\\s*(?:秒|s|S|分钟|min)(?:\\s*[：:].*)?|"
                    + "\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[-—~～至]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[：:].*)?)$");
    private static final Pattern ACT_SECTION_HEADING = Pattern.compile(
            "^(?:第[零〇一二三四五六七八九十百千万两\\d]+(?:幕|章|节|场|回)|序幕|尾声|开场|收尾)(?:\\s*[：:].*)?$");
    private static final Pattern SCREENPLAY_SCENE_HEADING = Pattern.compile(
            "^(?:(?:INT|EXT|INT\\.?/EXT|I/E)\\.?\\s+.+|(?:场景|场次)\\s*[零〇一二三四五六七八九十百千万两\\d]+(?:\\s*[：:、.．-]?\\s*.*)?(?:\\s+(?:内|外)(?:\\s+(?:日|夜|晨|昏|黄昏|清晨))?)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_SCENE_SLUG = Pattern.compile(
            "^(?:\\d+[.、．]\\s*)?(?:内|外)(?:景)?\\s*[·.。/\\-— ]+.+(?:\\s*[·.。/\\-— ]+(?:日|夜|晨|昏|黄昏|清晨))$");
    private static final Pattern LABELED_SPEAKER_CUE = Pattern.compile(
            "^(?:[\\p{L}\\p{N}_·•]{1,40}|旁白|画外音|OS|VO|V\\.O\\.|O\\.S\\.)\\s*[：:]$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDALONE_SPEAKER_CUE = Pattern.compile(
            "^(?:[\\p{IsHan}·•]{1,16}|[A-Z][A-Z0-9 _.'·•-]{0,39})$");
    private static final Pattern DIALOGUE_PARENTHETICAL = Pattern.compile(
            "^[（(][^（）()\\n]{1,80}[）)]$");
    private static final Pattern PREVIEW_LINE = Pattern.compile(
            "^[【\\[]?\\s*(?:下一集预告|下集预告|下一回预告|下回预告|下一集|下集|下回|未完待续|敬请期待)"
                    + "(?:\\s*[：:].*)?\\s*[】\\]]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIEW_WITH_SCREEN_LABEL = Pattern.compile(
            "^(?:字幕|画面文字|屏幕文字|屏显|字卡|标题卡)\\s*[：:]\\s*[【\\[]?\\s*"
                    + "(?:下一集预告|下集预告|下一回预告|下回预告|下一集|下集|下回|未完待续|敬请期待)"
                    + "(?:\\s*[：:].*)?\\s*[】\\]]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCREEN_TEXT_LINE = Pattern.compile(
            "^(字幕|画面文字|屏幕文字|屏显|字卡|标题卡|系统提示|系统文字|系统UI|UI文字|地点字幕|时间字幕)\\s*[：:]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_CAPTION_LINE = Pattern.compile(
            "^(?:对白字幕|对白自动字幕|自动字幕)\\s*[：:]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_DIRECTIVE = Pattern.compile("^[【\\[]\\s*(.*?)\\s*[】\\]]$");

    private static final Map<String, String> SHOT_SIZE_ALIASES = aliasMap(
            alias("大远景", "大远景"), alias("远景", "远景"), alias("全景", "全景"),
            alias("中全景/膝上景", "中全景/膝上景"), alias("中全景", "中全景/膝上景"),
            alias("膝上景", "中全景/膝上景"), alias("中景", "中景"), alias("中近景", "中近景"),
            alias("近景", "近景"), alias("特写", "大特写"), alias("大特写", "大特写"),
            alias("极特写", "极特写"), alias("细节镜头", "细节镜头"));
    private static final Map<String, String> CAMERA_ANGLE_ALIASES = aliasMap(
            alias("平视", "平视"), alias("客观平视", "平视"),
            alias("俯拍", "高角度"), alias("俯视", "高角度"), alias("高角度", "高角度"),
            alias("仰拍", "低角度"), alias("仰视", "低角度"), alias("低角度", "低角度"),
            alias("鸟瞰", "鸟瞰"), alias("上帝视角", "鸟瞰"),
            alias("虫视角", "虫视角"), alias("虫视", "虫视角"),
            alias("荷兰角", "荷兰角"), alias("倾斜视角", "荷兰角"),
            alias("过肩", "过肩"), alias("过肩视角", "过肩"), alias("过肩镜头", "过肩"),
            alias("第一人称视角", "第一人称视角"), alias("主观视角", "第一人称视角"),
            alias("侧面视角", "侧面视角"), alias("侧面", "侧面视角"),
            alias("正面", "正面"), alias("四分之三正面", "四分之三正面"),
            alias("四分之三背面", "四分之三背面"), alias("背面", "背面"));
    private static final Map<String, String> CAMERA_MOVEMENT_ALIASES = aliasMap(
            alias("固定机位", "固定机位"), alias("固定镜头", "固定机位"), alias("静态镜头", "固定机位"),
            alias("跟拍", "跟拍"), alias("跟镜", "跟拍"), alias("跟镜头", "跟拍"), alias("跟随镜头", "跟拍"),
            alias("环绕", "环绕"), alias("环绕镜头", "环绕"),
            alias("360度环绕", "360度环绕"), alias("360°环绕", "360度环绕"),
            alias("变焦拉近", "变焦拉近"), alias("变焦推近", "变焦拉近"), alias("zoomin", "变焦拉近"),
            alias("变焦拉远", "变焦拉远"), alias("zoomout", "变焦拉远"),
            alias("镜头左摇", "镜头左摇"), alias("左摇", "镜头左摇"), alias("向左摇镜", "镜头左摇"),
            alias("镜头右摇", "镜头右摇"), alias("右摇", "镜头右摇"), alias("向右摇镜", "镜头右摇"),
            alias("镜头上仰", "镜头上仰"), alias("上摇", "镜头上仰"), alias("向上摇镜", "镜头上仰"),
            alias("镜头下俯", "镜头下俯"), alias("下摇", "镜头下俯"), alias("向下摇镜", "镜头下俯"),
            alias("前移", "前移"), alias("向前移镜", "前移"), alias("推镜", "前移"), alias("推镜头", "前移"),
            alias("后移", "后移"), alias("向后移镜", "后移"), alias("拉镜", "后移"), alias("拉镜头", "后移"),
            alias("左移", "左移"), alias("向左移镜", "左移"),
            alias("右移", "右移"), alias("向右移镜", "右移"),
            alias("摆臂上升", "摆臂上升"), alias("升镜", "摆臂上升"), alias("升镜头", "摆臂上升"),
            alias("摆臂下降", "摆臂下降"), alias("降镜", "摆臂下降"), alias("降镜头", "摆臂下降"),
            alias("无人机航拍", "无人机航拍"), alias("航拍", "无人机航拍"));
    private static final Set<String> FREEZE_DIRECTIVES = orderedSet("画面定格", "定格画面", "定格", "冻结画面");
    private static final Map<String, String> TRANSITION_ALIASES = aliasMap(
            alias("淡入", "淡入"), alias("fadein", "FADE IN"),
            alias("淡出", "淡出"), alias("fadeout", "FADE OUT"),
            alias("黑场", "黑场"), alias("白场", "白场"), alias("切黑", "切黑"),
            alias("闪白", "闪白"), alias("叠化", "叠化"), alias("溶解", "溶解"),
            alias("转场", "转场"), alias("cutto", "CUT TO"),
            alias("dissolveto", "DISSOLVE TO"));

    /**
     * 生成剧本文档覆盖计划。
     *
     * @param rawScript 原始剧本文档
     * @param targetChunkSize 目标批次字符数
     * @return 稳定、可重建的覆盖计划
     */
    public CoveragePlan plan(String rawScript, int targetChunkSize)
    {
        String normalizedSource = normalizeLineEndings(rawScript);
        int requestedTarget = targetChunkSize > 0 ? targetChunkSize : DEFAULT_TARGET_CHUNK_SIZE;
        int targetSize = Math.min(requestedTarget, ABSOLUTE_HARD_CHUNK_SIZE);
        int hardSize = Math.min(ABSOLUTE_HARD_CHUNK_SIZE,
                Math.max(targetSize, targetSize * 2));
        if (StrUtil.isBlank(normalizedSource))
        {
            return new CoveragePlan("", "", Collections.emptyList(), Collections.emptyList());
        }

        String[] lines = normalizedSource.split("\\n", -1);
        int bodyHeadingIndex = findBodyHeadingIndex(lines);
        String referenceContext = bodyHeadingIndex >= 0
                ? buildReferenceContext(lines, bodyHeadingIndex) : "";
        int contentStart = bodyHeadingIndex >= 0 ? bodyHeadingIndex + 1 : 0;

        List<NarrativeAtom> atoms = new ArrayList<>();
        List<PendingDirective> pendingDirectives = new ArrayList<>();
        List<PendingCue> pendingCues = new ArrayList<>();
        List<IgnoredSegment> ignoredSegments = new ArrayList<>();

        if (bodyHeadingIndex >= 0)
        {
            for (int lineIndex = 0; lineIndex <= bodyHeadingIndex; lineIndex++)
            {
                String sourceLine = lines[lineIndex];
                String semanticLine = normalizeMarkdownText(sourceLine);
                boolean explicitMarkdownHeading = MARKDOWN_HEADING.matcher(sourceLine).matches();
                if (!explicitMarkdownHeading && lineIndex != bodyHeadingIndex)
                {
                    continue;
                }
                StructureCueType cueType = classifyStructureCue(sourceLine, semanticLine);
                if (Objects.nonNull(cueType))
                {
                    pendingCues.add(new PendingCue(cueType, semanticLine,
                            sourceLine.trim(), 0));
                }
            }
        }

        for (int lineIndex = contentStart; lineIndex < lines.length; lineIndex++)
        {
            String sourceLine = lines[lineIndex];
            String semanticLine = normalizeMarkdownText(sourceLine);
            if (StrUtil.isBlank(semanticLine))
            {
                continue;
            }

            if (isPreviewLine(semanticLine))
            {
                ignoredSegments.add(new IgnoredSegment(IgnoredType.NEXT_PREVIEW, semanticLine, lineIndex));
                continue;
            }

            Matcher autoCaption = AUTO_CAPTION_LINE.matcher(semanticLine);
            if (autoCaption.matches())
            {
                appendNarrativeAtoms(atoms, semanticLine, targetSize, hardSize);
                continue;
            }

            Matcher screenText = SCREEN_TEXT_LINE.matcher(semanticLine);
            if (screenText.matches())
            {
                String payload = StrUtil.trim(screenText.group(2));
                if (StrUtil.isBlank(payload))
                {
                    int payloadIndex = nextNonBlankLineIndex(lines, lineIndex + 1);
                    if (payloadIndex >= 0)
                    {
                        String nextLine = normalizeMarkdownText(lines[payloadIndex]);
                        if (isPreviewLine(nextLine))
                        {
                            ignoredSegments.add(new IgnoredSegment(IgnoredType.NEXT_PREVIEW,
                                    semanticLine + "\n" + nextLine, lineIndex));
                            lineIndex = payloadIndex;
                            continue;
                        }
                        payload = nextLine;
                        semanticLine = semanticLine + "\n" + nextLine;
                        lineIndex = payloadIndex;
                    }
                }
                if (StrUtil.isNotBlank(payload))
                {
                    appendScreenTextNarrative(atoms, pendingDirectives,
                            semanticLine, targetSize, hardSize);
                    continue;
                }
            }

            DirectiveSeed standaloneTransition = classifyStandaloneTransition(semanticLine);
            if (Objects.nonNull(standaloneTransition))
            {
                pendingDirectives.add(new PendingDirective(standaloneTransition.type(),
                        standaloneTransition.value(), semanticLine, atoms.size(),
                        transitionBindsToPrevious(standaloneTransition.value())));
                continue;
            }

            StructureCueType cueType = classifyStructureCue(sourceLine, semanticLine);
            if (Objects.nonNull(cueType))
            {
                pendingCues.add(new PendingCue(cueType, semanticLine, sourceLine.trim(), atoms.size()));
                continue;
            }

            List<DirectiveSeed> directives = classifyBracketDirectives(semanticLine);
            if (!directives.isEmpty())
            {
                boolean carriesScreenText = directives.stream()
                        .anyMatch(directive -> directive.type() == DirectiveType.SCREEN_TEXT);
                if (carriesScreenText)
                {
                    if (directives.size() == 1)
                    {
                        appendScreenTextNarrative(atoms, pendingDirectives,
                                semanticLine, targetSize, hardSize);
                    }
                    else
                    {
                        int screenAtom = atoms.size();
                        appendNarrativeAtoms(atoms, semanticLine, targetSize, hardSize);
                        for (DirectiveSeed directive : directives)
                        {
                            if (directive.type() == DirectiveType.SCREEN_TEXT)
                            {
                                pendingDirectives.add(PendingDirective.onAtom(
                                        DirectiveType.SCREEN_TEXT, directive.value(),
                                        semanticLine, screenAtom));
                            }
                        }
                    }
                }
                for (DirectiveSeed directive : directives)
                {
                    if (directive.type() != DirectiveType.SCREEN_TEXT)
                    {
                        pendingDirectives.add(new PendingDirective(directive.type(), directive.value(),
                                semanticLine, atoms.size(), bindsToPrevious(directive)));
                    }
                }
                continue;
            }

            String narrativeUnit = semanticLine;
            if (isSpeakerCue(semanticLine))
            {
                int dialogueIndex = nextNonBlankLineIndex(lines, lineIndex + 1);
                if (dialogueIndex >= 0)
                {
                    String dialogueLine = normalizeMarkdownText(lines[dialogueIndex]);
                    String parenthetical = null;
                    if (DIALOGUE_PARENTHETICAL.matcher(dialogueLine).matches())
                    {
                        parenthetical = dialogueLine;
                        dialogueIndex = nextNonBlankLineIndex(lines, dialogueIndex + 1);
                        dialogueLine = dialogueIndex >= 0
                                ? normalizeMarkdownText(lines[dialogueIndex]) : "";
                    }
                    if (StrUtil.isNotBlank(dialogueLine)
                            && !isUnambiguousMetadataLine(lines[dialogueIndex], dialogueLine))
                    {
                        narrativeUnit = semanticLine + "\n"
                                + (StrUtil.isNotBlank(parenthetical) ? parenthetical + "\n" : "")
                                + dialogueLine;
                        lineIndex = dialogueIndex;
                    }
                }
            }
            appendNarrativeAtoms(atoms, narrativeUnit, targetSize, hardSize);
        }

        if (atoms.isEmpty())
        {
            return new CoveragePlan(referenceContext, "", Collections.emptyList(), ignoredSegments);
        }

        assignCanonicalOffsets(atoms);
        List<CoverageBatch> batches = buildBatches(atoms, pendingDirectives, pendingCues,
                referenceContext, targetSize, hardSize);
        String narrativeText = joinAtomTexts(atoms, 0, atoms.size());
        return new CoveragePlan(referenceContext, narrativeText, batches, ignoredSegments);
    }

    /**
     * 使用默认目标大小生成覆盖计划。
     */
    public CoveragePlan plan(String rawScript)
    {
        return plan(rawScript, DEFAULT_TARGET_CHUNK_SIZE);
    }

    /**
     * 生成不受空白与引号样式影响的严格覆盖文本。
     */
    public static String normalizeCoverageText(String value)
    {
        if (value == null)
        {
            return "";
        }
        String compact = normalizeLineEndings(value).replaceAll("\\s+", "");
        StringBuilder normalized = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++)
        {
            char ch = compact.charAt(i);
            if (ch == '"' || ch == '\'' || ch == '“' || ch == '”'
                    || ch == '‘' || ch == '’' || ch == '「' || ch == '」'
                    || ch == '『' || ch == '』')
            {
                normalized.append('"');
            }
            else
            {
                normalized.append(ch);
            }
        }
        return normalized.toString();
    }

    private int findBodyHeadingIndex(String[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            String heading = extractHeadingText(lines[i]);
            if (StrUtil.isNotBlank(heading) && BODY_HEADING.matcher(heading).matches())
            {
                return i;
            }
        }
        return -1;
    }

    private String buildReferenceContext(String[] lines, int bodyHeadingIndex)
    {
        List<String> context = new ArrayList<>();
        for (int i = 0; i < bodyHeadingIndex; i++)
        {
            String line = normalizeMarkdownText(lines[i]);
            if (StrUtil.isNotBlank(line))
            {
                context.add(line);
            }
        }
        return String.join("\n", context);
    }

    private StructureCueType classifyStructureCue(String sourceLine, String semanticLine)
    {
        String heading = extractHeadingText(sourceLine);
        boolean markdownHeading = MARKDOWN_HEADING.matcher(
                StrUtil.blankToDefault(sourceLine, "")).matches();
        String candidate = StrUtil.blankToDefault(heading, semanticLine).trim();
        if (BODY_HEADING.matcher(candidate).matches())
        {
            return StructureCueType.BODY_SECTION;
        }
        if (TIME_SECTION_HEADING.matcher(candidate).matches())
        {
            return StructureCueType.TIME_SECTION;
        }
        if (ACT_SECTION_HEADING.matcher(candidate).matches())
        {
            return StructureCueType.ACT_SECTION;
        }
        if (SCREENPLAY_SCENE_HEADING.matcher(candidate).matches()
                || CHINESE_SCENE_SLUG.matcher(candidate).matches())
        {
            return StructureCueType.SCENE_HEADING;
        }
        if (markdownHeading && REFERENCE_HEADING.matcher(candidate).matches())
        {
            return StructureCueType.REFERENCE_SECTION;
        }
        if (markdownHeading)
        {
            return markdownHeadingLevel(sourceLine) == 1
                    ? StructureCueType.DOCUMENT_TITLE : StructureCueType.GENERIC_SECTION;
        }
        return null;
    }

    private int markdownHeadingLevel(String sourceLine)
    {
        String value = StrUtil.blankToDefault(sourceLine, "").stripLeading();
        int level = 0;
        while (level < value.length() && level < 6 && value.charAt(level) == '#')
        {
            level++;
        }
        return level;
    }

    private List<DirectiveSeed> classifyBracketDirectives(String semanticLine)
    {
        Matcher matcher = BRACKET_DIRECTIVE.matcher(semanticLine);
        if (!matcher.matches())
        {
            return Collections.emptyList();
        }
        String content = matcher.group(1).trim();
        if (StrUtil.isBlank(content))
        {
            return Collections.emptyList();
        }
        Matcher screenText = SCREEN_TEXT_LINE.matcher(content);
        if (screenText.matches() && StrUtil.isNotBlank(screenText.group(2)))
        {
            return Collections.singletonList(new DirectiveSeed(
                    DirectiveType.SCREEN_TEXT, screenText.group(2).trim()));
        }
        String[] tokens = content.split("[,，、;；]");
        List<DirectiveSeed> result = new ArrayList<>();
        for (String rawToken : tokens)
        {
            String token = stripDirectiveLabel(rawToken);
            DirectiveSeed seed = classifyDirectiveToken(token);
            if (Objects.isNull(seed))
            {
                return Collections.emptyList();
            }
            result.add(seed);
        }
        return result;
    }

    private DirectiveSeed classifyDirectiveToken(String token)
    {
        String normalized = normalizeDirectiveValue(token);
        String canonical = SHOT_SIZE_ALIASES.get(normalized);
        if (StrUtil.isNotBlank(canonical))
        {
            return new DirectiveSeed(DirectiveType.SHOT_SIZE, canonical);
        }
        canonical = CAMERA_ANGLE_ALIASES.get(normalized);
        if (StrUtil.isNotBlank(canonical))
        {
            return new DirectiveSeed(DirectiveType.CAMERA_ANGLE, canonical);
        }
        canonical = CAMERA_MOVEMENT_ALIASES.get(normalized);
        if (StrUtil.isNotBlank(canonical))
        {
            return new DirectiveSeed(DirectiveType.CAMERA_MOVEMENT, canonical);
        }
        if (FREEZE_DIRECTIVES.contains(normalized))
        {
            return new DirectiveSeed(DirectiveType.FREEZE_FRAME, normalized);
        }
        String transition = TRANSITION_ALIASES.get(stripTransitionPunctuation(normalized));
        if (StrUtil.isNotBlank(transition))
        {
            return new DirectiveSeed(DirectiveType.TRANSITION, transition);
        }
        Matcher screenText = SCREEN_TEXT_LINE.matcher(token);
        if (screenText.matches() && StrUtil.isNotBlank(screenText.group(2)))
        {
            return new DirectiveSeed(DirectiveType.SCREEN_TEXT, screenText.group(2).trim());
        }
        return null;
    }

    private DirectiveSeed classifyStandaloneTransition(String semanticLine)
    {
        String normalized = stripTransitionPunctuation(normalizeDirectiveValue(semanticLine));
        String canonical = TRANSITION_ALIASES.get(normalized);
        return StrUtil.isBlank(canonical)
                ? null : new DirectiveSeed(DirectiveType.TRANSITION, canonical);
    }

    private String stripTransitionPunctuation(String value)
    {
        return StrUtil.blankToDefault(value, "")
                .replaceFirst("[：:.。]+$", "");
    }

    private boolean bindsToPrevious(DirectiveSeed directive)
    {
        return directive.type() == DirectiveType.TRANSITION
                ? transitionBindsToPrevious(directive.value())
                : directive.type().bindsToPrevious();
    }

    private boolean transitionBindsToPrevious(String canonical)
    {
        return !Objects.equals("淡入", canonical)
                && !Objects.equals("FADE IN", canonical);
    }

    private String stripDirectiveLabel(String value)
    {
        String token = StrUtil.trim(value);
        return token.replaceFirst("^(?:景别|镜头景别|视角|拍摄角度|镜头运动|运镜)\\s*[：:]\\s*", "");
    }

    private String normalizeDirectiveValue(String value)
    {
        return Normalizer.normalize(StrUtil.blankToDefault(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private void appendNarrativeAtoms(List<NarrativeAtom> atoms, String text, int targetSize, int hardSize)
    {
        List<String> parts = splitNarrativeUnit(text, targetSize, hardSize);
        for (String part : parts)
        {
            if (StrUtil.isNotBlank(part))
            {
                atoms.add(new NarrativeAtom(part));
            }
        }
    }

    private void appendScreenTextNarrative(List<NarrativeAtom> atoms,
                                           List<PendingDirective> pendingDirectives,
                                           String text, int targetSize, int hardSize)
    {
        int firstAtom = atoms.size();
        appendNarrativeAtoms(atoms, text, targetSize, hardSize);
        for (int atomIndex = firstAtom; atomIndex < atoms.size(); atomIndex++)
        {
            String payloadPart = extractScreenTextPayloadPart(
                    atoms.get(atomIndex).text, atomIndex == firstAtom);
            if (StrUtil.isNotBlank(payloadPart))
            {
                pendingDirectives.add(PendingDirective.onAtom(DirectiveType.SCREEN_TEXT,
                        payloadPart, atoms.get(atomIndex).text, atomIndex));
            }
        }
    }

    private String extractScreenTextPayloadPart(String atomText, boolean firstPart)
    {
        String value = StrUtil.trim(atomText);
        if (firstPart)
        {
            value = value.replaceFirst("^[【\\[]\\s*", "")
                    .replaceFirst("^(?:字幕|画面文字|屏幕文字|屏显|字卡|标题卡|系统提示|系统文字|系统UI|UI文字|地点字幕|时间字幕)"
                            + "\\s*[：:]\\s*", "");
        }
        return value.replaceFirst("\\s*[】\\]]$", "").trim();
    }

    private List<String> splitNarrativeUnit(String value, int targetSize, int hardSize)
    {
        String text = StrUtil.trim(value);
        if (text.length() <= targetSize)
        {
            return Collections.singletonList(text);
        }
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (offset < text.length())
        {
            int remaining = text.length() - offset;
            if (remaining <= targetSize)
            {
                parts.add(text.substring(offset).trim());
                break;
            }
            int preferredEnd = Math.min(text.length(), offset + targetSize);
            int hardEnd = Math.min(text.length(), offset + hardSize);
            int boundary = findSafeBoundary(text, offset, preferredEnd);
            if (boundary <= offset)
            {
                boundary = findSafeBoundary(text, offset, hardEnd);
            }
            if (boundary <= offset)
            {
                boundary = hardEnd;
            }
            parts.add(text.substring(offset, boundary).trim());
            offset = boundary;
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset)))
            {
                offset++;
            }
        }
        return parts;
    }

    private int findSafeBoundary(String text, int start, int end)
    {
        int lowerBound = Math.min(end, start + Math.max(1, (end - start) / 2));
        for (int i = Math.min(end, text.length()) - 1; i >= lowerBound; i--)
        {
            char ch = text.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?'
                    || ch == '；' || ch == ';')
            {
                return i + 1;
            }
        }
        for (int i = Math.min(end, text.length()) - 1; i >= lowerBound; i--)
        {
            char ch = text.charAt(i);
            if (ch == '，' || ch == ',' || ch == '、' || ch == '：' || ch == ':')
            {
                return i + 1;
            }
        }
        return -1;
    }

    private void assignCanonicalOffsets(List<NarrativeAtom> atoms)
    {
        int offset = 0;
        for (NarrativeAtom atom : atoms)
        {
            atom.canonicalText = normalizeCoverageText(atom.text);
            atom.canonicalStart = offset;
            offset += atom.canonicalText.length();
            atom.canonicalEnd = offset;
        }
    }

    private List<CoverageBatch> buildBatches(List<NarrativeAtom> atoms,
                                              List<PendingDirective> pendingDirectives,
                                              List<PendingCue> pendingCues,
                                              String referenceContext,
                                              int targetSize,
                                              int hardSize)
    {
        List<AtomRange> ranges = new ArrayList<>();
        int start = 0;
        int length = 0;
        for (int i = 0; i < atoms.size(); i++)
        {
            int addition = atoms.get(i).text.length() + (i > start ? 1 : 0);
            if (i > start && length + addition > targetSize)
            {
                ranges.add(new AtomRange(start, i));
                start = i;
                length = atoms.get(i).text.length();
            }
            else
            {
                length += addition;
            }
            if (length > hardSize)
            {
                ranges.add(new AtomRange(start, i + 1));
                start = i + 1;
                length = 0;
            }
        }
        if (start < atoms.size())
        {
            ranges.add(new AtomRange(start, atoms.size()));
        }

        int[] atomToBatch = new int[atoms.size()];
        for (int batchIndex = 0; batchIndex < ranges.size(); batchIndex++)
        {
            AtomRange range = ranges.get(batchIndex);
            for (int atomIndex = range.start(); atomIndex < range.end(); atomIndex++)
            {
                atomToBatch[atomIndex] = batchIndex;
            }
        }

        List<List<VisualDirective>> directivesByBatch = emptyNestedList(ranges.size());
        for (PendingDirective pending : pendingDirectives)
        {
            int targetAtom = resolveTargetAtom(pending.position(), pending.bindsToPrevious(), atoms.size());
            if (targetAtom < 0)
            {
                continue;
            }
            int batchIndex = atomToBatch[targetAtom];
            AtomRange range = ranges.get(batchIndex);
            NarrativeAtom target = atoms.get(targetAtom);
            int batchCanonicalStart = atoms.get(range.start()).canonicalStart;
            int targetOffset = pending.bindsToPrevious()
                    ? Math.max(0, target.canonicalEnd - batchCanonicalStart - 1)
                    : Math.max(0, target.canonicalStart - batchCanonicalStart);
            directivesByBatch.get(batchIndex).add(new VisualDirective(
                    pending.type(), pending.value(), pending.rawText(),
                    anchorBefore(atoms, pending.position()), anchorAfter(atoms, pending.position()),
                    targetOffset, pending.bindsToPrevious()));
        }

        List<List<StructureCue>> cuesByBatch = emptyNestedList(ranges.size());
        for (PendingCue pending : pendingCues)
        {
            int targetAtom = resolveTargetAtom(pending.position(), false, atoms.size());
            if (targetAtom < 0)
            {
                continue;
            }
            int batchIndex = atomToBatch[targetAtom];
            AtomRange range = ranges.get(batchIndex);
            int batchCanonicalStart = atoms.get(range.start()).canonicalStart;
            int targetOffset = Math.max(0, atoms.get(targetAtom).canonicalStart - batchCanonicalStart);
            cuesByBatch.get(batchIndex).add(new StructureCue(
                    pending.type(), pending.value(), pending.rawText(),
                    anchorBefore(atoms, pending.position()), anchorAfter(atoms, pending.position()),
                    targetOffset));
        }

        List<CoverageBatch> batches = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < ranges.size(); batchIndex++)
        {
            AtomRange range = ranges.get(batchIndex);
            String narrative = joinAtomTexts(atoms, range.start(), range.end());
            batches.add(new CoverageBatch(batchIndex, narrative, referenceContext,
                    cuesByBatch.get(batchIndex), directivesByBatch.get(batchIndex), narrative.length()));
        }
        return Collections.unmodifiableList(batches);
    }

    private <T> List<List<T>> emptyNestedList(int size)
    {
        List<List<T>> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            result.add(new ArrayList<>());
        }
        return result;
    }

    private int resolveTargetAtom(int position, boolean bindsToPrevious, int atomCount)
    {
        if (atomCount <= 0)
        {
            return -1;
        }
        if (bindsToPrevious && position > 0)
        {
            return position - 1;
        }
        if (position < atomCount)
        {
            return position;
        }
        return position > 0 ? position - 1 : -1;
    }

    private String anchorBefore(List<NarrativeAtom> atoms, int position)
    {
        if (position <= 0 || atoms.isEmpty())
        {
            return "";
        }
        String text = atoms.get(Math.min(position - 1, atoms.size() - 1)).canonicalText;
        return text.substring(Math.max(0, text.length() - ANCHOR_LENGTH));
    }

    private String anchorAfter(List<NarrativeAtom> atoms, int position)
    {
        if (position < 0 || position >= atoms.size())
        {
            return "";
        }
        String text = atoms.get(position).canonicalText;
        return text.substring(0, Math.min(ANCHOR_LENGTH, text.length()));
    }

    private String joinAtomTexts(List<NarrativeAtom> atoms, int start, int end)
    {
        List<String> values = new ArrayList<>();
        for (int i = start; i < end; i++)
        {
            values.add(atoms.get(i).text);
        }
        return String.join("\n", values);
    }

    private boolean isUnambiguousMetadataLine(String sourceLine, String semanticLine)
    {
        return isPreviewLine(semanticLine)
                || Objects.nonNull(classifyStructureCue(sourceLine, semanticLine))
                || !classifyBracketDirectives(semanticLine).isEmpty()
                || SCREEN_TEXT_LINE.matcher(semanticLine).matches()
                || AUTO_CAPTION_LINE.matcher(semanticLine).matches();
    }

    private boolean isSpeakerCue(String semanticLine)
    {
        return LABELED_SPEAKER_CUE.matcher(semanticLine).matches()
                || STANDALONE_SPEAKER_CUE.matcher(semanticLine).matches();
    }

    private boolean isPreviewLine(String semanticLine)
    {
        return PREVIEW_LINE.matcher(semanticLine).matches()
                || PREVIEW_WITH_SCREEN_LABEL.matcher(semanticLine).matches();
    }

    private int nextNonBlankLineIndex(String[] lines, int start)
    {
        for (int i = start; i < lines.length; i++)
        {
            if (StrUtil.isNotBlank(normalizeMarkdownText(lines[i])))
            {
                return i;
            }
        }
        return -1;
    }

    private String extractHeadingText(String value)
    {
        String normalized = normalizeMarkdownText(value);
        Matcher markdown = MARKDOWN_HEADING.matcher(StrUtil.blankToDefault(value, ""));
        if (markdown.matches())
        {
            return normalizeMarkdownText(markdown.group(1));
        }
        Matcher bracket = BRACKET_DIRECTIVE.matcher(normalized);
        if (bracket.matches())
        {
            String inner = normalizeMarkdownText(bracket.group(1));
            if (BODY_HEADING.matcher(inner).matches())
            {
                return inner;
            }
        }
        String plain = normalized.replaceFirst("[：:]$", "").trim();
        return BODY_HEADING.matcher(plain).matches() ? plain : normalized;
    }

    private String normalizeMarkdownText(String value)
    {
        String text = StrUtil.blankToDefault(value, "").trim();
        Matcher heading = MARKDOWN_HEADING.matcher(text);
        if (heading.matches())
        {
            text = heading.group(1).trim();
        }
        text = text.replaceFirst("^(?:[-+*]|\\d+[.)、])\\s+", "")
                .replaceFirst("^>\\s?", "");
        String previous;
        do
        {
            previous = text;
            text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                    .replaceAll("__(.+?)__", "$1")
                    .replaceAll("~~(.+?)~~", "$1")
                    .replaceAll("`([^`]+)`", "$1");
        }
        while (!Objects.equals(previous, text));
        return text.trim();
    }

    private static String normalizeLineEndings(String value)
    {
        return StrUtil.blankToDefault(value, "").replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Set<String> orderedSet(String... values)
    {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    @SafeVarargs
    private static Map<String, String> aliasMap(Map.Entry<String, String>... entries)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries)
        {
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map.Entry<String, String> alias(String source, String canonical)
    {
        return Map.entry(source, canonical);
    }

    private static final class NarrativeAtom
    {
        private final String text;
        private String canonicalText;
        private int canonicalStart;
        private int canonicalEnd;

        private NarrativeAtom(String text)
        {
            this.text = text;
        }
    }

    private record AtomRange(int start, int end) { }

    private record DirectiveSeed(DirectiveType type, String value) { }

    private record PendingDirective(DirectiveType type, String value, String rawText,
                                    int position, boolean bindsToPrevious)
    {
        private static PendingDirective onAtom(DirectiveType type, String value,
                                               String rawText, int atomIndex)
        {
            return new PendingDirective(type, value, rawText, atomIndex, false);
        }
    }

    private record PendingCue(StructureCueType type, String value, String rawText, int position) { }

    public enum DirectiveType
    {
        SHOT_SIZE(false),
        CAMERA_ANGLE(false),
        CAMERA_MOVEMENT(false),
        FREEZE_FRAME(true),
        TRANSITION(true),
        SCREEN_TEXT(false);

        private final boolean bindsToPrevious;

        DirectiveType(boolean bindsToPrevious)
        {
            this.bindsToPrevious = bindsToPrevious;
        }

        public boolean bindsToPrevious()
        {
            return bindsToPrevious;
        }
    }

    public enum StructureCueType
    {
        BODY_SECTION,
        ACT_SECTION,
        TIME_SECTION,
        SCENE_HEADING,
        REFERENCE_SECTION,
        DOCUMENT_TITLE,
        GENERIC_SECTION
    }

    public enum IgnoredType
    {
        NEXT_PREVIEW
    }

    public record VisualDirective(DirectiveType type, String value, String rawText,
                                  String anchorBefore, String anchorAfter, int targetOffset,
                                  boolean bindsToPrevious)
    {
        public String promptLine()
        {
            return type.name() + "：" + value + "（锚点前：" + anchorBefore
                    + "；锚点后：" + anchorAfter + "；目标："
                    + (bindsToPrevious ? "前锚点对应镜头" : "后锚点对应镜头") + "）";
        }
    }

    public record StructureCue(StructureCueType type, String value, String rawText,
                               String anchorBefore, String anchorAfter, int targetOffset)
    {
        public String promptLine()
        {
            return type.name() + "：" + value + "（锚点前：" + anchorBefore
                    + "；锚点后：" + anchorAfter + "）";
        }
    }

    public record IgnoredSegment(IgnoredType type, String text, int sourceLine) { }

    public record CoverageBatch(int batchIndex, String narrativeText, String referenceContext,
                                List<StructureCue> structureCues,
                                List<VisualDirective> directives, int charCount)
    {
        public CoverageBatch
        {
            structureCues = List.copyOf(structureCues);
            directives = List.copyOf(directives);
        }

        public String structureContext()
        {
            return structureCues.stream().map(StructureCue::promptLine)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
        }

        public String directiveContext()
        {
            return directives.stream().map(VisualDirective::promptLine)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
        }

        public String signatureMaterial()
        {
            return batchIndex + "\n" + narrativeText + "\n" + structureContext()
                    + "\n" + directiveContext();
        }
    }

    public record CoveragePlan(String referenceContext, String narrativeText,
                               List<CoverageBatch> batches,
                               List<IgnoredSegment> ignoredSegments)
    {
        public CoveragePlan
        {
            batches = List.copyOf(batches);
            ignoredSegments = List.copyOf(ignoredSegments);
        }

        public String signatureMaterial()
        {
            StringBuilder signature = new StringBuilder(PLAN_VERSION).append('\n')
                    .append(referenceContext).append('\n').append(narrativeText);
            for (CoverageBatch batch : batches)
            {
                signature.append("\n---batch---\n").append(batch.signatureMaterial());
            }
            return signature.toString();
        }
    }
}
