package com.aid.aid.domain;

import java.math.BigDecimal;
import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * AI生图/生视频抽卡记录对象 aid_gen_record
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_gen_record")
public class AidGenRecord extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 项目ID（冗余存储，便于按项目维度反查） */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID（电影固定为 0；冗余存储，便于按剧集维度反查） */
    @Excel(name = "剧集ID(电影为0)")
    private Long episodeId;

    /** 分镜脚本主表ID */
    @Excel(name = "分镜脚本主表ID")
    private Long storyboardId;

    /** 类型: image单图, grid九宫格, i2v图生视频, multi多参视频, edge首尾视频 */
    @Excel(name = "类型: image单图, grid九宫格, i2v图生视频, multi多参视频, edge首尾视频")
    private String genType;

    /** 生成的URL（相对路径） */
    @Excel(name = "生成的URL")
    @MediaUrl
    private String fileUrl;

    /** 大模型异步任务ID(用于回调接口匹配) */
    @Excel(name = "大模型异步任务ID")
    private String taskId;

    /** 生成状态(0处理中, 1成功, 2失败) */
    @Excel(name = "生成状态", readConverterExp = "0=处理中,1=成功,2=失败")
    private Integer status;

    /** 轻量级生成参数(仅存储前端传入的原始Request DTO) */
    private String genParams;

    /** 该 take 的确定性业务序号(bizSeq=parentTaskId*1000000+slot)：分镜出图/出片的幂等唯一键，其他来源为 null */
    @TableField(value = "biz_seq")
    private Long bizSeq;

    /** 关联的aid_ai_model */
    @Excel(name = "关联的aid_ai_model")
    private Long modelId;

    /** 最终生成提示词 */
    @Excel(name = "最终生成提示词")
    private String promptText;

    /** 用户自己输入的补充文本 */
    @Excel(name = "用户自己输入的补充文本")
    private String userInputText;

    /** 图生视频依赖的底图 */
    @Excel(name = "图生视频依赖的底图")
    private Long baseImageId;

    /** 首尾帧视频依赖的首图 */
    @Excel(name = "首尾帧视频依赖的首图")
    private Long firstImageId;

    /** 首尾帧视频依赖的尾图 */
    @Excel(name = "首尾帧视频依赖的尾图")
    private Long lastImageId;

    /** 视频时长 */
    @Excel(name = "视频时长")
    private Long videoDuration;

    /** 音效描述 */
    @Excel(name = "音效描述")
    private String soundDesc;

    /** 本次生成消耗的积分 */
    @Excel(name = "本次生成消耗的积分")
    private BigDecimal costCredits;

    /** 是否被选为最终分镜(0否 1是) */
    @Excel(name = "是否被选为最终分镜(0否 1是)")
    private Integer isSelected;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
