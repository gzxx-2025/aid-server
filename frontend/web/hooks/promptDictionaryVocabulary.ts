/** 原从 `~/components/steps/SettingSelectField.vue` 导出；组件迁移前由本文件承载同一类型契约 */
export interface SettingSelectOption {
  key: string
  value: string
  image?: string
}

export const NONE: SettingSelectOption = { key: 'none', value: '无', image: '' }

/** 与接口文档 `promptType` 一致；UI「摄影技法」对应 exposure_blur，「特殊拍摄手法」对应 shooting_technique */
export const PROMPT_TYPE = {
  style: 'style',
  composition: 'composition',
  shot_size: 'shot_size',
  camera_angle: 'camera_angle',
  focal_length: 'focal_length',
  color_tone: 'color_tone',
  lighting: 'lighting',
  exposure_blur: 'exposure_blur',
  camera_movement: 'camera_movement',
  shooting_technique: 'shooting_technique'
} as const

/**
 * 仅用于旧数据迁移：根据旧版英文 key 得到展示名，再与接口返回的 promptName 匹配。
 * 不在 UI 中作为选项列表展示。
 */
const LEGACY_LABEL_BY_PROMPT_TYPE: Record<string, Record<string, string>> = {
  [PROMPT_TYPE.composition]: {
    none: '无',
    rule_of_thirds: '三分法构图',
    centered: '居中构图',
    diagonal: '对角线构图',
    framing: '框架构图',
    leading_lines: '引导线构图',
    symmetrical: '对称构图',
    golden_ratio: '黄金分割构图',
    triangular: '三角形构图'
  },
  [PROMPT_TYPE.shot_size]: {
    none: '无',
    extreme_close_up: '大特写',
    close_up: '特写',
    medium_close_up: '近景',
    medium_shot: '中景',
    medium_full: '中全景',
    full_shot: '全景',
    long_shot: '远景',
    extreme_long_shot: '大远景'
  },
  [PROMPT_TYPE.camera_angle]: {
    none: '无',
    eye_level: '平视',
    high_angle: '俯拍',
    low_angle: '仰拍',
    bird_eye: '鸟瞰',
    worm_eye: '虫视',
    over_shoulder: '过肩',
    side_shot: '侧拍',
    dutch_angle: '荷兰角',
    third_person: '第三人称'
  },
  [PROMPT_TYPE.focal_length]: {
    none: '无',
    fish_eye: '鱼眼',
    wide: '广角',
    standard: '标准',
    portrait: '人像',
    telephoto: '长焦',
    super_telephoto: '超长焦',
    macro: '微距',
    zoom: '变焦'
  },
  [PROMPT_TYPE.color_tone]: {
    none: '无',
    warm: '暖色',
    cool: '冷色',
    neutral: '中性',
    high_saturation: '高饱和',
    low_saturation: '低饱和',
    monochrome: '单色',
    complementary: '互补色',
    analogous: '类似色'
  },
  [PROMPT_TYPE.lighting]: {
    none: '无',
    natural: '自然光',
    soft: '柔光',
    hard: '硬光',
    backlight: '逆光',
    side_light: '侧光',
    rim_light: '轮廓光',
    dramatic: '戏剧光',
    golden_hour: '黄金时刻'
  },
  [PROMPT_TYPE.exposure_blur]: {
    none: '无',
    long_exposure: '长曝光',
    multiple_exposure: '多重曝光',
    macro: '微距摄影',
    tilt_shift: '移轴摄影',
    high_speed: '高速快门定格',
    shallow_dof: '浅景深虚化',
    reflection: '反射/镜面拍摄',
    silhouette: '剪影拍摄'
  },
  [PROMPT_TYPE.camera_movement]: {
    none: '无',
    fixed: '固定机位',
    follow: '跟拍',
    orbit: '环绕',
    zoom_in: '变焦拉近',
    zoom_out: '变焦拉远',
    pan_left: '镜头左摇',
    pan_right: '镜头右摇',
    tilt_up: '镜头上仰',
    tilt_down: '镜头下俯',
    dolly_in: '镜头前移',
    dolly_out: '镜头后移'
  },
  [PROMPT_TYPE.shooting_technique]: {
    none: '无',
    hitchcock_zoom: '希区柯克变焦',
    time_lapse: '延时摄影',
    quick_push: '急推镜头',
    quick_pull: '急拉镜头',
    whip_pan: '快速甩镜',
    bullet_time: '子弹时间',
    fpv: 'FPV 穿梭',
    macro: '微距特写',
    first_person: '第一人称',
    slow_motion: '慢镜头',
    probe: '探针镜头',
    dutch_roll: '旋转倾斜镜头'
  }
}

export function legacyLabelForKey(promptType: string, key: string): string | undefined {
  return LEGACY_LABEL_BY_PROMPT_TYPE[promptType]?.[key]
}
