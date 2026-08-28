// 用户相关类型（基础字段 + /api/user/profile 扩展信息）
export interface User {
  id: string
  username: string
  email: string
  avatar?: string
  role: 'admin' | 'user'
  nickName?: string
  phonenumber?: string
  balance?: number | null
  frozenBalance?: number | null
  memberLevel?: string | null
  memberLevelName?: string | null
  memberExpireTime?: string | null
  totalRecharge?: number | null
  totalConsumption?: number | null
  isReal?: boolean | null
  realName?: string | null
  idCard?: string | null
}

// 应用状态类型
export interface AppState {
  theme: 'light' | 'dark'
  sidebarCollapsed: boolean
}

// 创作流程步骤
export type CreationStep =
  | 'global-setting'
  | 'story-script'
  | 'scene-character'
  | 'storyboard-script'
  | 'storyboard-video'
  | 'dubbing'
  | 'preview'

// 作品类型
export interface Work {
  id: string
  title: string
  description: string
  coverImage: string
  author: User
  createdAt: string
  updatedAt: string
  status: 'draft' | 'in-progress' | 'completed'
  views: number
  likes: number
  tags: string[]
  currentStep: CreationStep
}

// 资产类型
export type AssetType = 'character' | 'scene' | 'prop' | 'bgm' | 'voice'

export interface Asset {
  id: string
  name: string
  type: AssetType
  thumbnail: string
  createdAt: string
  isPublic: boolean
  author: User
}

// 步骤数据
export interface GlobalSettingData {
  // 基础信息
  title: string
  genre: string
  style: string
  description: string
  // 画面比例
  aspectRatio: '16:9' | '9:16' | '4:3' | '3:4' | '1:1' | '21:9'
  // 剧本类型（ScriptTypeEnum）
  scriptType: 'plot' | 'monologue'
  // 模型策略（GenModeEnum）
  modelStrategy: 'economy' | 'performance'
  // 创作模式（CreationModeEnum）
  creationMode: 'i2v' | 'multi' | 'pro' | 'auto_grid'
  // 画面风格
  selectedStyle: {
    id: string
    name: string
    thumbnail: string
    /** 合并风格接口的原始数值 ID，仅用于创建/切换项目风格 */
    assetId?: number
    /** 合并风格接口来源：official 官方 / custom 用户自定义 */
    sourceFlag?: 'official' | 'custom'
    /** 与 official/query 的 assetName 一致，提交作品时写入 videoStyleType */
    assetName?: string
    /** 与 official/query 的 promptText 一致，提交作品时写入 videoStyleValue */
    promptText?: string | null
  } | null
  /** 仅前端草稿使用：用户是否主动选择了另一风格，不随项目请求提交 */
  styleSelectionTouched?: boolean
  /** 项目已有角色/场景/道具时由详情接口返回，锁定后仍允许保存其它配置 */
  styleLocked?: boolean
  // 我的风格库
  myStyles: Array<{
    id: string
    name: string
    thumbnail: string
  }>
}

export interface StoryScriptData {
  content: string
}

export interface SceneCharacterData {
  characters: string[]
  scenes: string[]
  props: string[]
}

export interface StoryboardScriptData {
  panels: StoryboardPanel[]
}

