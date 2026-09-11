package com.aid.project.snapshot.vo;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectSnapshotPreviewVO
{
    private Long projectId;
    private String projectName;
    private String projectType;
    private Long snapshotId;
    private Integer revisionNo;
    private Integer schemaVersion;
    /** 可作为客户端缓存校验值。 */
    private String snapshotHash;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishedAt;
    private Boolean allowPreview;
    private Boolean allowCopy;
    private String copyLabel;
    private List<FlowNode> nodes;
    private List<FlowEdge> edges;

    @Data
    @Builder
    public static class FlowNode
    {
        private String id;
        private String type;
        private String stage;
        private String title;
        private Integer order;
        /** data 对齐的正常工作台 VO 或其显式命名的匿名公开投影。 */
        private String contract;
        /** 权威接口，仅用于契约识别，不代表预览时重新调用该接口。 */
        private String sourceEndpoint;
        /** 从发布快照装配的公开业务数据，共有字段沿用工作台字段名和语义，不读取实时子表。 */
        private Object data;
        /** 快照专有的补充信息，与权威业务 data 分离，禁止承载任务、计费或提示词等内部字段。 */
        private Map<String, Object> snapshotMeta;
    }

    @Data
    @Builder
    public static class FlowEdge
    {
        private String id;
        private String source;
        private String target;
        private String relation;
    }
}
