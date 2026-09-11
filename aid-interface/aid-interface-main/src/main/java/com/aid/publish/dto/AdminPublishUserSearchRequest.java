package com.aid.publish.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台-发布管理用户搜索请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminPublishUserSearchRequest {

    /** 搜索关键字（昵称/邮箱/手机号，模糊匹配） */
    @NotBlank(message = "请输入搜索关键字")
    private String keyword;
}
