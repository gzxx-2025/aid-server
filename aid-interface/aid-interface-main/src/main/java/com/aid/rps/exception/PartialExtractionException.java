package com.aid.rps.exception;

import java.util.List;
import com.aid.rps.vo.RpsAssetVO;
import lombok.Getter;

/**
 * 资产提取部分完成异常，让 Consumer 把任务标记为 PARTIAL_FAILED 并保留续跑入口。
 *
 * @author 视觉AID
 */
@Getter
public class PartialExtractionException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /** 已成功提取的资产列表（部分结果） */
    private final List<RpsAssetVO> partialAssets;

    public PartialExtractionException(String message, List<RpsAssetVO> partialAssets)
    {
        super(message);
        this.partialAssets = partialAssets;
    }
}
