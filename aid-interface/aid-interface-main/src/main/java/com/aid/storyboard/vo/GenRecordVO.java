package com.aid.storyboard.vo;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 生成记录VO(返回给前端)
 *
 * @author 视觉AID
 */
@Data
@Builder
public class GenRecordVO {

    /** 记录ID */
    private Long id;

    /**
     * 展示名：`分镜{全局镜号}-图片{序号}` / `分镜{全局镜号}-视频{序号}`。
     * 全局镜号来自与 sortOrder 同步的 script_params.镜号，缺失时回落 sortOrder / 分镜 ID。
     */
    private String displayName;

    /** 分镜ID */
    private Long storyboardId;

    /** 生成类型 */
    private String genType;

    /** 生成的文件URL（出参拼域名） */
    @MediaUrl
    private String fileUrl;

    /** 模型ID */
    private Long modelId;

    /** 用户补充文本 */
    private String userInputText;

    /** 图生视频依赖的底图ID */
    private Long baseImageId;

    /** 首帧图ID */
    private Long firstImageId;

    /** 尾帧图ID */
    private Long lastImageId;

    /** 视频时长 */
    private Long videoDuration;

    /** 音效描述 */
    private String soundDesc;

    /** 消耗积分 */
    private BigDecimal costCredits;

    /** 是否被选为最终分镜(0否 1是) */
    private Integer isSelected;

    /** 大模型任务ID（异步生成时前端用于轮询） */
    private Long taskId;

    /** 任务状态：PENDING/PROCESSING/SUCCEEDED/FAILED */
    private String status;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
