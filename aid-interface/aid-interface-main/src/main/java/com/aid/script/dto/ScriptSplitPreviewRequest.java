package com.aid.script.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 剧本分集预览入参。
 *
 * @author 视觉AID
 */
@Data
public class ScriptSplitPreviewRequest {

    /** 项目ID（必填，必须是剧集类型项目） */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /**
     * 整篇剧本文本（必填，上限 10 万字）。
     * 来源：用户在导入框粘贴的整篇剧本，或前端读取 .txt 文件后的文本内容。
     */
    @NotBlank(message = "剧本内容不能为空")
    private String scriptText;

    /**
     * 分集词样例（可空，默认「第一集」）。
     * 含序数的样例（如「第一集」「第1话」）自动泛化：「第一集/第2集/第十三集」均可命中；
     * 不含序数的样例（如「===分集===」）按行首字面量匹配。
     */
    private String episodeKeyword;
}
