package com.aid.captcha.domain.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 校验验证码请求参数。
 *
 * @author 视觉AID
 */
@Data
public class CaptchaCheckRequest {

    /** 验证码 ID（由 /captcha/gen 返回） */
    @NotBlank(message = "验证码ID不能为空")
    private String id;

    /** 轨迹与尺寸数据（SDK 将其包在 data 字段下） */
    private TrackData data;

    /**
     * 轨迹与尺寸数据。
     */
    @Data
    public static class TrackData {

        /** 背景图宽度 */
        private Integer bgImageWidth;

        /** 背景图高度 */
        private Integer bgImageHeight;

        /** 模板图宽度 */
        private Integer templateImageWidth;

        /** 模板图高度 */
        private Integer templateImageHeight;

        /** 滑动开始时间（毫秒时间戳） */
        private Long startTime;

        /** 滑动结束时间（毫秒时间戳） */
        private Long stopTime;

        /** 行为轨迹列表 */
        private List<TrackDTO> trackList;

        /** 扩展数据（点选/拼图等场景使用） */
        private Object data;
    }

    /**
     * 单条轨迹。
     */
    @Data
    public static class TrackDTO {

        /** x 坐标 */
        private Float x;

        /** y 坐标 */
        private Float y;

        /** 相对时间 */
        private Float t;

        /** 轨迹类型（move/click/up/down 等） */
        private String type;
    }
}
