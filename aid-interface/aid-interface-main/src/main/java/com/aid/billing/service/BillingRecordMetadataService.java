package com.aid.billing.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidComicProjectService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;

/**
 * 消费流水展示元数据组装服务。
 * 统一生成“项目名：具体操作”的业务名称，并提取一次业务实际使用的模型编码。
 */
@Service
@RequiredArgsConstructor
public class BillingRecordMetadataService
{
    private static final String DEFAULT_PROJECT_NAME = "未命名项目";

    private final IAidComicProjectService projectService;

    /**
     * 生成提取类任务的消费业务名称。
     *
     * @param task 提取任务
     * @param resume 是否续生
     * @return 项目名加具体操作
     */
    public String buildExtractBizName(AidExtractTask task, boolean resume)
    {
        if (Objects.isNull(task))
        {
            return resume ? "AI任务续生" : "AI任务生成";
        }
        String operation = resolveExtractOperation(task);
        if (resume)
        {
            operation = operation + "续生";
        }
        return buildProjectBizName(task.getProjectId(), operation);
    }

    /**
     * 生成统一媒体任务的消费业务名称。
     *
     * @param task 媒体任务
     * @return 项目名加具体操作
     */
    public String buildMediaBizName(AidMediaTask task)
    {
        if (Objects.isNull(task))
        {
            return "媒体生成";
        }
        String operation = resolveOperation(task.getBizTaskType(), task.getMediaType());
        return buildProjectBizName(task.getProjectId(), operation);
    }

    /**
     * 按项目和操作生成消费业务名称。
     */
    public String buildProjectBizName(Long projectId, String operation)
    {
        String projectName = resolveProjectName(projectId);
        return projectName + "：" + StrUtil.blankToDefault(operation, "AI生成");
    }

