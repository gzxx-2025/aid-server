package com.aid.rps.vo;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 资产主表VO。
 *
 * @author 视觉AID
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RpsAssetVO {

    /** 主键 */
    private Long id;

    /** 资产类型: character / scene / prop */
    private String assetType;

    /** 创建来源: manual-手动创建, auto-自动提取 */
    private String createSource;

    /** 资产名称 */
    private String assetName;

    /**
     * 本次编辑是否触发角色音色自动重绑（改性别/年龄后即时重绑）；仅 character 且音色实际变化时为 true。
     * 前端据此弹出「部分镜头音色已变，是否重配」提示。
     */
    private Boolean voiceChanged;

    /** 音色重绑提示文案（{@link #voiceChanged} 为 true 时返回）。 */
    private String voiceChangedTip;
    /** 详细描述（角色介绍 / 场景视觉描述 / 道具视觉描述） */
    private String introduction;

    /** 简要说明（场景用途 / 道具简介；角色不返回） */
    private String summary;
    /** 别名(逗号分隔)，仅 character 返回 */
    private String aliasesName;

    /** 性别，仅 character 返回 */
    private String gender;

    /** 年龄段，仅 character 返回 */
    private String ageRange;

    /** 角色重要性层级 S/A/B/C/D，仅 character 返回 */
    private String roleLevel;

    /** 角色原型标签（如"智者"/"侠客"），仅 character 返回，解析自 profileData */
    private String archetype;

    /** 所处时代（如"民国"/"未来科幻"），仅 character 返回，解析自 profileData */
    private String eraPeriod;

    /** 职业，仅 character 返回，解析自 profileData */
    private String occupation;

    /** 服装等级，仅 character 返回，解析自 profileData */
    private Integer costumeTier;

    /** 社会阶层（如"中产"/"贵族"），仅 character 返回，解析自 profileData */
    private String socialClass;

    /** 视觉关键词列表，仅 character 返回，解析自 profileData */
    private List<String> visualKeywords;

    /** 性格标签列表，仅 character 返回，解析自 profileData */
    private List<String> personalityTags;

    /** 推荐色系列表，仅 character 返回，解析自 profileData */
    private List<String> suggestedColors;

    /** 主要识别特征，仅 character 返回，解析自 profileData */
    private String primaryIdentifier;

    /** 子形象列表（从 JSON 解析），仅 character 返回 */
    private List<Object> expectedAppearances;
    /** 角色可落位位置列表（从 JSON 解析），仅 scene 返回 */
    private List<String> availableSlots;

    /** 是否有人群: 0=无 1=有，仅 scene 返回 */
    private Integer hasCrowd;

    /** 人群类型描述，仅 scene 返回 */
    private String crowdDescription;
    /** 从表形态列表 */
    private List<RpsFormVO> forms;

    /**
     * 本次新建的形态图片主键（仅 /api/user/asset/rps/form-image/create 返回，其它接口为空）。
     * 对应 aid_role_prop_scene_form_image.id，前端用于后续调 /form/use、/form/unuse。
     */
    private Long imgId;

    /**
     * 音色绑定：仅 {@code assetType=character} 且已通过
     * {@code /api/user/asset/rps/voice/bind} 绑定时返回；其它情况字段为 null 不序列化。
     * 展示字段（voiceName / avatarUrl / sampleUrl / sampleText / language / gender / ageRange）
     * 直接来自 {@code aid_role_voice_binding} 冗余；能力字段（supports*）+ 默认参数 +
     * offlineTime 实时取自 {@code aid_ai_voice_library}。
     */
    private com.aid.rps.voice.vo.RoleVoiceBindingVO voiceBinding;
}
