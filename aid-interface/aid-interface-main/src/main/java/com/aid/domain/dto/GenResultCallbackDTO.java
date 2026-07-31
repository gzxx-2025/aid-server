package com.aid.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 大模型生成结果回调 DTO
 *
 * 由定时任务轮询时调用，将生成完成的图片/视频结果回写到对应的业务表。
 * 异步回调场景无法从系统上下文获取用户信息，所有必要字段均需显式传入。
 *
 * @author 视觉AID
 */
@Data
public class GenResultCallbackDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 【必传】业务记录主键
     * target=asset 时为 aid_comic_asset.id；target=gen_record 时为 aid_gen_record.id，用于定位要回写的记录。
     */
    private Long recordId;

    /**
     * 【必传】用户ID
     * 异步场景无法从 SecurityContext 获取，需显式传入，同时用于校验记录归属防止越权。
     */
    private Long userId;

    /**
     * 【必传】存储目标，枚举值见 {@link com.aid.enums.GenResultTargetEnum}
     * asset —— 资产表(aid_comic_asset)；gen_record —— 抽卡记录表(aid_gen_record)。
     * 决定结果回写到哪张表。
     */
    private String target;

    /**
     * 【条件必传】资产类型（仅 target=asset 时必传），枚举值见 {@link com.aid.enums.AssetTypeEnum}
     * character / scene / prop / file / pose / effect / expression，用于明确资产分类。
     */
    private String assetType;

    /**
     * 【必传】生成的图片/视频 URL，大模型生成的最终产物地址。
     */
    private String fileUrl;

    /**
     * 【必传】媒体类型，枚举值见 {@link com.aid.enums.MediaTypeEnum}
     * image —— 图片；video —— 视频，影响存储与展示逻辑。
     */
    private String mediaType;
}
