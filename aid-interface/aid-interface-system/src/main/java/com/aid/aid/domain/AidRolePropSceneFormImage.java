package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 角色场景道具形态图片实例对象 aid_role_prop_scene_form_image。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_role_prop_scene_form_image")
public class AidRolePropSceneFormImage extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联形态ID（aid_role_prop_scene_form.id） */
    @Excel(name = "关联形态ID")
    private Long formId;

    /** 冗余主资产ID（aid_role_prop_scene.id），用于按主资产维度高效检索 */
    @Excel(name = "主资产ID")
    private Long assetId;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID（电影模式为 0） */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 图片名称，默认 资产名_形态名_序号 */
    @Excel(name = "图片名称")
    private String name;

    /** 形态图 OSS 地址（出参拼域名 / 入参剥离域名） */
    @Excel(name = "形态图URL")
    @MediaUrl
    private String imageUrl;

    /** 来源类型：ai_auto / ai_builder / ai_manual / upload / official / migrate */
    @Excel(name = "来源类型")
    private String sourceType;

    /** 提示词下标，从 0 开始；上传 / 官方 / 迁移图可为空 */
    @Excel(name = "提示词下标")
    private Integer descriptionIndex;

    /** 本次实际使用的提示词快照 */
    private String promptSnapshot;

    /**
     * 本次生图使用的参考图 JSON 数组（数据库列类型为 JSON）。
     * 实体里以字符串承载，业务侧自行用 ObjectMapper 序列化 / 反序列化。
     */
    private String referenceImages;

    /** 同一次生成批次号（手动多张同批使用同一 batchNo，便于排序与回查） */
    private String batchNo;

    /** 同形态下排序（小→大），同时段插入按 0 起算 */
    private Integer sortOrder;

    /** 是否使用中（0/1）；同一 form 下允许多张同时为 1 */
    @Excel(name = "是否使用中")
    private Integer isUse;

    /** 图片状态：pending / processing / completed / failed */
    private String imageStatus;

    /** 失败原因（image_status=failed 时落库） */
    private String failReason;

    /**
     * 是否为拆分源图：1 表示该图已经被拆分成 4 宫格子图，不允许再次作为源图被拆分。
     * 仅 scene 类型形态图可被拆分（参见 {@code RpsFormImageBusinessServiceImpl#splitSceneImage}）。
     */
    private Integer isSplitSource;

    /**
     * 是否为拆分产物：1 表示该图本身是其他图片拆分而来，不允许再作为源图被拆分。
     * 同一张图，{@code isSplitSource} 与 {@code isSplitChild} 必有一项为 0，互不冲突但不会同时为 1。
     */
    private Integer isSplitChild;

    /**
     * 拆分产物的源图 ID（仅 {@code isSplitChild=1} 时有值，对应 aid_role_prop_scene_form_image.id）。
     * 用于排查"哪张子图来自哪张源图"，业务侧不强依赖此字段。
     */
    private Long splitParentImageId;

    /** 删除标志（0=存在 2=删除） */
    private String delFlag;

    /**
     * 名称统一 trim 入库（源头收口）：无论从哪个写入路径（手动上传 / AI 生成 / 拆分子图 / 设定卡 / 编辑 / 自愈）设置 name，
     * 一律去除首尾空格。与下游 {@code @图片N[name]} 占位解析（StoryboardImageReferenceResolver / 分镜图脚本可引用资产白名单）
     * 口径一致，杜绝因 name 带首尾空格导致"白名单可用但出图解析查不到"。
     * 显式定义此 setter 覆盖 Lombok {@code @Data} 生成的版本；不引第三方依赖，用原生 trim 保持 domain 纯净。
     */
    public void setName(String name)
    {
        this.name = (name == null) ? null : name.trim();
    }
}
