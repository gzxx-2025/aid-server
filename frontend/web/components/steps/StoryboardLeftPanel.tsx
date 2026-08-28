'use client'

/**
 * 分镜图生成左侧面板（原 components/steps/StoryboardLeftPanel.vue）。
 * 原组件无 props / emits，也无外部调用点（与源项目一致，保留为独立组件）。
 */

import { DownOutlined,ThunderboltOutlined } from '@ant-design/icons'
import { Button,Input } from 'antd'
import { useEffect,useState } from 'react'
import RichTextEditor from '~/components/common/RichTextEditor'
import { SettingSelectField } from '~/components/steps/SettingSelectField'
import {
PROMPT_TYPE,
resolvePromptSelection,
usePromptDictionary
} from '~/composables/usePromptDictionary'
import { htmlPlainTextLength } from '~/utils/htmlPlain'
import './StoryboardLeftPanel.css'
type SideSettingKey =
  | 'composition'
  | 'shotSize'
  | 'cameraAngle'
  | 'focalLength'
  | 'colorTone'
  | 'lighting'
  | 'technique'

type PromptSelection = { key: string; value: string } | null

export function StoryboardLeftPanel() {
  const [activeTab, setActiveTab] = useState<'generate' | 'dialogue' | 'grid'>('generate')
  const [sbPrompt, setSbPrompt] = useState('')
  const sbPromptPlainLen = htmlPlainTextLength(sbPrompt)

  const [settingsExpanded, setSettingsExpanded] = useState(false)
  const [activeSettingKey, setActiveSettingKey] = useState<SideSettingKey | null>(null)
  const [compositionDesc, setCompositionDesc] = useState('')
  const [selectedComposition, setSelectedComposition] = useState<PromptSelection>(null)
  const [selectedShotSize, setSelectedShotSize] = useState<PromptSelection>(null)
  const [selectedCameraAngle, setSelectedCameraAngle] = useState<PromptSelection>(null)
  const [selectedFocalLength, setSelectedFocalLength] = useState<PromptSelection>(null)
  const [selectedColorTone, setSelectedColorTone] = useState<PromptSelection>(null)
  const [selectedLighting, setSelectedLighting] = useState<PromptSelection>(null)
  const [selectedTechnique, setSelectedTechnique] = useState<PromptSelection>(null)

  const {
    ensureLoaded,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions
  } = usePromptDictionary()

  useEffect(() => {
    void ensureLoaded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function copyCompositionDesc() {
    const t = compositionDesc?.trim()
    if (!t) return
    void navigator.clipboard.writeText(t)
  }

  function migrate(
    cur: PromptSelection,
    opts: { key: string; value: string; image?: string }[],
    type: string,
    set: (v: PromptSelection) => void
  ) {
    if (!cur) return
    const n = resolvePromptSelection(cur, opts, type)
    if (n && (n.key !== cur.key || n.value !== cur.value)) set(n)
  }

  // 原 watch([options, selected], flush: 'post')：字典加载后迁移旧选择
  useEffect(() => {
    migrate(selectedComposition, compositionOptions, PROMPT_TYPE.composition, setSelectedComposition)
  }, [compositionOptions, selectedComposition])
  useEffect(() => {
    migrate(selectedShotSize, shotSizeOptions, PROMPT_TYPE.shot_size, setSelectedShotSize)
  }, [shotSizeOptions, selectedShotSize])
  useEffect(() => {
    migrate(selectedCameraAngle, cameraAngleOptions, PROMPT_TYPE.camera_angle, setSelectedCameraAngle)
  }, [cameraAngleOptions, selectedCameraAngle])
  useEffect(() => {
    migrate(selectedFocalLength, focalLengthOptions, PROMPT_TYPE.focal_length, setSelectedFocalLength)
  }, [focalLengthOptions, selectedFocalLength])
  useEffect(() => {
    migrate(selectedColorTone, colorToneOptions, PROMPT_TYPE.color_tone, setSelectedColorTone)
  }, [colorToneOptions, selectedColorTone])
  useEffect(() => {
    migrate(selectedLighting, lightingOptions, PROMPT_TYPE.lighting, setSelectedLighting)
  }, [lightingOptions, selectedLighting])
  useEffect(() => {
    migrate(selectedTechnique, techniqueOptions, PROMPT_TYPE.exposure_blur, setSelectedTechnique)
  }, [techniqueOptions, selectedTechnique])

  const dictSelects: Array<{
    key: SideSettingKey
    label: string
    placeholder: string
    panelTitle: string
    value: PromptSelection
    options: { key: string; value: string; image?: string }[]
    set: (v: PromptSelection) => void
  }> = [
    {
      key: 'cameraAngle',
      label: '拍摄角度',
      placeholder: '请选择拍摄角度',
      panelTitle: '选择拍摄角度',
      value: selectedCameraAngle,
      options: cameraAngleOptions,
      set: setSelectedCameraAngle
    },
    {
      key: 'focalLength',
      label: '镜头焦距',
      placeholder: '请选择镜头焦距',
      panelTitle: '选择镜头焦距',
      value: selectedFocalLength,
      options: focalLengthOptions,
      set: setSelectedFocalLength
    },
    {
      key: 'colorTone',
      label: '色彩倾向',
      placeholder: '请选择色彩倾向',
      panelTitle: '选择色彩倾向',
      value: selectedColorTone,
      options: colorToneOptions,
      set: setSelectedColorTone
    },
    {
      key: 'lighting',
      label: '光线',
      placeholder: '请选择光线',
      panelTitle: '选择光线',
      value: selectedLighting,
      options: lightingOptions,
      set: setSelectedLighting
    },
    {
      key: 'technique',
      label: '摄影技法',
      placeholder: '请选择摄影技法',
      panelTitle: '选择摄影技法',
      value: selectedTechnique,
      options: techniqueOptions,
      set: setSelectedTechnique
    }
  ]

  return (
    <div className="sb-left">
      <div className="sb-left-tabs">
        <button
          type="button"
          className={`sb-left-tab${activeTab === 'generate' ? ' active' : ''}`}
          onClick={() => setActiveTab('generate')}
        >
          生成分镜图
        </button>
        <button
          type="button"
          className={`sb-left-tab${activeTab === 'dialogue' ? ' active' : ''}`}
          onClick={() => setActiveTab('dialogue')}
        >
          对话作图
        </button>
        <button
          type="button"
          className={`sb-left-tab${activeTab === 'grid' ? ' active' : ''}`}
          onClick={() => setActiveTab('grid')}
        >
          九宫格
        </button>
      </div>

      <div className="sb-left-section">
        <div className="sb-left-header">
          <div className="sb-left-title">
            <span className="sb-badge">@分镜脚本1：</span>
            <span className="sb-name">未命名</span>
          </div>
          <Button
            size="small"
            type="primary"
            className="sb-generate-prompt"
            icon={<ThunderboltOutlined />}
          >
            生成提示词
          </Button>
        </div>

        <div className="sb-left-grid">
          <div className="sb-left-grid-col sb-left-grid-col-labels">
            <div className="sb-left-label">场景</div>
            <div className="sb-left-label">角色</div>
            <div className="sb-left-label">道具</div>
            <div className="sb-left-label">其他</div>
          </div>

          <div className="sb-left-grid-col">
            <button type="button" className="sb-import-card">
              <div className="sb-import-icon">+</div>
              <div className="sb-import-text">导入场景</div>
            </button>
            <button type="button" className="sb-import-card">
              <div className="sb-import-icon">+</div>
              <div className="sb-import-text">导入角色</div>
            </button>
            <button type="button" className="sb-import-card">
              <div className="sb-import-icon">+</div>
              <div className="sb-import-text">导入道具</div>
            </button>
            <div className="sb-other-row">
              <button type="button" className="sb-other-card">
                <div className="sb-import-icon">+</div>
                <div className="sb-import-text">姿态图</div>
              </button>
              <button type="button" className="sb-other-card">
                <div className="sb-import-icon">+</div>
                <div className="sb-import-text">表情图</div>
              </button>
              <button type="button" className="sb-other-card">
                <div className="sb-import-icon">+</div>
                <div className="sb-import-text">特效图</div>
              </button>
              <button type="button" className="sb-other-card">
                <div className="sb-import-icon">+</div>
                <div className="sb-import-text">手绘稿</div>
              </button>
            </div>
          </div>

          <div className="sb-left-grid-col sb-left-side sb-left-side--dict">
            <div className="sb-select">
              <div className="sb-select-label">构图</div>
              <SettingSelectField
                modelValue={selectedComposition}
                options={compositionOptions}
                placeholder="请选择构图"
                panelTitle="选择构图"
                open={activeSettingKey === 'composition'}
                onOpenChange={(v: boolean) => setActiveSettingKey(v ? 'composition' : null)}
                onModelValueChange={(v) => setSelectedComposition(v)}
              />
              <Input.TextArea
                value={compositionDesc}
                onChange={(e) => setCompositionDesc(e.target.value)}
                autoSize={{ minRows: 2, maxRows: 4 }}
                placeholder="请输入构图描述"
                className="sb-composition-desc"
              />
              <div className="sb-select-actions">
                <Button size="small" type="text" onClick={copyCompositionDesc}>
                  复制
                </Button>
                <Button size="small" type="text" danger onClick={() => setCompositionDesc('')}>
                  删除
                </Button>
              </div>
            </div>

            <div className="sb-select">
              <div className="sb-select-label">景别</div>
              <SettingSelectField
                modelValue={selectedShotSize}
                options={shotSizeOptions}
                placeholder="请选择景别"
                panelTitle="选择景别"
                open={activeSettingKey === 'shotSize'}
                onOpenChange={(v: boolean) => setActiveSettingKey(v ? 'shotSize' : null)}
                onModelValueChange={(v) => setSelectedShotSize(v)}
              />
            </div>

            {settingsExpanded
              ? dictSelects.map((item) => (
                  <div className="sb-select" key={item.key}>
                    <div className="sb-select-label">{item.label}</div>
                    <SettingSelectField
                      modelValue={item.value}
                      options={item.options}
                      placeholder={item.placeholder}
                      panelTitle={item.panelTitle}
                      open={activeSettingKey === item.key}
                      onOpenChange={(v: boolean) => setActiveSettingKey(v ? item.key : null)}
                      onModelValueChange={(v) => item.set(v)}
                    />
                  </div>
                ))
              : null}

            <button
              type="button"
              className="sb-expand"
              onClick={() => setSettingsExpanded((v) => !v)}
            >
              <span>{settingsExpanded ? '收起' : '展开'}</span>
              <DownOutlined className={settingsExpanded ? 'sb-expand-icon--open' : ''} />
            </button>
          </div>
        </div>

        <div className="sb-prompt">
          <RichTextEditor
            value={sbPrompt}
            onChange={setSbPrompt}
            minHeight="160px"
            maxLength={3000}
            placeholder="描述想要生成的画面，如：一只可爱的猫咪"
          />
          <div className="sb-char-count">{sbPromptPlainLen}/3000</div>
        </div>

        <div className="sb-bottom">
          <div className="sb-bottom-row">
            <div className="sb-pill">即梦5.0lite</div>
            <div className="sb-pill">16:9</div>
            <div className="sb-pill">1张</div>
            <div className="sb-pill">3k</div>
          </div>
          <Button type="primary" block className="sb-generate-btn">
            开始生图
          </Button>
        </div>
      </div>
    </div>
  )
}

export default StoryboardLeftPanel
