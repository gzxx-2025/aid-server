package com.aid.script.vo;

import java.util.List;

import lombok.Data;

/**
 * 剧本分集确认入库出参。
 *
 * @author 视觉AID
 */
@Data
public class ScriptSplitConfirmVO {

    /** 本次共创建多少集 */
    private Integer totalEpisodes;

    /** 各集入库结果（按剧本出现顺序） */
    private List<CreatedEpisodeVO> episodes;

    /** 单集入库结果 */
    @Data
    public static class CreatedEpisodeVO {

        /** 新建剧集ID（aid_comic_episode.id） */
        private Long episodeId;

        /** 入库集号（项目已有集数上顺延） */
        private Long episodeNo;

        /** 单集标题 */
        private String title;

        /** 单集描述 */
        private String description;

        /** 新建剧本记录ID（aid_comic_script.id，版本1、使用中） */
        private Long scriptId;
    }
}
