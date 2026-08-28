import React, { useMemo, useState } from 'react';
import { Avatar, Button, Card, Descriptions, Menu, Tag } from 'antd';
import {
  ArrowLeftOutlined, FileTextOutlined, PlayCircleOutlined, AppstoreOutlined,
  PictureOutlined, VideoCameraOutlined, SoundOutlined, ThunderboltOutlined,
  ProfileOutlined, UserOutlined, OrderedListOutlined, ApartmentOutlined
} from '@ant-design/icons';

import {
  PROJECT_TYPE_OPTIONS, PROJECT_STATUS_OPTIONS, CURRENT_STEP_WITH_MOVIE_OPTIONS,
  getLabelByValue, getAntdTagColor
} from '@/utils/enums';

import PageHeader from '@/components/PageHeader';
import './style.less';

import ScriptPage from '@/views/aid/aidscript';
import EpisodePage from '@/views/aid/aidcomicepisode';
import StoryboardPage from '@/views/aid/storyboard';
import SceneCpPage from '@/views/aid/scenecp';
import SceneFormPage from '@/views/aid/rolepropsceneform';
import GenRecordPage from '@/views/aid/genrecord';
import EditorPage from '@/views/aid/pisodeeditor';
import AudioPage from '@/views/aid/audioasset';
import ExtractPage from '@/views/aid/extracttask';
import AssetUserPage from '@/views/aid/assetuser';

interface Props {
  project: any;
  onBack: () => void;
}

/**
 * 项目工作台：以项目为主线，左侧按制作环节导航，右侧展示该项目维度的数据。
 * - 电视剧(series) 显示「剧集」环节；电影(movie) 不显示。
 * - 各环节复用既有列表页（按 projectId 过滤）；用户参考资产按项目所属用户(userId) 过滤。
 */
export default function ProjectWorkspaceView({ project, onBack }: Props) {
  const projectId = project?.id;
  const userId = project?.userId;
  const isSeries = project?.projectType === 'series';

  const sections = useMemo(() => {
    const list: Array<{ key: string; icon: React.ReactNode; label: string; render: () => React.ReactNode }> = [
      { key: 'overview', icon: <ProfileOutlined />, label: '项目概览', render: () => <Overview project={project} /> },
      { key: 'script', icon: <FileTextOutlined />, label: '剧本', render: () => <ScriptPage scope={{ projectId }} /> }
    ];
    if (isSeries) {
      list.push({ key: 'episode', icon: <OrderedListOutlined />, label: '剧集', render: () => <EpisodePage scope={{ projectId }} /> });
    }
    list.push(
      { key: 'storyboard', icon: <AppstoreOutlined />, label: '分镜', render: () => <StoryboardPage scope={{ projectId }} /> },
      { key: 'scenecp', icon: <ApartmentOutlined />, label: '角色/道具/场景', render: () => <SceneCpPage scope={{ projectId }} /> },
      { key: 'sceneform', icon: <PictureOutlined />, label: '场景形态图', render: () => <SceneFormPage scope={{ projectId }} /> },
      { key: 'genrecord', icon: <ThunderboltOutlined />, label: '生成记录', render: () => <GenRecordPage scope={{ projectId }} /> },
      { key: 'editor', icon: <VideoCameraOutlined />, label: '视频成片', render: () => <EditorPage scope={{ projectId }} /> },
      { key: 'audio', icon: <SoundOutlined />, label: '音频资产', render: () => <AudioPage scope={{ projectId }} /> },
      { key: 'extract', icon: <PlayCircleOutlined />, label: '提取任务', render: () => <ExtractPage scope={{ projectId }} /> },
      { key: 'userasset', icon: <UserOutlined />, label: '用户参考资产', render: () => <AssetUserPage scope={{ userId }} /> }
    );
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, userId, isSeries]);

  const [active, setActive] = useState('overview');
  const activeSection = sections.find((s) => s.key === active) || sections[0];

  return (
    <div className="project-workspace">
      {/* 项目头部 */}
      <Card bordered={false} className="page-card project-workspace__header-card">
        <PageHeader
          style={{ marginBottom: 0 }}
          title={
            <>
              {project?.coverUrl
                ? <Avatar shape="square" size={56} src={project.coverUrl} />
                : <Avatar shape="square" size={56} icon={<VideoCameraOutlined />} className="project-workspace__avatar" />}
              <span className="project-workspace__title">{project?.projectName || '未命名项目'}</span>
              <Tag color={project?.projectType === 'movie' ? 'purple' : 'blue'}>{getLabelByValue(PROJECT_TYPE_OPTIONS, project?.projectType)}</Tag>
              <Tag color={getAntdTagColor(PROJECT_STATUS_OPTIONS, project?.status)}>{getLabelByValue(PROJECT_STATUS_OPTIONS, project?.status)}</Tag>
            </>
          }
          desc={`项目ID：${projectId} · 用户ID：${userId} · 当前步骤：${getLabelByValue(CURRENT_STEP_WITH_MOVIE_OPTIONS, project?.currentStep)}`}
          extra={<Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回项目列表</Button>}
        />
      </Card>

      {/* 左导航 + 右内容 */}
      <div className="project-workspace__body">
        <Card bordered={false} className="page-card project-workspace__nav">
          <Menu
            mode="inline"
            selectedKeys={[active]}
            onClick={({ key }) => setActive(key)}
            items={sections.map((s) => ({ key: s.key, icon: s.icon, label: s.label }))}
          />
        </Card>
        <div className="project-workspace__content">
          {activeSection.render()}
        </div>
      </div>
    </div>
  );
}

/** 概览：展示项目基础信息 */
function Overview({ project }: { project: any }) {
  const rows: Array<[string, React.ReactNode]> = [
    ['项目名称', project?.projectName || '-'],
    ['项目描述', project?.projectDesc || '-'],
    ['项目类型', getLabelByValue(PROJECT_TYPE_OPTIONS, project?.projectType)],
    ['画面比例', project?.aspectRatio || '-'],
    ['当前步骤', getLabelByValue(CURRENT_STEP_WITH_MOVIE_OPTIONS, project?.currentStep)],
    ['状态', getLabelByValue(PROJECT_STATUS_OPTIONS, project?.status)],
    ['所属用户', project?.userId ?? '-'],
    ['是否公开', project?.isPublic === 'Y' ? '是' : '否']
  ];
  return (
    <Card bordered={false} className="page-card" title="项目概览">
      <Descriptions
        bordered
        column={2}
        size="small"
        items={rows.map(([k, v]) => ({ key: k, label: k, children: v }))}
      />
    </Card>
  );
}
