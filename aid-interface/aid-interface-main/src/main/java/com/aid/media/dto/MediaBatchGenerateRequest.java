package com.aid.media.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量媒体生成入参：一条 HTTP 请求内提交多张子任务，由服务端分配统一 batchId。
 */
@Data
public class MediaBatchGenerateRequest {

    /** 项目ID（可选）：批量任务共享，列表查询时按项目过滤。 */
    private Long projectId;

    /** 剧集ID（可选）：电影模式为0，剧集模式为真实剧集ID。 */
    private Long episodeId;

    /** 业务含义：子任务列表，须非空且条数不超过服务端上限（当前 20），顺序会反映为任务 id 升序展示。 */
    private List<BatchGenerateItem> items;

    /**
     * 单条子任务：通过 mediaType 指定走图片或视频参数体，与单接口字段语义一致。
     */
    @Data
    public static class BatchGenerateItem {

        /** 业务含义：IMAGE 或 VIDEO（大小写不敏感），决定下列哪一个 request 生效。 */
        private String mediaType;

        /** 业务含义：mediaType=IMAGE 时必填，字段与 {@link MediaImageGenerateRequest} 单接口相同。 */
        private MediaImageGenerateRequest imageRequest;

        /** 业务含义：mediaType=VIDEO 时必填，字段与 {@link MediaVideoGenerateRequest} 单接口相同。 */
        private MediaVideoGenerateRequest videoRequest;
    }
}
