'use client'

import { StoryboardModalTabThumbnail } from '~/components/common/StoryboardModalTabThumbnail'

export interface DubbingSceneTabItem {
  id: string
  name: string
  coverImageUrl: string
  videoUrl: string
}

export interface DubbingSceneTabsProps {
  sceneItems: DubbingSceneTabItem[]
  currentSceneIndex: number
  isSceneGenerating: (index: number) => boolean
  onSwitchScene: (index: number) => void
}

/** 顶部分镜切换 Tab 列表（缩略图与视频弹窗共用 StoryboardModalTabThumbnail） */
export function DubbingSceneTabs({
  sceneItems,
  currentSceneIndex,
  isSceneGenerating,
  onSwitchScene
}: DubbingSceneTabsProps) {
  return (
    <>
      {sceneItems.map((item, index) => (
        <div
          key={item.id}
          className={[
            'scene-image-tab',
            'scene-image-tab--dubbing',
            currentSceneIndex === index ? 'active' : ''
          ]
            .filter(Boolean)
            .join(' ')}
          onClick={() => onSwitchScene(index)}
        >
          <div className="scene-image-thumbnail">
            <StoryboardModalTabThumbnail
              generating={isSceneGenerating(index)}
              coverImageUrl={item.coverImageUrl}
              videoUrl={item.videoUrl}
              isActive={index === currentSceneIndex}
            />
          </div>
          <span className="scene-label scene-label--dubbing">{item.name}</span>
        </div>
      ))}
    </>
  )
}
