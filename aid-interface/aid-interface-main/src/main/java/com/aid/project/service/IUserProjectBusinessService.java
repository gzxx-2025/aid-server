package com.aid.project.service;

import java.util.List;
import com.aid.aid.domain.AidComicProject;
import com.aid.project.dto.UserProjectCreateRequest;
import com.aid.project.dto.UserProjectQueryRequest;
import com.aid.project.dto.UserProjectUpdateRequest;
import com.aid.project.vo.UserProjectVO;

/**
 * 用户项目业务Service接口
 *
 * @author 视觉AID
 */
public interface IUserProjectBusinessService
{
    /**
     * 查询用户的项目列表（带软删除过滤）
     *
     * @param request 查询条件
     * @param userId 用户ID
     * @return 项目列表
     */
    List<AidComicProject> selectUserProjectList(UserProjectQueryRequest request, Long userId);

    /**
     * 查询用户的项目详情（带归属校验）
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 项目详情
     */
    AidComicProject selectUserProjectById(Long id, Long userId);

    /**
     * 用户创建项目
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 新增的项目
     */
    AidComicProject insertUserProject(UserProjectCreateRequest request, Long userId);

    /**
     * 用户修改项目（带归属校验）
     *
     * @param request 修改请求
     * @param userId 用户ID
     * @return 修改后的项目
     */
    AidComicProject updateUserProject(UserProjectUpdateRequest request, Long userId);

    /**
     * 用户删除项目（带归属校验，级联硬删除项目及其全部子数据并清理OSS文件）
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 影响行数
     */
    int softDeleteUserProjectById(Long id, Long userId);

    /**
     * 用户提交项目审核（带归属校验）
     * 除「审核中(3)」「审核通过(4)」外的状态均可提交（成片导出成功后状态自动变为「完成未提交(2)」即可提审），
     * 提交后状态置为「审核中(3)」并清空状态原因，同时写入审核流水。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 提交审核后的项目
     */
    AidComicProject submitAudit(Long id, Long userId);

    /**
     * 用户公开项目（带归属校验）
     * 前提：项目必须为「审核通过(4)」状态；公开后 is_public 置为 1。
     * 公开期间项目内容锁定（禁止修改项目信息、剧集增删改、时间轴保存），须先关闭公开才能修改；
     * 导出成片不受锁限制，审核中/已过审内容重新导出时新片进入待审槽，旧片继续对外展示，重新过审后转正。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 公开后的项目
     */
    AidComicProject publishProject(Long id, Long userId);

    /**
     * 用户关闭项目公开（带归属校验）
     * is_public 置回 0，项目从公开列表下架，内容恢复可修改；审核状态（status）保持不变。
     * 未公开时幂等返回。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 关闭公开后的项目
     */
    AidComicProject unpublishProject(Long id, Long userId);

    /**
     * 项目实体批量转 VO：电影模式项目附加项目级成片信息
     * （episodeEditorId / finalVideoUrl / exportStatus，取自 aid_episode_editor 的 episode_id=0 记录）；
     * 剧集类型项目成片字段为 null（成片挂在各集上），并附加集数 episodeCount（批量统计，无集为 0）。
     *
     * @param projects 项目实体列表（同一用户）
     * @return VO 列表（顺序与入参一致）
     */
    List<UserProjectVO> convertToVOList(List<AidComicProject> projects);

    /**
     * 单条项目实体转 VO（复用批量口径）。
     *
     * @param project 项目实体
     * @return 项目 VO
     */
    UserProjectVO convertToVO(AidComicProject project);
}
