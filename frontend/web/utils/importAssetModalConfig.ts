import { CREATION_FLOW_STEP_TITLE_MAP } from '~/utils/createFlowStepMeta';

/** 素材库左侧/网格：与 ImportScriptModal materialCategories 一致 */
export const MATERIAL_CATEGORY_ROWS: Array<{ key: string; label: string; apiType: string }> = [
  { key: 'scene', label: '场景库', apiType: 'reference_scene' },
  { key: 'character', label: '角色库', apiType: 'reference_character' },
  { key: 'prop', label: '道具库', apiType: 'reference_prop' },
  { key: 'file', label: '文件库', apiType: 'file' },
  { key: 'pose', label: '姿势库', apiType: 'pose' },
  { key: 'effect', label: '特效库', apiType: 'effect' },
  { key: 'expression', label: '表情库', apiType: 'expression' },
  /** 接口暂无独立类型时与 file 共用统计 */
  { key: 'draft', label: '手绘稿库', apiType: 'file' },
  { key: 'misc', label: '其他素材库', apiType: 'file' },
  { key: 'style', label: '风格库', apiType: 'style' }
]

/** 本作品资产：文档节点 → center/list 的 categoryCode / assetType */
export const DOCUMENT_STRUCTURE: Array<{ key: string; label: string; apiType: string }> = [
  { key: 'global-setting', label: CREATION_FLOW_STEP_TITLE_MAP['global-setting'], apiType: 'file' },
  { key: 'story-script', label: CREATION_FLOW_STEP_TITLE_MAP['story-script'], apiType: 'file' },
  { key: 'scene-setting', label: '场景设定', apiType: 'scene' },
  { key: 'character-setting', label: '角色设定', apiType: 'character' },
  { key: 'prop-setting', label: '道具设定', apiType: 'prop' },
  { key: 'scene-image', label: '场景图', apiType: 'scene' },
  { key: 'character-image', label: '角色图', apiType: 'character' },
  { key: 'prop-image', label: '道具图', apiType: 'prop' },
  { key: 'storyboard-script', label: CREATION_FLOW_STEP_TITLE_MAP['storyboard-script'], apiType: 'file' },
  { key: 'storyboard-image', label: '分镜图', apiType: 'file' },
  { key: 'storyboard-video', label: CREATION_FLOW_STEP_TITLE_MAP['storyboard-video'], apiType: 'file' },
  { key: 'dubbing', label: CREATION_FLOW_STEP_TITLE_MAP.dubbing, apiType: 'file' },
  { key: 'preview', label: CREATION_FLOW_STEP_TITLE_MAP.preview, apiType: 'file' }
]

/** 资产中心 categoryCode → center/list 可选 assetType */
export const CENTER_CATEGORY_TO_ASSET_TYPE: Record<string, string> = {
  script: 'file',
  role: 'character',
  scene: 'scene',
  prop: 'prop',
  role_setting: 'character',
  scene_setting: 'scene',
  prop_setting: 'prop',
  role_image: 'character',
  scene_image: 'scene',
  prop_image: 'prop',
  storyboard_script: 'file',
  storyboard_image: 'file',
  storyboard_video: 'file',
  dubbing: 'file',
  preview_video: 'file',
  global_setting: 'file'
}

/** 接口分类兜底（与 center/category/tree 出参 categoryCode 对齐） */
export const CENTER_CATEGORY_FALLBACK: Array<{ categoryCode: string; categoryName: string }> = [
  { categoryCode: 'script', categoryName: '剧本' },
  { categoryCode: 'role', categoryName: '角色' },
  { categoryCode: 'scene', categoryName: '场景' },
  { categoryCode: 'prop', categoryName: '道具' },
  { categoryCode: 'role_setting', categoryName: '角色设定' },
  { categoryCode: 'scene_setting', categoryName: '场景设定' },
  { categoryCode: 'prop_setting', categoryName: '道具设定' },
  { categoryCode: 'role_image', categoryName: '角色图' },
  { categoryCode: 'scene_image', categoryName: '场景图' },
  { categoryCode: 'prop_image', categoryName: '道具图' },
  { categoryCode: 'storyboard_script', categoryName: '分镜脚本' },
  { categoryCode: 'storyboard_image', categoryName: '分镜图' },
  { categoryCode: 'storyboard_video', categoryName: '分镜视频' },
  { categoryCode: 'dubbing', categoryName: '配音' },
  { categoryCode: 'preview_video', categoryName: '预览视频' }
]

/** 文档节点 key → API assetType */
export const DOCUMENT_KEY_TO_API_TYPE: Record<string, string> = Object.fromEntries(
  DOCUMENT_STRUCTURE.map((d) => [d.key, d.apiType])
)
