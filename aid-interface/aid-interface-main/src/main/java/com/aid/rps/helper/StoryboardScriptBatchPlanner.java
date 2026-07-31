package com.aid.rps.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidScenePlot;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜脚本批次切片器（按场次拆批：一个 plot = 一批，batchIndex 为全局时序序号）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardScriptBatchPlanner
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 字段合并间隔符（多 plot 内容拼接） */
    private static final String MERGE_SEPARATOR = "\n\n";

    /**
     * 按场次拆批：一个 plot（场次）= 一批，batchIndex 为全局时序序号（0 起）。
     *
     * @param plots       已按全局 scene_code（场次时序）升序排列的剧情节拍列表
     * @param sceneIndex  scene_id → AidRolePropScene 索引（用于回填 sceneName 等元信息）
     * @return 批次清单；每个 BatchPlanItem 对应一个场次、一次 LLM 调用，batchIndex 全局连续
     */
    public List<BatchPlanItem> planBatches(List<AidScenePlot> plots, Map<Long, AidRolePropScene> sceneIndex)
    {
        List<BatchPlanItem> result = new ArrayList<>();
        if (CollectionUtil.isEmpty(plots))
        {
            log.info("StoryboardScriptBatchPlanner 拆批输入为空");
            return result;
        }

        // 一个场次一批；batchIndex = 全局时序序号（plots 已按 scene_code 升序），从 0 起
        int globalIndex = 0;
        for (AidScenePlot p : plots)
        {
            if (Objects.isNull(p))
            {
                continue;
            }
            BatchPlanItem item = newBatch(p, sceneIndex);
            item.appendPlot(p);
            item.setBatchIndex(globalIndex);
            // 兼容字段：shotCodes = sceneCodes（单场次即单元素，如 ["020"]）
            item.setShotCodes(new ArrayList<>(item.getSceneCodes()));
            result.add(item);
            globalIndex++;
        }

        log.info("StoryboardScriptBatchPlanner 拆批完成（按场次）: 输入plot={}, 输出batch={}（一场次一批）",
                plots.size(), result.size());
        return result;
    }

    /**
     * 新建一个批次空容器，回填 scene 元信息。
     */
    private BatchPlanItem newBatch(AidScenePlot p, Map<Long, AidRolePropScene> sceneIndex)
    {
        BatchPlanItem item = new BatchPlanItem();
        item.setSceneId(p.getSceneId());
        AidRolePropScene scene = (sceneIndex == null) ? null : sceneIndex.get(p.getSceneId());
        if (Objects.nonNull(scene))
        {
            item.setSceneName(scene.getName());
        }
        return item;
    }

    /** 解析可能是 JSON 数组字符串的字段为 List<String>；解析失败按整体字符串塞回 */
    private static List<String> parseJsonArrayOrFallback(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return new ArrayList<>();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("["))
        {
            try
            {
                List<String> parsed = OBJECT_MAPPER.readValue(trimmed,
                        new TypeReference<List<String>>() { });
                if (CollectionUtil.isNotEmpty(parsed))
                {
                    return parsed;
                }
            }
            catch (Exception ignore)
            {
                // 解析失败兜底：作为整体字符串塞入
            }
        }
        List<String> fallback = new ArrayList<>(1);
        fallback.add(trimmed);
        return fallback;
    }

    /**
     * 单个批次的执行计划。
     */
    @Data
    public static class BatchPlanItem
    {
        /** 所属 scene id（同批内唯一） */
        private Long sceneId;

        /** scene 名（用于日志展示） */
        private String sceneName;

        /** 全局时序批次序号（从 0 起，按 scene_code 升序），即该场次在全片中的顺序位 */
        private Integer batchIndex;

        /** 该批包含的 plot.id 列表（顺序与遍历一致） */
        private List<Long> plotIds = new ArrayList<>();

        /** 该批包含的 sceneCode 列表（与 plotIds 一一对应） */
        private List<String> sceneCodes = new ArrayList<>();

        /** 兼容字段：保留给 aid_storyboard_batch.shotCodes 等调用点（与 sceneCodes 同值） */
        private List<String> shotCodes = new ArrayList<>();

        /** 该批合并后总字数（用于 estimateBatchCost 按字数 / token 估算预冻结金额） */
        private int charCount;

        /** 合并字段：plotContent 多段用空行连接 */
        private String mergedPlotContent;

        /** 合并字段：characters 去重合并（每个 plot 的 characters 是 JSON 数组字符串） */
        private List<String> mergedCharacters = new ArrayList<>();

        /** 合并字段：characterActions 拼接非空 */
        private String mergedCharacterActions;

        /** 合并字段：characterStates 拼接非空 */
        private String mergedCharacterStates;

        /** 合并字段：keyDialogues 去重合并 */
        private List<String> mergedKeyDialogues = new ArrayList<>();

        /** 合并字段：sceneFunction 拼接非空 */
        private String mergedSceneFunction;

        /** 合并字段：timeOfDay 取首个非空 */
        private String mergedTimeOfDay;

        /** 合并字段：eraCoordinate 取首个非空 */
        private String mergedEraCoordinate;

        /** 合并字段：dateCoordinate 取首个非空 */
        private String mergedDateCoordinate;

        /** 合并字段：weather 取首个非空 */
        private String mergedWeather;

        // ---- 镜头组拆分计划字段（仅 pro/auto_grid 模式下使用）----

        /** 关联的镜头组拆分计划ID（非空表示当前批次由镜头组拆分产生） */
        private Long shotGroupPlanId;

        /** 来源场次编码（镜头组拆分时记录，用于展示编码） */
        private String sourceSceneCode;

        /** 场次内镜头组编码，如 001 */
        private String groupCode;

        /** 场次内镜头组序号 */
        private Integer groupIndex;

        /** 上一组承接摘要 */
        private String groupPreviousSummary;

        /** 下一组承接摘要 */
        private String groupNextSummary;

        /**
         * 把一个 plot 追加到当前批，更新所有合并字段。
         */
        public void appendPlot(AidScenePlot p)
        {
            if (Objects.isNull(p))
            {
                return;
            }
            plotIds.add(p.getId());
            sceneCodes.add(StrUtil.nullToEmpty(p.getSceneCode()));
            charCount += StrUtil.length(p.getPlotContent());

            mergedPlotContent = joinWithNewline(mergedPlotContent, p.getPlotContent());
            mergedCharacterActions = joinWithNewline(mergedCharacterActions, p.getCharacterActions());
            mergedCharacterStates = joinWithNewline(mergedCharacterStates, p.getCharacterStates());
            mergedSceneFunction = joinWithNewline(mergedSceneFunction, p.getSceneFunction());

            mergeUniqueInto(mergedCharacters, parseJsonArrayOrFallback(p.getCharacters()));
            mergeUniqueInto(mergedKeyDialogues, parseJsonArrayOrFallback(p.getKeyDialogues()));

            mergedTimeOfDay = firstNonBlank(mergedTimeOfDay, p.getTimeOfDay());
            mergedEraCoordinate = firstNonBlank(mergedEraCoordinate, p.getEraCoordinate());
            mergedDateCoordinate = firstNonBlank(mergedDateCoordinate, p.getDateCoordinate());
            mergedWeather = firstNonBlank(mergedWeather, p.getWeather());
        }

        /** 用 \n\n 连接两个非空字符串；任一空则返回另一方。 */
        private static String joinWithNewline(String oldVal, String add)
        {
            if (StrUtil.isBlank(add))
            {
                return oldVal;
            }
            if (StrUtil.isBlank(oldVal))
            {
                return add;
            }
            return oldVal + MERGE_SEPARATOR + add;
        }

        /** 取首个非空（保留最早出现的值，不被后续 plot 覆盖）。 */
        private static String firstNonBlank(String oldVal, String add)
        {
            if (StrUtil.isNotBlank(oldVal))
            {
                return oldVal;
            }
            return StrUtil.isBlank(add) ? null : add;
        }

        /** 把 add 列表去重合并到 target 列表（保留 target 既有顺序，新元素追加在末尾）。 */
        private static void mergeUniqueInto(List<String> target, List<String> add)
        {
            if (CollectionUtil.isEmpty(add))
            {
                return;
            }
            for (String item : add)
            {
                if (StrUtil.isBlank(item))
                {
                    continue;
                }
                String trimmed = item.trim();
                if (!target.contains(trimmed))
                {
                    target.add(trimmed);
                }
            }
        }
    }
}
