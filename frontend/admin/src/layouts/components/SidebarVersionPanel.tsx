import React, { useEffect, useState } from 'react';
import { Button, Popover, Spin, Tooltip } from 'antd';
import {
  CheckCircleFilled,
  CloudDownloadOutlined,
  DownOutlined,
  ExclamationCircleFilled,
  GithubOutlined,
  LinkOutlined,
  ReloadOutlined,
  RightOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

import { useUpgradeStore } from '@/store/useUpgradeStore';

interface Props {
  collapsed: boolean;
}

type VersionState = 'current' | 'update' | 'error';

/**
 * 品牌区版本入口。
 *
 * 只允许点击触发，避免 hover 与 click 同时控制浮层时出现闪退、误跳转；
 * 版本详情与升级页共享同一状态，任一处检查更新后都会全局联动。
 */
export default function SidebarVersionPanel({ collapsed }: Props) {
  const [open, setOpen] = useState(false);
  const status = useUpgradeStore((state) => state.status);
  const checking = useUpgradeStore((state) => state.checking);
  const loadStatus = useUpgradeStore((state) => state.loadStatus);
  const navigate = useNavigate();

  useEffect(() => {
    // 已有共享快照（如从升级页返回）时不重复请求。
    if (!useUpgradeStore.getState().status) {
      loadStatus(false).catch(() => undefined);
    }
  }, [loadStatus]);

  if (collapsed || !status) return null;

  const version = status.currentVersion ? `v${status.currentVersion}` : '-';
  const rollbackCount = status.rollbackReleases?.length || 0;
  const state: VersionState = status.hasUpdate ? 'update' : status.checkError ? 'error' : 'current';
  const isBeta = status.latestChannel === 'beta' || /-(beta|rc)/i.test(status.currentVersion || '');
  const channelLabel = isBeta ? 'Beta 测试版' : 'Stable 正式版';
  const statusLabel = state === 'update' ? '发现新版本' : state === 'error' ? '检查异常' : '已是最新版';
  const statusDescription = state === 'update'
    ? `新版本 v${status.latestVersion || '-'} 已发布，可前往升级中心查看更新说明。`
    : state === 'error'
      ? '暂时无法连接版本源，可重新检查或前往升级中心查看诊断信息。'
      : '当前版本运行正常，无需进行升级操作。';

  const goUpgradeCenter = () => {
    setOpen(false);
    navigate('/system/upgrade');
  };

  const content = (
    <div className={`version-popover is-${state}`} onClick={(event) => event.stopPropagation()}>
      <div className="version-popover__header">
        <div>
          <div className="version-popover__eyebrow">版本状态</div>
          <div className="version-popover__caption">AID Version Center</div>
        </div>
        <Tooltip title="重新检查更新" placement="left">
          <Button
            className="version-popover__refresh"
            type="text"
            shape="circle"
            aria-label="重新检查更新"
            icon={<ReloadOutlined spin={checking} />}
            disabled={checking}
            onClick={(event) => {
              event.stopPropagation();
              loadStatus(true).catch(() => undefined);
            }}
          />
        </Tooltip>
      </div>

      <div className="version-popover__hero">
        <div className="version-popover__state-icon" aria-hidden="true">
          {state === 'current' && <CheckCircleFilled />}
          {state === 'update' && <CloudDownloadOutlined />}
          {state === 'error' && <ExclamationCircleFilled />}
        </div>
        <div className="version-popover__hero-content">
          <div className="version-popover__version-row">
            <strong>{version}</strong>
            <span className="version-popover__status-tag">{statusLabel}</span>
          </div>
          <p>{statusDescription}</p>
        </div>
      </div>

      {state === 'update' && (
        <div className="version-popover__update-note">
          <span>最新可用版本</span>
          <strong>v{status.latestVersion || '-'}</strong>
          {status.latestChannel === 'beta' && <em>Beta</em>}
        </div>
      )}

      <div className="version-popover__facts">
        <div>
          <span>发布渠道</span>
          <strong>{channelLabel}</strong>
        </div>
        <div>
          <span>可回退版本</span>
          <strong>{rollbackCount > 0 ? `${rollbackCount} 个` : '暂无'}</strong>
        </div>
      </div>

      {(status.giteeReleaseUrl || status.githubReleaseUrl || status.docsUrl) && (
        <div className="version-popover__resources">
          <span className="version-popover__resources-label">发布与帮助</span>
          <div className="version-popover__resource-links">
            {status.giteeReleaseUrl && (
              <a href={status.giteeReleaseUrl} target="_blank" rel="noreferrer" onClick={() => setOpen(false)}>
                <LinkOutlined /> Gitee
              </a>
            )}
            {status.githubReleaseUrl && (
              <a href={status.githubReleaseUrl} target="_blank" rel="noreferrer" onClick={() => setOpen(false)}>
                <GithubOutlined /> GitHub
              </a>
            )}
            {status.docsUrl && (
              <a href={status.docsUrl} target="_blank" rel="noreferrer" onClick={() => setOpen(false)}>
                <LinkOutlined /> 使用教程
              </a>
            )}
          </div>
        </div>
      )}

      <div className="version-popover__footer">
        <span>{status.checkedAt ? `最近检查 ${status.checkedAt}` : '版本状态来自官方更新源'}</span>
        <button type="button" onClick={goUpgradeCenter}>
          {state === 'update' ? '前往升级中心' : '查看升级设置'}
          <RightOutlined />
        </button>
      </div>
    </div>
  );

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomLeft"
      arrow={false}
      overlayClassName="sidebar-version-popover"
    >
      <button
        className={`sidebar-version-trigger is-${state}${open ? ' is-open' : ''}`}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`当前版本 ${version}，${statusLabel}`}
      >
        {checking ? <Spin size="small" /> : <span className="sidebar-version-trigger__dot" />}
        <span className="sidebar-version-trigger__version">{version}</span>
        {status.hasUpdate && <span className="sidebar-version-trigger__badge">可更新</span>}
        <DownOutlined className="sidebar-version-trigger__arrow" />
      </button>
    </Popover>
  );
}
