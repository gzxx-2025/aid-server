package com.aid.project.service;

import com.aid.aid.domain.AidComicProject;

/**
 * 项目内容修改守卫Service接口
 * 统一约束：项目公开期间（is_public=1）禁止修改项目内容（项目信息、剧集增删改、时间轴保存），
 * 用户必须先调用「关闭公开」接口把项目下架后才能继续修改。
 * 导出成片不在锁定范围：公开期间允许导出，新成片进入待审槽，旧片继续展示，重新过审后转正。
 *
 * @author 视觉AID
 */
public interface IProjectContentGuardService
{
    /**
     * 校验项目内容是否允许修改（按项目ID查询后校验）
     * 项目已公开（is_public=1）时抛出异常拒绝修改；项目不存在时不在此处拦截（由各业务自身的归属校验负责）。
     *
     * @param projectId 项目ID
     */
    void assertProjectEditable(Long projectId);

    /**
     * 校验项目内容是否允许修改（调用方已持有项目实体时使用，避免重复查询）
     * 项目已公开（is_public=1）时抛出异常拒绝修改。
     *
     * @param project 项目实体（须含 isPublic 字段）
     */
    void assertProjectEditable(AidComicProject project);
}
