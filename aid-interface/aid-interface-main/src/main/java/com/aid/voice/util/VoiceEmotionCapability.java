package com.aid.voice.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 音色情感能力工具：<strong>以供应商声明为唯一标准</strong>，系统不再维护任何全局情感配置。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>能力来源：模型 {@code aid_ai_model.capability_json.emotions}（供应商官方文档声明的原生编码，
 *       如 MiniMax 的 happy/fearful/whisper、豆包的 happy/fear/coldness），由本工具统一解析；</li>
 *   <li>展示翻译：{@link #labelOf(String)} 仅做「供应商编码 → 中文显示名」的纯展示映射
 *       （静态词表，不承担任何"是否支持"判断）；未收录的编码原样返回，不阻断；</li>
 *   <li>下发口径：情感编码原样透传给 Provider（值本身就是供应商原生编码，无需二次映射）。</li>
 * </ul>
 *
 * <p>白名单语义（与配音链路一致）：{@code emotions} 缺失/为空 = 供应商未声明能力，不拦截；
 * 非空 = 仅白名单内的编码可用。</p>
 *
 * @author 视觉AID
 */
@Slf4j
public final class VoiceEmotionCapability
{
    private VoiceEmotionCapability()
    {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** capability_json 中的情感白名单字段名 */
    private static final String CAPABILITY_KEY_EMOTIONS = "emotions";

    /**
     * 供应商情感编码 → 中文显示名（纯展示层翻译，覆盖豆包 + MiniMax 官方文档全部编码）。
     * 未收录的编码 {@link #labelOf(String)} 原样返回，新供应商接入无需改这里也能工作。
     */
    private static final Map<String, String> EMOTION_LABELS = buildLabels();

    private static Map<String, String> buildLabels()
    {
        Map<String, String> m = new LinkedHashMap<>();
        // 通用 / MiniMax（speech-2.x 系列固定枚举）
        m.put("happy", "开心");
        m.put("sad", "悲伤");
        m.put("angry", "愤怒");
        m.put("fearful", "恐惧");
        m.put("disgusted", "厌恶");
        m.put("surprised", "惊讶");
        m.put("calm", "中性");
        m.put("fluent", "生动");
        m.put("whisper", "低语");
        // 豆包中文音色（大模型语音合成官方情感编码）
        m.put("fear", "恐惧");
        m.put("hate", "厌恶");
        m.put("excited", "激动");
        m.put("coldness", "冷漠");
        m.put("neutral", "中性");
        m.put("depressed", "沮丧");
        m.put("lovey-dovey", "撒娇");
        m.put("shy", "害羞");
        m.put("comfort", "安慰鼓励");
        m.put("tension", "咆哮/焦急");
        m.put("tender", "温柔");
        m.put("storytelling", "讲故事");
        m.put("radio", "情感电台");
        m.put("magnetic", "磁性");
        m.put("advertising", "广告营销");
        m.put("vocal-fry", "气泡音");
        m.put("ASMR", "低语(ASMR)");
        m.put("news", "新闻播报");
        m.put("entertainment", "娱乐八卦");
        m.put("dialect", "方言");
        // 豆包英文音色
        m.put("chat", "对话/闲聊");
        m.put("warm", "温暖");
        m.put("affectionate", "深情");
        m.put("authoritative", "权威");
        return m;
    }

    /**
     * 从模型 capability_json 解析供应商声明的情感白名单。
     * JSON 缺失 / 解析失败 / 非数组 → 返回空列表（视为"供应商未声明能力"，调用方不拦截）。
     *
     * @param capabilityJson 模型能力JSON
     * @return 供应商原生情感编码列表（保持声明顺序、去重）
     */
    public static List<String> parseModelEmotions(String capabilityJson)
    {
        List<String> list = new ArrayList<>();
        if (StrUtil.isBlank(capabilityJson))
        {
            return list;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(capabilityJson);
            JsonNode arr = root.get(CAPABILITY_KEY_EMOTIONS);
            if (Objects.nonNull(arr) && arr.isArray())
            {
                for (JsonNode n : arr)
                {
                    if (Objects.nonNull(n) && !n.isNull() && StrUtil.isNotBlank(n.asText()))
                    {
                        String code = n.asText().trim();
                        if (!list.contains(code))
                        {
                            list.add(code);
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("parseModelEmotions 解析失败: capabilityJson={}, err={}",
                    StrUtil.brief(capabilityJson, 80), e.getMessage());
        }
        return list;
    }

    /**
     * 供应商情感编码 → 中文显示名；未收录的编码原样返回（不阻断新供应商接入）。
     *
     * @param code 供应商原生情感编码
     * @return 中文显示名或原编码
     */
    public static String labelOf(String code)
    {
        if (StrUtil.isBlank(code))
        {
            return code;
        }
        return EMOTION_LABELS.getOrDefault(code.trim(), code.trim());
    }
}
