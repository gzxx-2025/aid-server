package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 时间轴背景音乐轨（全片级单条）：url 为空 = 不添加背景音乐。
 * 出参恒定：对象永不为 null，无音乐时 url/name=null、volume/loop/fade 给默认值。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineBgm {

    /** 背景音乐地址。库内存相对路径，出参拼完整域名；为空 = 无背景音乐 */
    private String url;

    /** 音乐展示名称（前端选择/上传时携带，仅展示用） */
    private String name;

    /** 背景音乐音量（0-100，默认 30，避免盖过配音） */
    private Integer volume;

    /** 是否循环铺满整片（预留，默认 true） */
    private Boolean loop;

    /** 是否淡入淡出（预留，默认 true） */
    private Boolean fade;
}
