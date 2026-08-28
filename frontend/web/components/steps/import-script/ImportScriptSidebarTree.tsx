'use client'

import type { AssetCenterCategoryTreeVO } from '~/types/business-api'
import { episodeDisplayLabel } from '~/utils/importAssetModalQuery'
import { assetUrl } from '~/utils/assetUrl'
import fileWhiteIconMod from '@/assets/img/icon/file-white.svg'
import fileWhiteSelIconMod from '@/assets/img/icon/flie-white-sel.svg'

const fileWhiteIcon = assetUrl(fileWhiteIconMod)
const fileWhiteSelIcon = assetUrl(fileWhiteSelIconMod)

export type ImportScriptTab = 'current' | 'history' | 'material' | 'shared'

export interface MaterialCategory {
  key: string
  label: string
}

// 素材库分类
export const MATERIAL_CATEGORIES: MaterialCategory[] = [
  { key: 'scene', label: '场景库' },
  { key: 'character', label: '角色库' },
  { key: 'prop', label: '道具库' },
  { key: 'file', label: '文件库' },
  { key: 'pose', label: '姿势库' },
  { key: 'effect', label: '特效库' },
  { key: 'expression', label: '表情库' },
  { key: 'draft', label: '手绘稿库' },
  { key: 'misc', label: '其他素材库' },
  { key: 'style', label: '风格库' }
]

export interface ImportScriptSidebarTreeProps {
  activeTab: ImportScriptTab
  displayProjectId: string
  currentProject: { id: string; name: string }
  currentEpisodeCategories: AssetCenterCategoryTreeVO[]
  assetCenterTree: AssetCenterCategoryTreeVO[]
  assetCenterTreeLoading: boolean
  expandedNodes: Record<string, boolean>
  selectedNode: string | null
  onToggleNode: (key: string) => void
  onProjectClick: (key: string, project?: any) => void
  onSelectCurrentCategory: (cat: AssetCenterCategoryTreeVO) => void
  onHistoryProjectClick: (project: AssetCenterCategoryTreeVO) => void
  onHistoryEpisodeClick: (
    project: AssetCenterCategoryTreeVO,
    episode: AssetCenterCategoryTreeVO
  ) => void
  onSelectHistoryCategory: (
    project: AssetCenterCategoryTreeVO,
    episode: AssetCenterCategoryTreeVO,
    cat: AssetCenterCategoryTreeVO
  ) => void
  onSelectMaterialLibrary: () => void
  onSelectMaterialCategory: (category: MaterialCategory) => void
}

