package com.aid.script.service;

import com.aid.script.dto.ScriptSplitPreviewRequest;
import com.aid.script.vo.ScriptSplitConfirmVO;
import com.aid.script.vo.ScriptSplitPreviewVO;

/**
 * 剧本自动分集服务：整篇剧本按分集词切分为多集（预览 + 确认入库）。
 *
 * @author 视觉AID
 */
public interface IScriptSplitService {

    /**
     * 分集预览：只解析不入库，返回每集标题/描述/字数。
     *
     * @param request 预览入参（projectId + scriptText + 可选分集词）
     * @param userId  当前用户ID
     * @return 预览结果
     */
    ScriptSplitPreviewVO previewSplit(ScriptSplitPreviewRequest request, Long userId);

    /**
     * 分集确认入库：按相同解析规则切分后，逐集创建剧集与剧本记录。
     *
     * @param request 确认入参（与预览同构，须传相同文本与分集词）
     * @param userId  当前用户ID
     * @return 入库结果（各集剧集ID/剧本ID）
     */
    ScriptSplitConfirmVO confirmSplit(ScriptSplitPreviewRequest request, Long userId);
}
