package com.aid.service;

import com.aid.domain.dto.GenResultCallbackDTO;

/**
 * 大模型生成结果回调 Service 接口。
 *
 * @author 视觉AID
 * @see GenResultCallbackDTO 入参说明（含必传/非必传标注）
 */
public interface IGenResultCallbackService {

    /**
     * 处理大模型生成结果回调，回写图片/视频结果到资产表或抽卡记录表。
     *
     * @param dto 回调参数，详见 {@link GenResultCallbackDTO}
     * @return true=处理成功, false=处理失败
     * @throws IllegalArgumentException 参数校验不通过时抛出
     */
    boolean handleGenResult(GenResultCallbackDTO dto);

    /**
     * 根据记录ID和分类回填生成的文件URL
     *
     * @param recordId 记录主键（aid_comic_asset.id 或 aid_gen_record.id）
     * @param fileUrl  生成的图片/视频URL
     * @param category 分类：asset（资产表）/ gen_record（抽卡记录表）
     * @return true=回填成功, false=记录不存在
     */
    boolean fillResultUrl(Long recordId, String fileUrl, String category);
}
