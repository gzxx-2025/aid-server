package com.aid.storyboard.video;

/**
 * 分镜视频生成的单条已解析参考素材（由分镜提示词 {@code @图片N[name]} 占位解析并关联资产富化得到）。
 *
 * @author 视觉AID
 */
public class ResolvedReference
{
    /** 占位符里的原始序号 N（{@code @图片N}）。 */
    private final int originalN;

    /** 形态名（{@code @图片N[name]} 里的 name，对应 form_image.name）。 */
    private final String formName;

    /** 主资产名（aid_role_prop_scene.name，如"艾拉"/"林深"，与视频提示词正文里的角色名对齐）。 */
    private final String assetName;

    /** 资产类型：character 角色 / scene 场景 / prop 道具 / null 未知。 */
    private final String assetType;

    /** 是否角色多方位设定卡（source_type=ai_builder 或 name 以 _角色设定 结尾）。 */
    private final boolean settingCard;

    /** 完整可访问 URL（已经过 MediaUrlResolver 拼域名）。 */
    private final String url;

    public ResolvedReference(int originalN, String formName, String assetName,
                             String assetType, boolean settingCard, String url)
    {
        this.originalN = originalN;
        this.formName = formName;
        this.assetName = assetName;
        this.assetType = assetType;
        this.settingCard = settingCard;
        this.url = url;
    }

    public int getOriginalN()
    {
        return originalN;
    }

    public String getFormName()
    {
        return formName;
    }

    public String getAssetName()
    {
        return assetName;
    }

    public String getAssetType()
    {
        return assetType;
    }

    public boolean isSettingCard()
    {
        return settingCard;
    }

    public String getUrl()
    {
        return url;
    }

    /**
     * 取用于提示词「参考图说明」展示的名称：优先主资产名（与视频提示词正文角色名一致），
     * 主资产名缺失时回退形态名。
     */
    public String displayName()
    {
        if (assetName != null && !assetName.isBlank())
        {
            return assetName;
        }
        return formName == null ? "" : formName;
    }

    /** 资产类型中文标签，用于「参考图说明」括注。 */
    public String typeLabel()
    {
        if (assetType == null)
        {
            return "参考";
        }
        switch (assetType)
        {
            case "character":
                return settingCard ? "角色设定卡" : "角色";
            case "scene":
                return "场景";
            case "prop":
                return "道具";
            default:
                return "参考";
        }
    }

    /** 是否角色类（角色 / 角色设定卡）。即梦等单图模型用来挑首帧主角。 */
    public boolean isCharacter()
    {
        return "character".equals(assetType);
    }
}
