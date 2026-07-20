package com.aid.script.vo;

import java.util.List;

import lombok.Data;

/**
 * 剧本分集预览出参。
 *
 * @author 视觉AID
 */
@Data
public class ScriptSplitPreviewVO {

    /** 共解析出多少集 */
    private Integer totalEpisodes;

    /** 全篇有效字数（不含空白） */
    private Integer totalCharCount;

    /** 实际生效的分集词样例（入参为空时回显默认值） */
    private String episodeKeyword;

    /** 各集预览条目（按剧本出现顺序） */
    private List<ScriptSplitEpisodeItemVO> items;
}
