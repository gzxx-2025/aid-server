package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量配音请求DTO。
 * 按「项目 + 剧集」为有台词的分镜逐条执行「配音 → 贴回该分镜最终视频 → 新配音视频自动设为使用中」：
 * 产物是每分镜一条配音合成视频生成记录（aid_gen_record，genType=compose），原视频自动置为未使用
 * （记录保留、绝不覆盖；overwrite=true 重配同样只新增不覆盖）。
 * 每分镜音色解析优先级：台词首个角色段的启用音色绑定（aid_role_voice_binding） → 本请求兜底音色
 * （voiceLibraryId / voiceModelId+timbreCode）；有台词分镜双空时整批拒绝（请先绑定音色）；
 * 无台词的分镜自动跳过，不做任何处理。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardAudioBatchRequest {

    /** 项目ID（必填） */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（必填，电影传 0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /**
     * 目标分镜ID列表（可选）。
     * 为空 = 该剧集下全部「有台词」的分镜；传入时仅处理列表内归属本人的分镜。
     */
    private List<Long> storyboardIds;

    /**
     * 兜底音色库ID（可选，aid_ai_voice_library.id）。
     * 分镜台词解析不出「角色→绑定音色」时（如纯旁白/角色未绑定），用此音色配音。
     */
    private Long voiceLibraryId;

    /** 兜底配音模型ID（可选，兼容老入参；与 timbreCode 搭配使用，voiceLibraryId 存在时忽略） */
    private Long voiceModelId;

    /** 兜底音色编码（可选，兼容老入参；与 voiceModelId 搭配使用） */
    private String timbreCode;

    /**
     * 是否覆盖重配（可选，默认 false）。
     * false = 已存在成功配音记录（aid_audio_record.status=SUCCEEDED）的分镜跳过；
     * true = 全部重新配音（新配一条记录，旧记录保留）。
     */
    private Boolean overwrite;

    /** 情感（可选，供应商原生编码，仅音色支持情感时生效，逐条透传单分镜配音链路） */
    private String emotion;

    /** 情感强度（可选，1~5） */
    @Min(value = 1, message = "情感强度无效")
    @Max(value = 5, message = "情感强度无效")
    private Integer emotionScale;

    /** 语速（可选，[-50,100]） */
    @Min(value = -50, message = "语速无效")
    @Max(value = 100, message = "语速无效")
    private Integer speechRate;

    /** 音量（可选，[-50,100]） */
    @Min(value = -50, message = "音量无效")
    @Max(value = 100, message = "音量无效")
    private Integer loudnessRate;

    /** 音调（可选，[-12,12]） */
    @Min(value = -12, message = "音调无效")
    @Max(value = 12, message = "音调无效")
    private Integer pitch;

    /** 配音视频输出分辨率档（可选，SD/HD/FHD/2K/4K，默认 FHD，透传合成链路） */
    private String resolution;
}
