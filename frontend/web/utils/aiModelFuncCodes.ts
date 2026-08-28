/** 与后台 aid_ai_model_func_config.func_code 对齐（见 components/steps/接口.md） */
export const AI_MODEL_FUNC_CODE = {
  /** 分镜脚本生成设置 */
  STORYBOARD_SCRIPT: 'main_storyboard_script',
  /** 分镜图脚本（image-prompt） */
  STORYBOARD_STYLIST: 'main_storyboard_stylist',
  /** 分镜视频提示词（video-prompt，多参方向） */
  STORYBOARD_VIDEO_PROMPT: 'main_storyboard_video_prompt',
  /** 图生视频提示词（video-prompt-image，图生方向） */
  STORYBOARD_VIDEO_PROMPT_IMAGE: 'main_storyboard_video_prompt_image',
  /** 宫格视频提示词（video-prompt-grid，仅 auto_grid 创作模式） */
  STORYBOARD_VIDEO_PROMPT_GRID: 'main_storyboard_video_prompt_grid',
  /** 分镜生图 / 编辑分镜图弹窗 */
  STORYBOARD_IMAGE: 'main_storyboard_image',
  /** 多参生视频出片（generate/video） */
  STORYBOARD_VIDEO: 'main_storyboard_video',
  /** 专业版多参生视频出片（generate/video + creation_mode=pro） */
  STORYBOARD_VIDEO_MULTI_PRO: 'main_storyboard_video_multi_pro',
  /** 图生视频出片（generate/video/image，前端直传参考图） */
  STORYBOARD_VIDEO_IMAGE: 'main_storyboard_video_image',
  /** 首尾帧生视频出片（generate/video/edge） */
  STORYBOARD_VIDEO_EDGE: 'main_storyboard_video_edge',
  /** 宫格生视频出片（generate/video/grid，仅 auto_grid 创作模式） */
  STORYBOARD_VIDEO_GRID: 'main_storyboard_video_grid',
  SCENE_IMAGE: 'main_scene_image',
  CHARACTER_IMAGE: 'main_character_image',
  PROP_IMAGE: 'main_prop_image',
  IMAGE_EDIT: 'image_edit',
  IMAGE_UPSCALE: 'image_upscale',
  IMAGE_MULTI_VIEW: 'image_multi_view',
  /** 九宫格机位形态生图（v2.57.3 独立功能池） */
  IMAGE_MULTI_GRID: 'image_multi_grid',
  IMAGE_FORM_EDIT_CHAT: 'image_form_edit_chat',
  /** 角色设定卡（与 main_character_card_image 智能体 biz_category 一致） */
  CHARACTER_CARD_IMAGE: 'main_character_card_image'
} as const

/** 九宫格模型池无配置时回退到多机位池 */
export const IMAGE_MULTI_GRID_FUNC_CODE_FALLBACKS = [
  AI_MODEL_FUNC_CODE.IMAGE_MULTI_GRID,
  AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW
] as const

/** listByFunc 无配置时的备选 funcCode（按顺序尝试） */
export const STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS = [
  AI_MODEL_FUNC_CODE.STORYBOARD_IMAGE,
  'storyboard_image',
  'storyboard_image_generate'
] as const
