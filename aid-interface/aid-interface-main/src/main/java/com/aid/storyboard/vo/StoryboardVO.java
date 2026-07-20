package com.aid.storyboard.vo;

import java.util.Date;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 分镜VO(返回给前端)
 *
 * @author 视觉AID
 */
@Data
@Builder
public class StoryboardVO {

    private Long id;
    private Long projectId;
    private Long episodeId;
    private Long sortOrder;

    /** 分镜标题 */
    private String title;

    /** 分镜剧本内容(对该镜头的剧情描述) */
    private String storyScript;

    /** 分镜台词配音文本(该镜头角色的对话) */
    private String dialogueText;

    /**
     * 字幕展示文本：由 {@code dialogueText} 格式化为「人物：说的话」（剥掉形象名/情感标注/占位/引号壳，
     * 多段按换行拼接），供成品预览时间轴字幕轨与导出字幕直接使用；无台词为 null。
     */
    private String subtitleText;

    /**
     * 配音类型：{@code narration}=纯旁白（含画外音）/ {@code dialogue}=纯角色对白 / {@code mixed}=旁白+对白混合；
     * 无可朗读台词为 null。
     */
    private String voiceType;

    /** 发言角色主名列表（台词解析、按出现顺序去重，旁白段不计入）；无可朗读台词为 null */
    private List<String> speakerRoles;

    /** 发言角色的音色绑定列表（与 {@link #speakerRoles} 同序同长）；无角色对白为 null */
    private List<StoryboardSpeakerVoiceVO> speakerVoices;

    /** 配音状态：{@code SUCCEEDED}=已配音 / {@code PROCESSING}=配音中 / {@code FAILED}=配音失败 / {@code NONE}=未配音 */
    private String audioStatus;

    /** 最终产物ID */
    private Long finalImageId;
    private Long finalVideoId;
    private Long finalAudioId;

    /**
     * 主图 URL：以 {@code aid_gen_record.is_selected=1}（图片类，del_flag='0'）为权威源，未设置时为 null。
     */
    @MediaUrl
    private String finalImageUrl;

    /**
     * 分镜视频 URL（video 类主视频，恒为配音前原视频）：以 {@code final_video_id} 指针为权威源
     * （i2v/multi/edge/upload_video），配音视频的主选中不影响本字段。未设置时为 null。
     */
    @MediaUrl
    private String finalVideoUrl;

    /**
     * 最终配音视频 URL（compose 类主视频）：以 {@code aid_gen_record.is_selected=1}（genType=compose，
     * del_flag='0'）为权威源，取最新一条；成片合成导出优先使用本视频（配音已合进画面）。未设置时为 null。
     */
    @MediaUrl
    private String finalComposeVideoUrl;

    /**
     * 该最终分镜图实际引用的参考图列表（快照，按 {@code @图片N} 编号升序），仅存在主图且记录带快照时返回，否则为 null。
     */
    private List<StoryboardRefImageVO> referenceImages;

    /**
     * 分镜图生成提示词（对应 {@code aid_storyboard.image_prompt}），仅明细接口返回，列表接口为 null。
     */
    private String imagePrompt;

    /**
     * 分镜视频提示词——多参方向（对应 {@code aid_storyboard.video_prompt}），仅明细接口返回，列表接口为 null。
     */
    private String videoPrompt;

    /**
     * 分镜视频提示词——图生方向（对应 {@code aid_storyboard.video_prompt_image}），仅明细接口返回，列表接口为 null。
     */
    private String videoPromptImage;

    /**
     * 宫格类型（「四宫格」/「九宫格」，对应 {@code aid_storyboard.grid_type}），非 auto_grid 模式为 null，仅明细接口返回。
     */
    private String gridType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
