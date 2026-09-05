import { describe, expect, it } from 'vitest'
import panelSource from '../components/steps/story-script-agent/StoryScriptAgentPanel.tsx?raw'
import composerSendSource from '../components/common/composer-send/ComposerSendButton.tsx?raw'
import sharedPanelSource from '../components/common/agent-conversation/AgentConversationPanel.tsx?raw'
import { readCssImportGraph } from './testSupport/readCssImportGraph'

const cssSource = [
  readCssImportGraph(
    new URL('../components/steps/story-script-agent/story-script-agent.css', import.meta.url)
  ),
  readCssImportGraph(
    new URL('../components/common/agent-conversation/agent-conversation-panel.css', import.meta.url)
  ),
  readCssImportGraph(
    new URL('../components/common/agent-conversation/agent-conversation-viewport.css', import.meta.url)
  )
].join('\n')
const chromeSource = `${panelSource}\n${sharedPanelSource}\n${composerSendSource}`

describe('story script agent panel chrome', () => {
  it('uses Lanhu header icons, title and the shared send control', () => {
    expect(chromeSource).toContain('assets/img/home/agent-icon.svg')
    expect(chromeSource).toContain('assets/img/icon/icon-fold.svg')
    expect(chromeSource).toContain('ComposerSendButton')
    expect(chromeSource).toContain('assets/img/home/btn-send-h.svg')
    expect(chromeSource).toContain('assets/img/home/group-icon.svg')
    expect(chromeSource).toContain('新对话')
    expect(chromeSource).toContain('输入内容')
    expect(chromeSource).toContain('composer-flow')
    expect(chromeSource).not.toContain('RobotOutlined')
    expect(chromeSource).not.toContain('ShrinkOutlined')
  })

  it('matches Lanhu panel width and composer input height', () => {
    expect(cssSource).toMatch(/--story-agent-panel-w:\s*min\(440px,\s*100%\)/)
    expect(cssSource).toMatch(/\.story-script-agent-panel__input-wrap[^{]*\{[^}]*height:\s*122px/s)
    expect(cssSource).toMatch(/border-radius:\s*16px/)
    expect(cssSource).toContain('composer-flow')
  })

  it('keeps skill as an in-composer icon instead of a bulky select', () => {
    expect(panelSource).toContain('composer-tools')
    expect(panelSource).toContain('StoryScriptAgentSkillPicker')
    expect(cssSource).not.toContain('story-script-agent-panel__skill-row')
    expect(cssSource).not.toContain('story-agent-skill-select')
  })

  it('fills the story-script column so the conversation viewport owns vertical scrolling', () => {
    expect(cssSource).toMatch(
      /\.story-script-agent-panel\s*\{[^}]*display:\s*flex[^}]*flex-direction:\s*column[^}]*height:\s*100%/s
    )
    expect(cssSource).toMatch(
      /\.story-script-agent-panel\s*>\s*\.agent-conversation-panel\s*\{[^}]*height:\s*100%/s
    )
    expect(cssSource).toMatch(/\.agent-conversation-viewport\s*\{[^}]*overflow-y:\s*auto/s)
    expect(cssSource).toMatch(
      /\.story-script-agent-panel__messages\s*\{[^}]*min-height:\s*0[^}]*overflow-y:\s*auto/s
    )
  })

  it('lets expanded screenplay grow inside the conversation scroll, with actions after the body', () => {
    expect(cssSource).toMatch(
      /\.story-agent-document__content\.is-expanded\s*\{[^}]*max-height:\s*none/s
    )
    expect(cssSource).toMatch(
      /\.story-agent-document__content:not\(\.is-expanded\)[^{]*\{[^}]*overflow-y:\s*auto/s
    )
    expect(cssSource).toMatch(
      /\.story-agent-document__content:not\(\.is-expanded\)[^{]*\{[^}]*scrollbar-width:\s*none/s
    )
    expect(cssSource).not.toMatch(
      /\.story-agent-document__content\.is-expanded\s*\{[^}]*overflow:\s*hidden/s
    )
    expect(panelSource).toContain('resolveStoryScriptAgentDocumentView')
    expect(panelSource).toContain('story-agent-document__actions')
    expect(panelSource.indexOf('story-agent-document__content')).toBeLessThan(
      panelSource.indexOf('story-agent-document__actions')
    )
    expect(panelSource).toContain('收起全文')
    expect(panelSource).toContain('复制剧本')
    expect(panelSource).toContain('带入当前剧本')
  })

  it('styles the apply button as a project primary action with white text', () => {
    expect(cssSource).toMatch(
      /\.story-agent-document__apply\s*\{[^}]*color:\s*#fff[^}]*background:\s*var\(--home-grad-btn/s
    )
    expect(cssSource).not.toMatch(
      /\.story-agent-document__apply\s*\{[^}]*color:\s*#0b1d23/s
    )
  })

  it('renders Runtime output and dynamic input without legacy reasoning UI', () => {
    expect(panelSource).not.toContain('view.reasoningLabel')
    expect(panelSource).toContain('view.showBody')
    expect(panelSource).toContain('reasoning={item.reasoning}')
    expect(panelSource).toContain('StoryScriptAgentInputRequestCard')
    expect(panelSource).toContain('display.applicable && stoppedPartialTrusted')
    expect(panelSource).toContain('softPauseEnabled ? onPauseReceiving : onStop')
    expect(panelSource).toContain('onPause={pauseAction}')
    expect(panelSource).toContain('AgentPausedRuntimeNotice')
  })

  it('uses the muted dialog flow border on the conversation composer', () => {
    expect(panelSource).toContain('composer-flow--dialog')
    expect(cssSource).toContain('rgba(38, 41, 49, .5)')
  })
})
