package com.aid.project.service.impl;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.exception.ServiceException;
import com.aid.project.service.IProjectContentGuardService;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目内容修改守卫Service实现
 * 项目公开期间（is_public=1）禁止修改内容，须先关闭公开（/api/user/project/unpublish）再修改。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ProjectContentGuardServiceImpl implements IProjectContentGuardService
{
    /** 是否公开：是 */
    private static final String IS_PUBLIC_YES = "1";

    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    /**
     * 校验项目内容是否允许修改（按项目ID查询后校验）
     *
     * @param projectId 项目ID
     */
    @Override
    public void assertProjectEditable(Long projectId)
    {
        if (Objects.isNull(projectId)) {
            return;
        }
        // 查询字段精简：公开锁校验只需主键与公开标记（新增使用字段时此处必须同步补充）
        AidComicProject project = aidComicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getIsPublic)
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        // 项目不存在不在此处拦截，由各业务自身的归属校验负责报错
        if (Objects.isNull(project)) {
            return;
        }
        assertProjectEditable(project);
    }

    /**
     * 校验项目内容是否允许修改（调用方已持有项目实体时使用）
     *
     * @param project 项目实体（须含 isPublic 字段）
     */
    @Override
    public void assertProjectEditable(AidComicProject project)
    {
        if (Objects.isNull(project)) {
            return;
        }
        // 公开期间内容锁定：必须先关闭公开才能修改
        if (Objects.equals(IS_PUBLIC_YES, project.getIsPublic())) {
            log.info("项目已公开，内容修改被拒绝: projectId={}", project.getId());
            throw new ServiceException("请先关闭项目公开");
        }
    }
}