export interface StoryboardPanel {
  id: string
  title: string
  description?: string
  duration?: number
  images?: any[]
  /** 列表接口 `finalImageUrl`，与 `images` 中 isSelected=1 的主图一致 */
  finalImageUrl?: string | null
  /**
   * 列表接口 `finalVideoUrl`：配音前原视频（不含 compose 配音合成视频）
   */
  finalVideoUrl?: string | null
  /** 列表接口 `finalVideoId`，与分镜视频生成记录 isSelected=1 一致 */
  finalVideoId?: number | null
  /**
   * 列表接口 `finalComposeVideoUrl`：配音视频主视频（compose 类 is_selected=1）
   */
  finalComposeVideoUrl?: string | null
  /** 列表接口 `referenceImages`，最终分镜图引用的参考图快照 */
  referenceImages?: import('~/types/business-api').StoryboardReferenceImageSnapshot[]
  scriptContent?: string
  /** 工作台 `dialogueText`，同步到配音步骤 `dialogue` */
  dialogueText?: string | null
  /** 列表接口 `subtitleText`，成品预览字幕轨唯一来源（勿用 dialogueText 兜底） */
  subtitleText?: string | null
  /** 列表/详情 `voiceType`：narration / dialogue / mixed */
  voiceType?: string | null
  /** 列表/详情 `speakerRoles` */
  speakerRoles?: string[] | null
  /** 列表/详情 `speakerVoices` */
  speakerVoices?: import('~/types/business-api').UserStoryboardSpeakerVoice[] | null
  /** 列表/详情 `audioStatus`：SUCCEEDED / PROCESSING / FAILED / NONE */
  audioStatus?: string | null
}

/** 分镜视频单条（第五步） */
export interface StoryboardVideoPanel {
  id: string
  title: string
  /** 生视频模式：如 多参 */
  videoMode?: string
  /** 详情描述 */
  detailDescription?: string
  /**
   * 列表接口 `finalVideoUrl` 快照：配音前原视频 URL（双轨语义下与 compose 配音视频分离）
   */
  finalVideoUrl?: string | null
  /** 右侧保存的视频列表（编辑分镜适配弹窗内同步） */
  videos?: Array<{ id: string; url?: string; title?: string; [key: string]: any }>
  /** 自动生成中 */
  generating?: boolean
  /** 自动生成失败时的错误信息，有则展示「重新生成」 */
  generateError?: string
}

export interface StoryboardVideoData {
  panels: StoryboardVideoPanel[]
}

/** 音画同步单条（分镜配音 N） */
export interface DubbingPanel {
  id: string
  /** 如：分镜配音1: 分镜1-1 */
  title: string
  /** 台词（工作台 dialogueText 原文，编辑用） */
  dialogue?: string
  /** 字幕展示文本（列表 subtitleText）；成品预览字幕轨唯一来源 */
  subtitleText?: string | null
  /** 配音类型：旁白/画外音 等 */
  dubbingType?: string
  /** 发言角色 */
  speakerRole?: string
  /** 未配音 | 已配音 */
  status?: 'pending' | 'done'
  /** 编辑分镜配音：配音音色展示名 */
  dubbingVoiceName?: string
  /** 音色头像（选音色弹窗） */
  dubbingVoiceAvatarUrl?: string
  /** 情感：中性、高兴 等 */
  dubbingEmotion?: string
  /** 是否对口型 */
  dubbingLipSync?: boolean
  /** 用户已在弹窗内确认「设置分镜音画同步结果」（与 status/dialogue 一起用于展示「取消设置」） */
  storyboardDubbingConfirmed?: boolean
  /** 用户本地上传配音文件经 OSS 后的地址（对口型流程用） */
  dubbingUploadedAudioUrl?: string
  /** 配音生成后的对口型视频地址（优先于分镜原视频展示） */
  dubbingLipSyncVideoUrl?: string
  /** 当前设为对口型的条目标识：'__source__'=原分镜视频，否则=生成记录的 id，用于区分同 URL 的多条 */
  dubbingLipSyncKey?: string
  /** 本分镜的生成历史（弹窗右侧列表），批量生成与弹窗内「开始配音」均追加到此列表 */
  dubbingGenHistory?: Array<{
    id: string
    url: string
    title: string
    dialogue: string
    voiceName: string
    emotion: string
  }>
}

export interface DubbingData {
  voiceActors: string[]
  bgm: string
  /** 音画同步列表（与分镜脚本/分镜视频一一对应） */
  panels: DubbingPanel[]
}

// 作品数据
export interface WorkData {
  globalSetting: GlobalSettingData
  storyScript: StoryScriptData
  sceneCharacter: SceneCharacterData
  storyboardScript: StoryboardScriptData
  storyboardVideo: StoryboardVideoData
  dubbing: DubbingData
}

// 计数器状态
export interface CounterState {
  count: number
}