/** 导入弹窗左侧文档结构树（本作品 / 历史作品 / 素材库 / 共享） */
export function ImportScriptSidebarTree(props: ImportScriptSidebarTreeProps) {
  const {
    activeTab,
    displayProjectId,
    currentProject,
    currentEpisodeCategories,
    assetCenterTree,
    assetCenterTreeLoading,
    expandedNodes,
    selectedNode
  } = props

  return (
    <div className="document-tree">
      {/* 根据标签页显示不同的树结构 */}

      {/* 本作品资产：分类树中当前项目 → 当前剧集 → 分类 */}
      {activeTab === 'current' ? (
        !displayProjectId ? (
          <p className="tree-empty-hint">暂无作品 ID，请从创作流程进入或先创建作品</p>
        ) : assetCenterTreeLoading ? (
          <p className="tree-empty-hint">加载分类树…</p>
        ) : !currentEpisodeCategories.length ? (
          <p className="tree-empty-hint">暂无本作品资产</p>
        ) : (
          <div className="tree-item">
            <div
              className={`tree-node${
                selectedNode === `project-${displayProjectId}` ? ' active' : ''
              }`}
              onClick={(e) => {
                e.stopPropagation()
                props.onProjectClick(`project-${displayProjectId}`, currentProject)
              }}
            >
              <span
                className="tree-icon"
                onClick={(e) => {
                  e.stopPropagation()
                  props.onToggleNode(`project-${displayProjectId}`)
                }}
              >
                {expandedNodes[`project-${displayProjectId}`] ? '▼' : '▶'}
              </span>
              <span className="tree-label">{currentProject.name}</span>
            </div>
            {expandedNodes[`project-${displayProjectId}`] ? (
              <div className="tree-children">
                {currentEpisodeCategories.map((cat) => (
                  <div
                    key={`tree-cur-${cat.categoryCode}`}
                    className={`tree-item${
                      selectedNode === `${displayProjectId}-${cat.categoryCode}` ? ' active' : ''
                    }`}
                    onClick={() => props.onSelectCurrentCategory(cat)}
                  >
                    <div className="tree-node leaf">
                      <img
                        className="tree-icon"
                        src={
                          selectedNode === `${displayProjectId}-${cat.categoryCode}`
                            ? fileWhiteSelIcon
                            : fileWhiteIcon
                        }
                        alt=""
                      />
                      <span className="tree-label">{cat.categoryName || cat.categoryCode}</span>
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        )
      ) : activeTab === 'history' ? (
        /* 历史作品资产：资产中心分类树（项目→剧集→分类） */
        assetCenterTreeLoading ? (
          <p className="tree-empty-hint">加载分类树…</p>
        ) : !assetCenterTree.length ? (
          <p className="tree-empty-hint">暂无历史作品资产</p>
        ) : (
          <>
            {assetCenterTree.map((project) => (
              <div key={`tree-p-${project.projectId}`} className="tree-item">
                <div
                  className={`tree-node${
                    selectedNode === `project-${project.projectId}` ? ' active' : ''
                  }`}
                  onClick={(e) => {
                    e.stopPropagation()
                    props.onHistoryProjectClick(project)
                  }}
                >
                  <span
                    className="tree-icon"
                    onClick={(e) => {
                      e.stopPropagation()
                      props.onToggleNode(`project-${project.projectId}`)
                    }}
                  >
                    {expandedNodes[`project-${project.projectId}`] ? '▼' : '▶'}
                  </span>
                  <span className="tree-label">
                    {project.projectName || `项目${project.projectId}`}
                  </span>
                </div>
                {expandedNodes[`project-${project.projectId}`] ? (
                  <div className="tree-children">
                    {(project.children || []).map((episode) => (
                      <div
                        key={`tree-e-${project.projectId}-${episode.episodeId ?? 0}`}
                        className="tree-item"
                      >
                        <div
                          className={`tree-node${
                            selectedNode ===
                            `episode-${project.projectId}-${episode.episodeId ?? 0}`
                              ? ' active'
                              : ''
                          }`}
                          onClick={(e) => {
                            e.stopPropagation()
                            props.onHistoryEpisodeClick(project, episode)
                          }}
                        >
                          <span
                            className="tree-icon"
                            onClick={(e) => {
                              e.stopPropagation()
                              props.onToggleNode(
                                `episode-${project.projectId}-${episode.episodeId ?? 0}`
                              )
                            }}
                          >
                            {expandedNodes[
                              `episode-${project.projectId}-${episode.episodeId ?? 0}`
                            ]
                              ? '▼'
                              : '▶'}
                          </span>
                          <span className="tree-label">{episodeDisplayLabel(episode)}</span>
                        </div>
                        {expandedNodes[
                          `episode-${project.projectId}-${episode.episodeId ?? 0}`
                        ] ? (
                          <div className="tree-children">
                            {(episode.children || []).map((cat) => (
                              <div
                                key={`tree-c-${project.projectId}-${episode.episodeId ?? 0}-${cat.categoryCode}`}
                                className={`tree-item${
                                  selectedNode ===
                                  `${project.projectId}-${episode.episodeId ?? 0}-${cat.categoryCode}`
                                    ? ' active'
                                    : ''
                                }`}
                                onClick={() =>
                                  props.onSelectHistoryCategory(project, episode, cat)
                                }
                              >
                                <div className="tree-node leaf">
                                  <img
                                    className="tree-icon"
                                    src={
                                      selectedNode ===
                                      `${project.projectId}-${episode.episodeId ?? 0}-${cat.categoryCode}`
                                        ? fileWhiteSelIcon
                                        : fileWhiteIcon
                                    }
                                    alt=""
                                  />
                                  <span className="tree-label">
                                    {cat.categoryName || cat.categoryCode}
                                  </span>
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
          </>
        )
      ) : activeTab === 'material' ? (
        /* 素材库：显示素材库树 */
        <div className="tree-item">
          <div
            className={`tree-node${selectedNode === 'material-library' ? ' active' : ''}`}
            onClick={props.onSelectMaterialLibrary}
          >
            <span className="tree-icon">{expandedNodes['material-library'] ? '▼' : '▶'}</span>
            <span className="tree-label">素材库</span>
          </div>
          {expandedNodes['material-library'] ? (
            <div className="tree-children">
              {MATERIAL_CATEGORIES.map((category) => (
                <div
                  key={category.key}
                  className={`tree-item${
                    selectedNode === `material-${category.key}` ? ' active' : ''
                  }`}
                  onClick={() => props.onSelectMaterialCategory(category)}
                >
                  <div className="tree-node leaf">
                    <img
                      className="tree-icon"
                      src={
                        selectedNode === `material-${category.key}`
                          ? fileWhiteSelIcon
                          : fileWhiteIcon
                      }
                      alt=""
                    />
                    <span className="tree-label">{category.label}</span>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      ) : activeTab === 'shared' ? (
        /* 共享给我的资产：显示共享资产树 */
        <div className="tree-item">
          <div className="tree-node" onClick={() => props.onToggleNode('shared-assets')}>
            <span className="tree-icon">{expandedNodes['shared-assets'] ? '▼' : '▶'}</span>
            <span className="tree-label">共享给我的资产</span>
          </div>
          {expandedNodes['shared-assets'] ? (
            <div className="tree-children">
              <div className="tree-item">
                <div className="tree-node leaf">
                  <img className="tree-icon" src={fileWhiteIcon} alt="" />
                  <span className="tree-label">共享文件夹</span>
                </div>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

export default ImportScriptSidebarTree
