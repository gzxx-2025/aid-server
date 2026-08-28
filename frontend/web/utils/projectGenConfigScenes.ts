import type { ProjectGenConfigSceneCode } from '~/types/business-api'

export type ProjectGenConfigSceneKind = 'text' | 'image'

export interface ProjectGenConfigSceneMeta {
  sceneCode: ProjectGenConfigSceneCode | string
  label: string
  kind: ProjectGenConfigSceneKind
}

export interface ProjectGenConfigSceneGroup {
  title: string
  scenes: ProjectGenConfigSceneMeta[]
}

/** 固定 16 项，顺序与接口文档一致 */
export const PROJECT_GEN_CONFIG_SCENE_GROUPS: ProjectGenConfigSceneGroup[] = [
  {
    title: '资产提取（文字）',
    scenes: [
      { sceneCode: 'main_character_extract', label: '角色提取', kind: 'text' },
      { sceneCode: 'main_scene_extract', label: '场景提取', kind: 'text' },
      { sceneCode: 'main_prop_extract', label: '道具提取', kind: 'text' }
    ]
  },
  {
    title: '形态文案（文字）',
    scenes: [
      { sceneCode: 'main_character_form', label: '角色形态', kind: 'text' },
      { sceneCode: 'main_scene_form', label: '场景形态', kind: 'text' },
      { sceneCode: 'main_prop_form', label: '道具形态', kind: 'text' }
    ]
  },
  {
    title: '形态生图（图片）',
    scenes: [
      { sceneCode: 'main_character_image', label: '角色图', kind: 'image' },
      { sceneCode: 'main_scene_image', label: '场景图', kind: 'image' },
      { sceneCode: 'main_prop_image', label: '道具图', kind: 'image' },
      { sceneCode: 'main_character_card_image', label: '角色设定卡', kind: 'image' }
    ]
  },
  {
    title: '分镜（文字）',
    scenes: [
      { sceneCode: 'main_storyboard_script', label: '分镜脚本', kind: 'text' },
      { sceneCode: 'main_storyboard_stylist', label: '分镜图提示词', kind: 'text' },
      { sceneCode: 'main_storyboard_video_prompt', label: '视频提示词-多参', kind: 'text' },
      { sceneCode: 'main_storyboard_video_prompt_image', label: '视频提示词-图生', kind: 'text' },
      { sceneCode: 'main_storyboard_video_prompt_grid', label: '视频提示词-宫格', kind: 'text' }
    ]
  },
  {
    title: '分镜生图（图片）',
    scenes: [
      { sceneCode: 'main_storyboard_image', label: '分镜图', kind: 'image' }
    ]
  }
]

export const ALL_PROJECT_GEN_CONFIG_SCENES: ProjectGenConfigSceneMeta[] =
  PROJECT_GEN_CONFIG_SCENE_GROUPS.flatMap((g) => g.scenes)

export function isImageProjectGenScene(sceneCode: string): boolean {
  const hit = ALL_PROJECT_GEN_CONFIG_SCENES.find((s) => s.sceneCode === sceneCode)
  return hit?.kind === 'image'
}
