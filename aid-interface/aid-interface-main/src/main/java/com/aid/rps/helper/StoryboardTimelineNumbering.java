package com.aid.rps.helper;

import java.util.LinkedHashMap;
import java.util.Map;

import com.aid.aid.domain.AidStoryboard;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;

/**
 * 同步分镜时间线排序号及其编号镜像字段。
 *
 * @author 视觉AID
 */
public final class StoryboardTimelineNumbering
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StoryboardTimelineNumbering()
    {
    }

    /**
     * 同步分镜排序号及编号镜像字段。
     *
     * @param storyboard 分镜
     * @param sortOrder 排序号
     */
    public static void synchronize(AidStoryboard storyboard, long sortOrder)
    {
        storyboard.setSortOrder(sortOrder);
        if (StrUtil.isBlank(storyboard.getTitle())
                || storyboard.getTitle().matches("^分镜脚本\\d+$"))
        {
            storyboard.setTitle(String.format("分镜脚本%03d", sortOrder));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(storyboard.getScriptParams()))
        {
            try
            {
                params = OBJECT_MAPPER.readValue(storyboard.getScriptParams(),
                        OBJECT_MAPPER.getTypeFactory().constructMapType(
                                LinkedHashMap.class, String.class, Object.class));
            }
            catch (Exception e)
            {
                throw new ServiceException("分镜编号失败");
            }
        }
        params.put("镜号", String.format("%03d", sortOrder));
        try
        {
            storyboard.setScriptParams(OBJECT_MAPPER.writeValueAsString(params));
        }
        catch (Exception e)
        {
            throw new ServiceException("分镜编号失败");
        }
    }
}
