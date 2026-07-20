package com.aid.voice.vo;

import java.util.List;

import lombok.Data;

/**
 * 远程音色拉取结果 VO。
 */
@Data
public class RemoteVoiceFetchResultVO
{
    /** 远程音色列表（含 exists 标记） */
    private List<RemoteVoiceVO> voices;

    /** 远程总数 */
    private Integer remoteCount;

    /** 本地已入库数 */
    private Integer localCount;
}
