package com.aid.captcha.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.aid.captcha.domain.dto.CaptchaCheckRequest;
import com.aid.captcha.domain.dto.CaptchaGenRequest;
import com.aid.common.annotation.Anonymous;
import com.aid.common.captcha.service.CaptchaService;
import com.aid.common.core.domain.AjaxResult;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 行为验证码控制器（C 端）。
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "行为验证码", description = "滑块/点选等人机校验接口")
@RestController
@RequestMapping("/captcha")
public class BehaviorCaptchaController {

    @Resource
    private CaptchaService captchaService;

    /**
     * 生成验证码。
     *
     * @param request 生成参数（type 可选；GET 时为空）
     * @return 开启时 data 含验证码图数据；未开启/降级时 data.enabled=false
     */
    @Operation(summary = "生成验证码", description = "返回当前类型的行为验证码数据")
    @Anonymous
    @RequestMapping(value = "/gen", method = { RequestMethod.GET, RequestMethod.POST })
    public AjaxResult gen(@RequestBody(required = false) CaptchaGenRequest request) {
        ImageCaptchaVO vo = captchaService.generate();
        AjaxResult ajax = AjaxResult.success();
        // 未开启或降级：明确告知前端 enabled=false
        if (Objects.isNull(vo)) {
            ajax.put("data", new EnabledFlag(false));
            return ajax;
        }
        ajax.put("data", vo);
        return ajax;
    }

    /**
     * 校验验证码。
     *
     * @param request 轨迹数据
     * @return 成功时 data.token 为一次性凭证；失败返回错误信息
     */
    @Operation(summary = "校验验证码", description = "校验行为轨迹,成功签发一次性token")
    @Anonymous
    @PostMapping("/check")
    public AjaxResult check(@Valid @RequestBody CaptchaCheckRequest request) {
        ImageCaptchaTrack track = buildTrack(request);
        String token = captchaService.check(request.getId(), track);
        // 校验失败：先 log，再返回可读文案
        if (StrUtil.isBlank(token)) {
            log.info("验证码校验失败: id={}", request.getId());
            return AjaxResult.error("验证失败");
        }
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", new TokenResult(token));
        return ajax;
    }

    /**
     * 将请求 DTO 组装为 tianai 的轨迹对象。
     */
    private ImageCaptchaTrack buildTrack(CaptchaCheckRequest request) {
        ImageCaptchaTrack track = new ImageCaptchaTrack();
        // SDK 把轨迹与尺寸包在 data 字段下
        CaptchaCheckRequest.TrackData d = request.getData();
        if (Objects.isNull(d)) {
            // 没有轨迹数据：返回空轨迹，由校验器判定失败
            track.setTrackList(new ArrayList<>());
            return track;
        }
        track.setBgImageWidth(d.getBgImageWidth());
        track.setBgImageHeight(d.getBgImageHeight());
        track.setTemplateImageWidth(d.getTemplateImageWidth());
        track.setTemplateImageHeight(d.getTemplateImageHeight());
        track.setStartTime(d.getStartTime());
        track.setStopTime(d.getStopTime());
        track.setData(d.getData());
        List<ImageCaptchaTrack.Track> trackList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(d.getTrackList())) {
            for (CaptchaCheckRequest.TrackDTO t : d.getTrackList()) {
                ImageCaptchaTrack.Track point = new ImageCaptchaTrack.Track();
                point.setX(t.getX());
                point.setY(t.getY());
                point.setT(t.getT());
                if (StrUtil.isNotBlank(t.getType())) {
                    point.setType(t.getType());
                }
                trackList.add(point);
            }
        }
        track.setTrackList(trackList);
        return track;
    }

    /**
     * 未开启标志返回体。
     */
    private record EnabledFlag(boolean enabled) {
    }

    /**
     * token 返回体。
     */
    private record TokenResult(String token) {
    }
}
