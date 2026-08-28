import React, { useState } from 'react';

import ProjectListView from './ProjectListView';
import ProjectWorkspaceView from './ProjectWorkspaceView';

/**
 * 漫剧项目工作台（需求16 整合）。
 *
 * 以「项目」为主线，将剧本、剧集、分镜、角色道具场景、场景形态图、生成记录、视频成片、
 * 音频资产、提取任务、用户参考资产等串成一套操作流程：
 *  - 默认展示项目列表；
 *  - 点击「进入工作台」后进入该项目维度，左侧按制作环节导航，右侧展示对应数据（按 projectId 过滤）。
 *
 * 说明：生成记录 / 提取任务 同时保留独立菜单作为"全量任务列表"；项目提取资产为跨项目共享资产库，保留独立入口。
 */
export default function ComicWorkspacePage() {
  const [project, setProject] = useState<any | null>(null);

  if (!project) {
    return (
      <div className="crud-page" style={{ padding: 16 }}>
        <ProjectListView onEnter={setProject} />
      </div>
    );
  }

  return (
    <div className="crud-page" style={{ padding: 16 }}>
      <ProjectWorkspaceView project={project} onBack={() => setProject(null)} />
    </div>
  );
}
