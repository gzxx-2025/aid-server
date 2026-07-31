package com.aid.script.vo;

import lombok.Data;

/**
 * 剧本分集预览单集条目。
 *
 * @author 视觉AID
 */
@Data
public class ScriptSplitEpisodeItemVO {

    /** 集号（按分集行出现顺序 1 起；确认入库时在项目已有集数上顺延） */
    private Integer episodeNo;

    /** 单集标题（分集行余文；无余文回退「第N集」） */
    private String title;

    /** 单集描述（正文前 20 字，超长以 ... 结尾） */
    private String description;

    /** 该集正文字数（不含空白） */
    private Integer charCount;
}
