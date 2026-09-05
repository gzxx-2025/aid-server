import { describe, expect, it } from 'vitest'
import storyScriptSource from '../components/steps/StoryScript.tsx?raw'
import autoSaveHookSource from '../hooks/useStoryScriptAutoSave.ts?raw'
import { readCssImportGraph } from './testSupport/readCssImportGraph'

const cssSource = readCssImportGraph(
  new URL('../components/steps/story-script.css', import.meta.url)
)

describe('story script agent toolbar button', () => {
  it('uses the design agent icon and AI写剧本 label', () => {
    expect(storyScriptSource).toContain('assets/img/home/agent-icon.svg')
    expect(storyScriptSource).toContain('AI写剧本')
    expect(storyScriptSource).not.toContain('RobotOutlined')
    expect(storyScriptSource).not.toMatch(/>\s*Agent\s*</)
  })

  it('hides the AI写剧本 button while the dialogue panel is open', () => {
    expect(storyScriptSource).toContain('agent-btn-slot')
    expect(storyScriptSource).toMatch(/agentOpen \? ' is-collapsed'/)
    expect(storyScriptSource).not.toMatch(/onAgentToggle && !agentOpen/)
  })

  it('collapses the agent button with the same easing as the dialogue panel', () => {
    expect(cssSource).toMatch(/\.agent-btn-slot/)
    expect(cssSource).toMatch(/0\.42s cubic-bezier\(0\.16,\s*1,\s*0\.3,\s*1\)/)
    expect(cssSource).toMatch(/\.agent-btn-slot\.is-collapsed/)
  })

  it('shares the outline button chrome with import and history actions', () => {
    expect(cssSource).toMatch(
      /\.import-btn,\s*\.story-script \.toolbar-right \.history-btn,\s*\.story-script \.toolbar-right \.agent-btn\s*\{[^}]*border:\s*1px\s+solid\s+#2f3949[^}]*background:\s*transparent/s
    )
    expect(cssSource).not.toMatch(/\.agent-btn\s*\{[^}]*color:\s*#b7c1ce/)
    expect(cssSource).not.toMatch(/\.agent-btn:hover[\s\S]*?background:\s*rgba\(74,\s*231,\s*253/)
  })

  it('does not render inline autosave status next to the agent button', () => {
    expect(storyScriptSource).not.toContain('story-script-save-status')
    expect(storyScriptSource).not.toContain('saveStatusText')
  })

  it('uses colored text links in the history drawer', () => {
    expect(storyScriptSource).toContain('story-script-history-action--restore')
    expect(storyScriptSource).toContain('story-script-history-action--delete')
    expect(storyScriptSource).toMatch(/type="link"[\s\S]*恢复此版本/)
    expect(cssSource).toMatch(
      /\.story-script-history-action--restore[\s\S]*\.ant-drawer-body[\s\S]*color:\s*#4ae7fd/
    )
    expect(cssSource).toMatch(
      /\.story-script-history-action--delete[\s\S]*\.ant-drawer-body[\s\S]*color:\s*#ff7875/
    )
    expect(cssSource).toMatch(/story-script-history-action--restore[\s\S]*> span/)
    expect(cssSource).toMatch(/story-script-history-action--delete[\s\S]*> span/)
  })

  it('shows autosave hint in the editor footer instead of toolbar status or toast', () => {
    expect(storyScriptSource).not.toContain('story-script-save-status')
    expect(storyScriptSource).toContain('formatStoryScriptAutosaveHint')
    expect(storyScriptSource).toContain('story-script-editor-footer')
    expect(storyScriptSource).toContain('story-script-autosave-hint')
    expect(storyScriptSource).toContain('story-script-char-count')
    expect(storyScriptSource).toContain('showCount={false}')
    expect(autoSaveHookSource).not.toContain("message.success('已自动保存')")
    expect(cssSource).toMatch(/\.story-script-editor-footer[\s\S]*right:\s*16px/)
    expect(cssSource).toMatch(/\.story-script-autosave-hint[\s\S]*color:\s*#8e97a5/)
  })
})
