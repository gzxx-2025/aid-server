package com.aid.web.controller.system;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.utils.StringUtils;

/**
 * 首页
 *
 * @author 视觉AID
 */
@RestController
public class SysIndexController
{
    /**
     * 访问首页，提示语
     */
    @RequestMapping("/")
    public String index()
    {
        return StringUtils.format("error");
    }
}
