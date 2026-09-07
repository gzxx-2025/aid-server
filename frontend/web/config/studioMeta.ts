import type { StudioNodeKind, StudioZone } from '@/types/studio'

export interface StudioNodeMeta {
  label: string
  description: string
  color: string
  defaultPrompt: string
}

export const STUDIO_NODE_META: Record<StudioNodeKind, StudioNodeMeta> = {
  image: {
    label: '图片',
    description: '角色、场景、道具与分镜画面',
    color: '#72a6ba',
    defaultPrompt: '生成主体清晰、构图稳定且具有叙事感的高质量画面。'
  },
  video: {
    label: '视频',
    description: '镜头运动与动态画面结果',
    color: '#7d91c7',
    defaultPrompt: '生成动作连贯、运镜稳定、主体一致的电影感视频。'
  },
  text: {
    label: '文本',
    description: '剧本与项目配置内容',
    color: '#8f8bd9',
    defaultPrompt: ''
  },
  storyboard_script: {
    label: '分镜脚本',
    description: '镜头拆解、调度与叙事结构',
    color: '#8f8bd9',
    defaultPrompt: ''
  },
  voice: {
    label: '配音',
    description: '对白、旁白与声音预演',
    color: '#6fa99e',
    defaultPrompt: ''
  }
}

export const DEFAULT_STUDIO_ZONES: StudioZone[] = [
  ['global-setting', '01 项目配置', '创作约束与全局设置', 0, 330],
  ['story-script', '02 剧本创作', '故事与对白', 370, 330],
  ['scene-character', '03 素材准备', '角色、场景与道具', 740, 620],
  ['storyboard-script', '04 分镜脚本', '导演调度与镜头拆分', 1400, 620],
  ['storyboard-video', '05 分镜视频', '画面与视频结果', 2060, 620],
  ['dubbing', '06 音画同步', '配音、字幕与声音', 2720, 330],
  ['preview', '07 成品预览', '交付检查与成片衔接', 3090, 330]
].map(([id, title, caption, x, width]) => ({
  id: String(id),
  title: String(title),
  caption: String(caption),
  color: '#8d96a5',
  position: { x: Number(x), y: 0 },
  width: Number(width),
  height: 690,
  collapsed: false
}))
