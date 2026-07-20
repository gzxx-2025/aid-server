package com.aid.voice.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 音色库常量（硬编码枚举目录）
 * 仅用于 {@link com.aid.aid.domain.AidAiVoiceLibrary} 的 language / gender / age_range
 * 等稳定枚举值。
 * 本类不依赖任何动态配置源（不读取 aid_config、不读取缓存），声明后对外只读。
 *
 * @author 视觉AID
 */
public final class VoiceLibraryConstants
{
    private VoiceLibraryConstants() {}
    public static final String TAG_TYPE_CHARACTER = "character_type";
    public static final String TAG_TYPE_VOICE_STYLE = "voice_style";
    public static final String TAG_TYPE_TONE = "tone";

    /** 标签字典允许的 tag_type 取值集合（大小写精确匹配） */
    public static final List<String> TAG_TYPES = Collections.unmodifiableList(
            Arrays.asList(TAG_TYPE_CHARACTER, TAG_TYPE_VOICE_STYLE, TAG_TYPE_TONE));
    public static final String LANG_ZH_CN = "zh-CN";
    public static final String LANG_EN_US = "en-US";
    public static final String LANG_JA_JP = "ja-JP";

    /** 语言允许集合（大小写精确匹配） */
    public static final List<String> LANGUAGES = Collections.unmodifiableList(
            Arrays.asList(LANG_ZH_CN, LANG_EN_US, LANG_JA_JP));

    /** 语言显示名（按声明顺序稳定） */
    public static final Map<String, String> LANGUAGE_LABELS;
    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(LANG_ZH_CN, "中文");
        m.put(LANG_EN_US, "英文");
        m.put(LANG_JA_JP, "日文");
        LANGUAGE_LABELS = Collections.unmodifiableMap(m);
    }
    public static final String GENDER_FEMALE = "female";
    public static final String GENDER_MALE = "male";
    public static final String GENDER_NEUTRAL = "neutral";

    public static final List<String> GENDERS = Collections.unmodifiableList(
            Arrays.asList(GENDER_FEMALE, GENDER_MALE, GENDER_NEUTRAL));

    public static final Map<String, String> GENDER_LABELS;
    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(GENDER_FEMALE, "女");
        m.put(GENDER_MALE, "男");
        m.put(GENDER_NEUTRAL, "中性");
        GENDER_LABELS = Collections.unmodifiableMap(m);
    }
    public static final String AGE_CHILD = "child";
    public static final String AGE_TEEN = "teen";
    public static final String AGE_YOUNG = "young";
    public static final String AGE_ADULT = "adult";
    public static final String AGE_MIDDLE = "middle";
    public static final String AGE_ELDERLY = "elderly";

    public static final List<String> AGE_RANGES = Collections.unmodifiableList(
            Arrays.asList(AGE_CHILD, AGE_TEEN, AGE_YOUNG, AGE_ADULT, AGE_MIDDLE, AGE_ELDERLY));

    public static final Map<String, String> AGE_RANGE_LABELS;
    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AGE_CHILD, "儿童");
        m.put(AGE_TEEN, "少年");
        m.put(AGE_YOUNG, "青年");
        m.put(AGE_ADULT, "成年");
        m.put(AGE_MIDDLE, "中年");
        m.put(AGE_ELDERLY, "老年");
        AGE_RANGE_LABELS = Collections.unmodifiableMap(m);
    }
    public static final String AUDIO_FORMAT_MP3 = "mp3";
    public static final String AUDIO_FORMAT_WAV = "wav";
    public static final String AUDIO_FORMAT_PCM = "pcm";

    public static final List<String> AUDIO_FORMATS = Collections.unmodifiableList(
            Arrays.asList(AUDIO_FORMAT_MP3, AUDIO_FORMAT_WAV, AUDIO_FORMAT_PCM));

    public static final List<Integer> SAMPLE_RATES = Collections.unmodifiableList(
            Arrays.asList(16000, 24000, 48000));
    /** 默认语速下限 */
    public static final java.math.BigDecimal SPEED_MIN = new java.math.BigDecimal("0.5");
    /** 默认语速上限 */
    public static final java.math.BigDecimal SPEED_MAX = new java.math.BigDecimal("2.0");
    /** 默认音调下限 */
    public static final java.math.BigDecimal PITCH_MIN = new java.math.BigDecimal("-12");
    /** 默认音调上限 */
    public static final java.math.BigDecimal PITCH_MAX = new java.math.BigDecimal("12");

    /** 单个标签数组元素最大数量 */
    public static final int TAG_ARRAY_MAX_SIZE = 20;

    /** 单个标签元素最大长度 */
    public static final int TAG_ELEMENT_MAX_LEN = 64;

    /** voice_name 最大长度 */
    public static final int VOICE_NAME_MAX_LEN = 100;

    /** URL 字段最大长度 */
    public static final int URL_MAX_LEN = 500;

    /** voice_code 最大长度 */
    public static final int VOICE_CODE_MAX_LEN = 128;

    /** 批量删除最大数量 */
    public static final int DELETE_BATCH_MAX = 100;

    /** 分页 pageSize 最大值 */
    public static final int PAGE_SIZE_MAX = 100;

    /** 分页 pageSize 最小值 */
    public static final int PAGE_SIZE_MIN = 1;
}
