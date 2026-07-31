package com.aid.rps.helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboardShotGroupPlan;
import com.aid.common.exception.ServiceException;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜镜头组拆分计划解析器（严格解析，不做 JSON 自动修复、不截取、不剥代码块）。
 *
 * 原文保真校验采用「折叠归一化」口径：除空白/不可见字符外，把 LLM 常见的标点漂移
 * （如全角【】被模型输出为半角[]）折叠为同一字符后再匹配；匹配成功后用原文对应区间
 * 回填 plotContent，保证落库与下游拿到的始终是原文，模型输出仅用于定位。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardShotGroupPlanParser
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 镜头组计划状态 */
    public static final String STATUS_PENDING = "PENDING";

    /** 不一致明细的分歧点上下文长度（归一化字符数） */
    private static final int DETAIL_CONTEXT_CHARS = 20;

    /** 不一致明细中引用文本的截断长度 */
    private static final int DETAIL_TEXT_LIMIT = 80;

    /** LLM 常见标点漂移折叠表：CJK 标点与 ASCII 视觉等价符号折叠为同一字符，仅用于匹配校验 */
    private static final Map<Character, Character> PUNCT_FOLD_MAP = buildPunctFoldMap();

    /**
     * 解析拆分 LLM 输出为镜头组计划列表（不落库，由调用方持久化）。
     * 严格校验：JSON 首尾必须 { }、groupIndex 从 1 连续、plotContent 来自原文、keyDialogues 来自原文。
     * plotContent 校验通过后会用原文对应区间回填，确保模型的字符漂移不进入下游。
     *
     * @param llmOutput 拆分 LLM 原始输出
     * @param taskId    分镜脚本批量任务ID
     * @param plot      来源场次剧情
     * @param userId    当前用户ID
     * @return 镜头组计划列表
     */
    public List<AidStoryboardShotGroupPlan> parse(String llmOutput, Long taskId, AidScenePlot plot, Long userId)
    {
        if (StrUtil.isBlank(llmOutput))
        {
            log.error("镜头组拆分输出为空: scenePlotId={}, sceneCode={}", plot.getId(), plot.getSceneCode());
            throw new ServiceException("拆分结果为空");
        }

        // 严格解析：首字符必须 {，尾字符必须 }，禁止代码块、禁止数组根、禁止前后多文本
        String trimmed = llmOutput.trim();
        if (trimmed.startsWith("```") || trimmed.startsWith("["))
        {
            log.error("镜头组拆分输出含代码块或数组根: scenePlotId={}, head={}",
                    plot.getId(), StrUtil.sub(trimmed, 0, 50));
            throw new ServiceException("拆分格式异常");
        }
        if (trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}')
        {
            log.error("镜头组拆分输出首尾不是大括号: scenePlotId={}, head={}, tail={}",
                    plot.getId(), StrUtil.sub(trimmed, 0, 30), StrUtil.sub(trimmed, -30, trimmed.length()));
            throw new ServiceException("拆分格式异常");
        }

        JsonNode root;
        try
        {
            root = OBJECT_MAPPER.readTree(trimmed);
        }
        catch (Exception e)
        {
            log.error("镜头组拆分JSON解析失败: scenePlotId={}, sceneCode={}, output={}",
                    plot.getId(), plot.getSceneCode(), StrUtil.sub(trimmed, 0, 200), e);
            throw new ServiceException("拆分格式异常");
        }

        if (root == null || !root.isObject())
        {
            log.error("镜头组拆分根节点不是object: scenePlotId={}, sceneCode={}", plot.getId(), plot.getSceneCode());
            throw new ServiceException("拆分格式异常");
        }
        if (root.size() != 1 || !root.has("shotGroups"))
        {
            log.error("镜头组拆分根节点字段异常: scenePlotId={}, sceneCode={}, fieldCount={}",
                    plot.getId(), plot.getSceneCode(), root.size());
            throw new ServiceException("拆分格式异常");
        }

        JsonNode shotGroupsNode = root.get("shotGroups");
        if (shotGroupsNode == null || !shotGroupsNode.isArray() || shotGroupsNode.isEmpty())
        {
            log.error("镜头组拆分缺少shotGroups数组: scenePlotId={}, sceneCode={}", plot.getId(), plot.getSceneCode());
            throw new ServiceException("拆分格式异常");
        }

        // 原文的折叠归一化视图（含 归一化字符 -> 原文区间 映射，用于校验与原文回填）
        String originalPlot = StrUtil.nullToEmpty(plot.getPlotContent());
        NormalizedText normOriginal = buildNormalizedText(originalPlot);
        // 原文 keyDialogues 集合（原样 + 折叠归一化两种口径，用于校验拆分台词来源）
        Set<String> originalDialogueSet = parseStringSet(plot.getKeyDialogues());
        List<String> originalDialogueList = parseStringList(plot.getKeyDialogues());
        Set<String> originalDialogueCanonSet = new HashSet<>();
        for (String dialogue : originalDialogueList)
        {
            if (StrUtil.isNotBlank(dialogue))
            {
                originalDialogueCanonSet.add(canonicalize(dialogue.trim()));
            }
        }

        List<AidStoryboardShotGroupPlan> plans = new ArrayList<>(shotGroupsNode.size());
        int expectedIndex = 1;
        // 归一化原文中的覆盖游标：每组必须从游标处开始，保证按原文顺序无缝覆盖
        int plotCursor = 0;
        StringBuilder combinedCanonPlot = new StringBuilder();
        Set<String> coveredDialogueSet = new HashSet<>();
        for (JsonNode groupNode : shotGroupsNode)
        {
            AidStoryboardShotGroupPlan plan = parseGroup(groupNode, taskId, plot, userId);

            // 校验 groupIndex 从 1 连续递增
            if (plan.getGroupIndex() != expectedIndex)
            {
                log.error("镜头组groupIndex不连续: scenePlotId={}, expected={}, actual={}, groupCode={}",
                        plot.getId(), expectedIndex, plan.getGroupIndex(), plan.getGroupCode());
                throw new ServiceException("拆分序号异常");
            }
            expectedIndex++;

            // 折叠归一化后校验 plotContent 来自原文，通过后用原文区间回填
            String normGroup = canonicalize(plan.getPlotContent());
            if (StrUtil.isNotEmpty(normOriginal.text) && StrUtil.isNotEmpty(normGroup))
            {
                // 先在全文范围判断"是否原文内容"，再判断"是否按原文顺序"
                if (!normOriginal.text.contains(normGroup))
                {
                    String detail = buildMismatchDetail(originalPlot, normOriginal, plotCursor,
                            normGroup, plan.getGroupCode());
                    log.error("镜头组plotContent不在原文中: scenePlotId={}, groupCode={}, groupPlotHead={}, detail={}",
                            plot.getId(), plan.getGroupCode(), StrUtil.sub(normGroup, 0, 60), detail);
                    throw new ServiceException("拆分内容异常").setDetailMessage(detail);
                }
                int foundIndex = normOriginal.text.indexOf(normGroup, plotCursor);
                if (foundIndex < 0)
                {
                    String detail = "镜头组 " + plan.getGroupCode()
                            + " 的 plotContent 在原文中的位置早于上一组结束位置，镜头组顺序必须与原文时间顺序一致。该组内容开头："
                            + StrUtil.sub(normGroup, 0, DETAIL_TEXT_LIMIT);
                    log.error("镜头组plotContent未按原文顺序覆盖: scenePlotId={}, groupCode={}, cursor={}, groupPlotHead={}",
                            plot.getId(), plan.getGroupCode(), plotCursor, StrUtil.sub(normGroup, 0, 60));
                    throw new ServiceException("拆分顺序异常").setDetailMessage(detail);
                }
                if (foundIndex > plotCursor)
                {
                    // 映射回原文，给出被跳过的原文片段
                    String skippedRaw = originalPlot.substring(
                            normOriginal.rawStart[plotCursor], normOriginal.rawStart[foundIndex]);
                    String detail = "镜头组 " + plan.getGroupCode() + " 之前有原文片段未被任何镜头组覆盖："
                            + StrUtil.sub(skippedRaw, 0, DETAIL_TEXT_LIMIT);
                    log.error("镜头组plotContent存在未覆盖原文: scenePlotId={}, groupCode={}, skippedHead={}",
                            plot.getId(), plan.getGroupCode(), StrUtil.sub(skippedRaw, 0, 60));
                    throw new ServiceException("拆分内容缺失").setDetailMessage(detail);
                }
                // 定位成功：用原文区间回填 plotContent，杜绝模型标点/字符漂移进入落库与下游
                int rawFrom = normOriginal.rawStart[foundIndex];
                int rawTo = normOriginal.rawEnd[foundIndex + normGroup.length() - 1];
                String backfilled = originalPlot.substring(rawFrom, rawTo);
                if (!Objects.equals(normalizeWhitespace(plan.getPlotContent()), normalizeWhitespace(backfilled)))
                {
                    // 仅靠标点折叠才匹配上的情况：说明模型输出发生了标点漂移（如【】→[]），记录便于观察模型质量
                    log.warn("镜头组plotContent存在标点漂移，已按原文回填: scenePlotId={}, groupCode={}, modelHead={}, originalHead={}",
                            plot.getId(), plan.getGroupCode(),
                            StrUtil.sub(plan.getPlotContent(), 0, 40), StrUtil.sub(backfilled, 0, 40));
                }
                plan.setPlotContent(backfilled);
                plotCursor = foundIndex + normGroup.length();
                combinedCanonPlot.append(normGroup);
            }

            // 校验 keyDialogues 来自原文 keyDialogues 或 plotContent（折叠归一化口径）
            List<String> groupDialogues = parseStringList(plan.getKeyDialoguesJson());
            if (CollectionUtil.isNotEmpty(groupDialogues))
            {
                for (String dialogue : groupDialogues)
                {
                    if (StrUtil.isBlank(dialogue))
                    {
                        continue;
                    }
                    String trimmedDialogue = dialogue.trim();
                    String canonDialogue = canonicalize(trimmedDialogue);
                    boolean fromOriginalDialogues = originalDialogueSet.contains(trimmedDialogue)
                            || originalDialogueCanonSet.contains(canonDialogue);
                    boolean fromPlotContent = StrUtil.isNotEmpty(normOriginal.text)
                            && StrUtil.isNotEmpty(canonDialogue)
                            && normOriginal.text.contains(canonDialogue);
                    if (!fromOriginalDialogues && !fromPlotContent)
                    {
                        String detail = "镜头组 " + plan.getGroupCode()
                                + " 的台词不在输入 keyDialogues 或 plotContent 原文中，禁止改写台词："
                                + StrUtil.sub(trimmedDialogue, 0, DETAIL_TEXT_LIMIT);
                        log.error("镜头组keyDialogues不在原文中: scenePlotId={}, groupCode={}, dialogue={}",
                                plot.getId(), plan.getGroupCode(), StrUtil.sub(trimmedDialogue, 0, 40));
                        throw new ServiceException("拆分台词异常").setDetailMessage(detail);
                    }
                    coveredDialogueSet.add(canonDialogue);
                }
            }

            plans.add(plan);
        }
        if (StrUtil.isNotEmpty(normOriginal.text) && plotCursor < normOriginal.text.length())
        {
            // 映射回原文，给出未覆盖的原文尾部
            String remainingRaw = originalPlot.substring(normOriginal.rawStart[plotCursor]);
            String detail = "原文结尾这一段未被任何镜头组覆盖："
                    + StrUtil.sub(remainingRaw, 0, DETAIL_TEXT_LIMIT);
            log.error("镜头组拆分未覆盖原文尾部: scenePlotId={}, sceneCode={}, remainingHead={}",
                    plot.getId(), plot.getSceneCode(), StrUtil.sub(remainingRaw, 0, 60));
            throw new ServiceException("拆分内容缺失").setDetailMessage(detail);
        }
        validateDialogueCoverage(plot, originalDialogueList, coveredDialogueSet, combinedCanonPlot.toString());

        log.info("镜头组拆分解析完成: scenePlotId={}, sceneCode={}, groupCount={}",
                plot.getId(), plot.getSceneCode(), plans.size());
        return plans;
    }

    /**
     * 校验原文关键台词均被镜头组覆盖（keyDialogues 字段或镜头组 plotContent 中出现均可）。
     *
     * @param combinedCanonPlot 全部镜头组 plotContent 的折叠归一化拼接
     */
    private void validateDialogueCoverage(AidScenePlot plot, List<String> originalDialogues,
            Set<String> coveredDialogueSet, String combinedCanonPlot)
    {
        if (CollectionUtil.isEmpty(originalDialogues))
        {
            return;
        }
        for (String dialogue : originalDialogues)
        {
            if (StrUtil.isBlank(dialogue))
            {
                continue;
            }
            String canonDialogue = canonicalize(dialogue.trim());
            if (StrUtil.isEmpty(canonDialogue))
            {
                continue;
            }
            boolean inDialogueField = coveredDialogueSet.contains(canonDialogue);
            boolean inGroupPlot = StrUtil.isNotEmpty(combinedCanonPlot)
                    && combinedCanonPlot.contains(canonDialogue);
            if (!inDialogueField && !inGroupPlot)
            {
                String detail = "原文关键台词未出现在任何镜头组的 keyDialogues 或 plotContent 中："
                        + StrUtil.sub(dialogue, 0, DETAIL_TEXT_LIMIT);
                log.error("镜头组拆分遗漏关键台词: scenePlotId={}, sceneCode={}, dialogue={}",
                        plot.getId(), plot.getSceneCode(), StrUtil.sub(dialogue, 0, 40));
                throw new ServiceException("拆分台词缺失").setDetailMessage(detail);
            }
        }
    }

    /**
     * 构建 plotContent 与原文的首个分歧点明细（写入异常 detailMessage 供纠偏重试反馈给模型，不面向用户）。
     */
    private String buildMismatchDetail(String originalPlot, NormalizedText normOriginal,
            int plotCursor, String normGroup, String groupCode)
    {
        // 与"当前应覆盖位置"逐字符对齐，找到首个分歧点
        int maxPrefix = Math.max(0, Math.min(normGroup.length(), normOriginal.text.length() - plotCursor));
        int prefix = 0;
        while (prefix < maxPrefix && normOriginal.text.charAt(plotCursor + prefix) == normGroup.charAt(prefix))
        {
            prefix++;
        }
        // 原文侧上下文：分歧点前后各取一段，映射回原文展示
        int divergeNormPos = plotCursor + prefix;
        String originalCtx;
        if (divergeNormPos < normOriginal.text.length())
        {
            int ctxFromNorm = Math.max(plotCursor, divergeNormPos - DETAIL_CONTEXT_CHARS);
            int ctxToNorm = Math.min(normOriginal.text.length() - 1, divergeNormPos + DETAIL_CONTEXT_CHARS);
            originalCtx = originalPlot.substring(
                    normOriginal.rawStart[ctxFromNorm], normOriginal.rawEnd[ctxToNorm]);
        }
        else
        {
            originalCtx = "(原文在此处已结束)";
        }
        // 输出侧上下文：分歧点前后各取一段模型输出（折叠归一化视图）
        String groupCtx = StrUtil.sub(normGroup,
                Math.max(0, prefix - DETAIL_CONTEXT_CHARS),
                Math.min(normGroup.length(), prefix + DETAIL_CONTEXT_CHARS));
        return "镜头组 " + groupCode + " 的 plotContent 与原文不一致。原文此处为「" + originalCtx
                + "」，你的输出对应位置为「" + groupCtx
                + "」。plotContent 必须逐字照搬输入原文，包括【】「」等标点，禁止替换、改写、增删任何字符。";
    }

    /**
     * 解析单个镜头组 JSON 节点为计划对象。
     */
    private AidStoryboardShotGroupPlan parseGroup(JsonNode groupNode, Long taskId, AidScenePlot plot, Long userId)
    {
        String groupCode = textOrDefault(groupNode, "groupCode", null);
        Integer groupIndex = intOrDefault(groupNode, "groupIndex", null);
        String plotContent = textOrDefault(groupNode, "plotContent", null);
        JsonNode charactersNode = groupNode.get("characters");
        JsonNode keyDialoguesNode = groupNode.get("keyDialogues");

        if (StrUtil.isBlank(groupCode))
        {
            log.error("镜头组缺少groupCode: scenePlotId={}", plot.getId());
            throw new ServiceException("拆分格式异常");
        }
        if (groupIndex == null)
        {
            log.error("镜头组缺少groupIndex: scenePlotId={}, groupCode={}", plot.getId(), groupCode);
            throw new ServiceException("拆分格式异常");
        }
        if (StrUtil.isBlank(plotContent))
        {
            log.error("镜头组缺少plotContent: scenePlotId={}, groupCode={}, fields={}, nodeHead={}",
                    plot.getId(), groupCode, fieldNames(groupNode), StrUtil.sub(groupNode.toString(), 0, 200));
            throw new ServiceException("拆分格式异常");
        }
        if (charactersNode == null || !charactersNode.isArray())
        {
            log.error("镜头组characters不是数组: scenePlotId={}, groupCode={}", plot.getId(), groupCode);
            throw new ServiceException("拆分格式异常");
        }
        if (keyDialoguesNode == null || !keyDialoguesNode.isArray())
        {
            log.error("镜头组keyDialogues不是数组: scenePlotId={}, groupCode={}", plot.getId(), groupCode);
            throw new ServiceException("拆分格式异常");
        }

        AidStoryboardShotGroupPlan plan = new AidStoryboardShotGroupPlan();
        plan.setTaskId(taskId);
        plan.setProjectId(plot.getProjectId());
        plan.setEpisodeId(plot.getEpisodeId());
        plan.setUserId(userId);
        plan.setScenePlotId(plot.getId());
        plan.setSceneId(plot.getSceneId());
        plan.setSourceSceneCode(plot.getSceneCode());
        plan.setGroupCode(groupCode);
        plan.setGroupIndex(groupIndex);
        plan.setDisplayCode(buildDisplayCode(plot.getSceneCode(), groupIndex));
        plan.setPlotContent(plotContent);
        plan.setCharactersJson(serializeArray(groupNode, "characters"));
        plan.setKeyDialoguesJson(serializeArray(groupNode, "keyDialogues"));
        plan.setSplitReason(textOrDefault(groupNode, "splitReason", null));
        plan.setPreviousSummary(textOrDefault(groupNode, "previousSummary", null));
        plan.setNextSummary(textOrDefault(groupNode, "nextSummary", null));
        plan.setStatus(STATUS_PENDING);
        plan.setDelFlag("0");
        return plan;
    }

    /** 计算展示编码，如 sceneCode=004, groupIndex=1 -> 4-1 */
    private String buildDisplayCode(String sceneCode, int groupIndex)
    {
        if (StrUtil.isBlank(sceneCode))
        {
            return null;
        }
        try
        {
            int sceneNum = Integer.parseInt(sceneCode.trim());
            return sceneNum + "-" + groupIndex;
        }
        catch (NumberFormatException e)
        {
            return sceneCode + "-" + groupIndex;
        }
    }

    /** 归一化字符 -> 原文区间 的映射视图，用于原文校验与回填 */
    private static final class NormalizedText
    {
        /** 折叠归一化后的文本 */
        private final String text;
        /** 每个归一化字符对应的原文起始下标 */
        private final int[] rawStart;
        /** 每个归一化字符对应的原文结束下标（不含） */
        private final int[] rawEnd;

        private NormalizedText(String text, int[] rawStart, int[] rawEnd)
        {
            this.text = text;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
        }
    }

    /** 构建原文的折叠归一化视图，并记录每个归一化字符对应的原文区间 */
    private static NormalizedText buildNormalizedText(String raw)
    {
        if (StrUtil.isEmpty(raw))
        {
            return new NormalizedText("", new int[0], new int[0]);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        List<Integer> startList = new ArrayList<>(raw.length());
        List<Integer> endList = new ArrayList<>(raw.length());
        int i = 0;
        while (i < raw.length())
        {
            int codePoint = raw.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            // 单码点归一化可能产出 0~N 个字符（空白丢弃、NFKC 展开），逐字符记录来源区间
            String normalized = canonicalizeCodePoint(codePoint);
            for (int k = 0; k < normalized.length(); k++)
            {
                sb.append(normalized.charAt(k));
                startList.add(i);
                endList.add(next);
            }
            i = next;
        }
        int[] rawStart = new int[startList.size()];
        int[] rawEnd = new int[endList.size()];
        for (int k = 0; k < rawStart.length; k++)
        {
            rawStart[k] = startList.get(k);
            rawEnd[k] = endList.get(k);
        }
        return new NormalizedText(sb.toString(), rawStart, rawEnd);
    }

    /** 折叠归一化：NFKC + 去不可见字符/空白 + 标点漂移折叠，仅用于匹配校验，不用于落库 */
    private static String canonicalize(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length())
        {
            int codePoint = text.codePointAt(i);
            sb.append(canonicalizeCodePoint(codePoint));
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    /** 单码点折叠归一化：不可见字符与空白折叠为空串，其余 NFKC 后再做标点折叠 */
    private static String canonicalizeCodePoint(int codePoint)
    {
        if (isInvisibleChar(codePoint) || isWhitespaceChar(codePoint))
        {
            return "";
        }
        String nfkc = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int k = 0; k < nfkc.length(); k++)
        {
            char c = nfkc.charAt(k);
            // NFKC 可能把全角空格等映射为普通空白，统一丢弃
            if (isInvisibleChar(c) || isWhitespaceChar(c))
            {
                continue;
            }
            Character folded = PUNCT_FOLD_MAP.get(c);
            out.append(folded == null ? c : folded.charValue());
        }
        return out.toString();
    }

    /** 是否零宽/不可见字符 */
    private static boolean isInvisibleChar(int codePoint)
    {
        return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                || codePoint == 0x2060 || codePoint == 0xFEFF;
    }

    /** 是否空白字符（含不换行空格） */
    private static boolean isWhitespaceChar(int codePoint)
    {
        return Character.isWhitespace(codePoint) || codePoint == 0x00A0;
    }

    /** 构建标点折叠表（NFKC 覆盖不到、但 LLM 经常互换的视觉等价标点） */
    private static Map<Character, Character> buildPunctFoldMap()
    {
        Map<Character, Character> map = new HashMap<>();
        // 方括号族：【〖〔〘〚 -> [，】〗〕〙〛 -> ]
        for (char c : "【〖〔〘〚".toCharArray())
        {
            map.put(c, '[');
        }
        for (char c : "】〗〕〙〛".toCharArray())
        {
            map.put(c, ']');
        }
        // 双引号族：「」『』与弯引号 -> "
        for (char c : "「」『』\u201C\u201D\u201E\u201F".toCharArray())
        {
            map.put(c, '"');
        }
        // 单引号族：弯单引号 -> '
        for (char c : "\u2018\u2019\u201A\u201B".toCharArray())
        {
            map.put(c, '\'');
        }
        // 书名号/尖括号族：《〈 -> <，》〉 -> >
        map.put('《', '<');
        map.put('〈', '<');
        map.put('》', '>');
        map.put('〉', '>');
        // 破折号/连字符族 -> -
        for (char c : "\u2014\u2013\u2015\u2010\u2011\u2012\u2212\u2500\u2501".toCharArray())
        {
            map.put(c, '-');
        }
        // 间隔号族：・(U+30FB) 等 -> ·(U+00B7)
        for (char c : "\u30FB\u2022\u2219\u22C5".toCharArray())
        {
            map.put(c, '·');
        }
        // 句读族：。-> .，、-> ,
        map.put('。', '.');
        map.put('、', ',');
        // 波浪号：〜(U+301C) -> ~
        map.put('\u301C', '~');
        return map;
    }

    /**
     * 严格归一化：仅清理不可见字符、全半角差异和空白差异，不折叠标点。
     * 用于识别"仅靠标点折叠才匹配上"的漂移场景，便于打点观察。
     */
    private static String normalizeWhitespace(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "");
        return normalized.replaceAll("[\\s\\u00A0]+", "");
    }

    /** 把 JSON 数组字符串解析为 Set<String>，用于台词来源校验 */
    private static Set<String> parseStringSet(String json)
    {
        Set<String> result = new HashSet<>();
        if (StrUtil.isBlank(json))
        {
            return result;
        }
        try
        {
            List<String> parsed = OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            if (CollectionUtil.isNotEmpty(parsed))
            {
                for (String s : parsed)
                {
                    if (StrUtil.isNotBlank(s))
                    {
                        result.add(s.trim());
                    }
                }
            }
        }
        catch (Exception ignore) { /* 原始台词格式异常时跳过校验 */ }
        return result;
    }

    /** 把 JSON 数组字符串解析为 List<String> */
    private static List<String> parseStringList(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return new ArrayList<>();
        }
        try
        {
            List<String> parsed = OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            return CollectionUtil.isNotEmpty(parsed) ? parsed : new ArrayList<>();
        }
        catch (Exception e)
        {
            return new ArrayList<>();
        }
    }

    /** 提取文本字段，空则返回默认值 */
    private String textOrDefault(JsonNode node, String field, String defaultValue)
    {
        JsonNode child = node.get(field);
        if (child == null || child.isNull())
        {
            return defaultValue;
        }
        String text = child.asText();
        return StrUtil.isBlank(text) ? defaultValue : text;
    }

    /** 提取整数字段，空则返回默认值 */
    private Integer intOrDefault(JsonNode node, String field, Integer defaultValue)
    {
        JsonNode child = node.get(field);
        if (child == null || child.isNull())
        {
            return defaultValue;
        }
        if (child.isNumber())
        {
            return child.asInt();
        }
        try
        {
            return Integer.parseInt(child.asText().trim());
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    /** 输出 JSON 节点字段名，便于定位模型把 plotContent 写错字段名或漏字段的问题。 */
    private List<String> fieldNames(JsonNode node)
    {
        List<String> fields = new ArrayList<>();
        if (node != null && node.isObject())
        {
            node.fieldNames().forEachRemaining(fields::add);
        }
        return fields;
    }

    /** 把 JSON 数组字段序列化为字符串，便于落库 TEXT 列 */
    private String serializeArray(JsonNode node, String field)
    {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isArray() || child.isEmpty())
        {
            return null;
        }
        try
        {
            return OBJECT_MAPPER.writeValueAsString(child);
        }
        catch (Exception e)
        {
            log.warn("镜头组数组字段序列化失败, fallback to raw: field={}", field);
            return child.toString();
        }
    }
}