    /**
     * 提取任务可能按角色、场景、道具分别选择模型，因此返回去重后的逗号分隔模型编码。
     */
    public String resolveExtractModelCodes(AidExtractTask task)
    {
        if (Objects.isNull(task))
        {
            return null;
        }
        Set<String> modelCodes = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(task.getInputSnapshot()) && JSONUtil.isTypeJSON(task.getInputSnapshot()))
        {
            try
            {
                JSONObject snapshot = JSONUtil.parseObj(task.getInputSnapshot());
                Object modelCodesValue = snapshot.get("modelCodes");
                if (modelCodesValue instanceof JSONObject modelCodesJson)
                {
                    for (String key : modelCodesJson.keySet())
                    {
                        addModelCode(modelCodes, modelCodesJson.getStr(key));
                    }
                }
                else if (modelCodesValue instanceof Map<?, ?> modelCodeMap)
                {
                    for (Object value : modelCodeMap.values())
                    {
                        addModelCode(modelCodes, Objects.toString(value, null));
                    }
                }
                addModelCode(modelCodes, snapshot.getStr("modelCode"));
            }
            catch (Exception ignored)
            {
                // 旧任务快照可能不是 JSON，继续使用任务主模型兜底。
            }
        }
        addModelCode(modelCodes, task.getModelCode());
        return CollectionUtil.isEmpty(modelCodes) ? null : String.join(",", modelCodes);
    }

    private String resolveExtractOperation(AidExtractTask task)
    {
        String taskType = task.getTaskType();
        if (Objects.equals("asset_extract", taskType))
        {
            List<String> extractTypes = readStringList(task.getInputSnapshot(), "extractTypes");
            List<String> names = new ArrayList<>();
            if (extractTypes.contains("character"))
            {
                names.add("角色");
            }
            if (extractTypes.contains("scene"))
            {
                names.add("场景");
            }
            if (extractTypes.contains("prop"))
            {
                names.add("道具");
            }
            return CollectionUtil.isEmpty(names) ? "资产提取" : String.join("/", names) + "提取";
        }
        String operation = resolveOperation(taskType, null);
        if (Objects.equals("storyboard_image_prompt_batch", taskType)
                || Objects.equals("storyboard_video_prompt_batch", taskType))
        {
            int count = resolveTaskCount(task);
            if (count > 0)
            {
                operation = operation + "（" + count + "个分镜）";
            }
        }
        return operation;
    }

    private String resolveOperation(String taskType, String mediaType)
    {
        if (StrUtil.isNotBlank(taskType))
        {
            return switch (taskType)
            {
                case "storyboard_script", "storyboard_script_batch" -> "分镜脚本生成";
                case "storyboard_image_prompt", "storyboard_image_prompt_batch" -> "分镜图片提示词生成";
                case "storyboard_video_prompt", "storyboard_video_prompt_batch" -> "分镜视频提示词生成";
                case "storyboard_image_generate" -> "分镜图片生成";
                case "storyboard_video_generate" -> "分镜视频生成";
                case "storyboard_edit_image" -> "分镜图片编辑";
                case "storyboard_image_upscale", "image_upscale" -> "图片高清";
                case "storyboard_multi_view_image", "form_multi_view" -> "多视图图片生成";
                case "storyboard_multi_grid_image" -> "多宫格图片生成";
                case "form_generate" -> "形态提示词生成";
                case "form_generate_batch" -> "形态提示词批量生成";
                case "form_image", "form_image_batch" -> "形态图片生成";
                case "form_card_image", "form_card_image_batch" -> "形态卡图片生成";
                case "form_edit_chat" -> "形态图片编辑";
                case "audio_record" -> "分镜配音";
                case "lip_sync_record" -> "对口型生成";
                case "extract" -> "资产提取";
                default -> fallbackMediaOperation(mediaType);
            };
        }
        return fallbackMediaOperation(mediaType);
    }

    private String fallbackMediaOperation(String mediaType)
    {
        if ("IMAGE".equalsIgnoreCase(mediaType))
        {
            return "图片生成";
        }
        if ("VIDEO".equalsIgnoreCase(mediaType))
        {
            return "视频生成";
        }
        if ("AUDIO".equalsIgnoreCase(mediaType))
        {
            return "配音生成";
        }
        if ("TEXT".equalsIgnoreCase(mediaType))
        {
            return "文本生成";
        }
        return "AI生成";
    }

    private int resolveTaskCount(AidExtractTask task)
    {
        List<String> storyboardIds = readStringList(task.getInputSnapshot(), "storyboardIds");
        if (CollectionUtil.isNotEmpty(storyboardIds))
        {
            return storyboardIds.size();
        }
        return Objects.isNull(task.getTotalCount()) ? 0 : task.getTotalCount();
    }

    private List<String> readStringList(String json, String fieldName)
    {
        List<String> values = new ArrayList<>();
        if (StrUtil.isBlank(json) || !JSONUtil.isTypeJSON(json))
        {
            return values;
        }
        try
        {
            JSONArray array = JSONUtil.parseObj(json).getJSONArray(fieldName);
            if (Objects.nonNull(array))
            {
                for (Object value : array)
                {
                    if (Objects.nonNull(value))
                    {
                        values.add(value.toString());
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // 展示元数据解析失败不影响主任务冻结。
        }
        return values;
    }

    private String resolveProjectName(Long projectId)
    {
        if (Objects.isNull(projectId))
        {
            return DEFAULT_PROJECT_NAME;
        }
        // 消费展示只需要项目主键和名称，避免读取项目表其他大字段。
        AidComicProject project = projectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .eq(AidComicProject::getId, projectId)
                        .select(AidComicProject::getId, AidComicProject::getProjectName)
                        .last("LIMIT 1"));
        if (Objects.isNull(project) || StrUtil.isBlank(project.getProjectName()))
        {
            return "项目" + projectId;
        }
        return project.getProjectName().trim();
    }

    private void addModelCode(Set<String> modelCodes, String modelCode)
    {
        if (StrUtil.isNotBlank(modelCode))
        {
            modelCodes.add(modelCode.trim());
        }
    }
}
