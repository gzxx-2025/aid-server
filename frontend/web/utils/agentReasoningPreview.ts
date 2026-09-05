export interface AgentReasoningPreview {
  text: string
  truncated: boolean
}

/**
 * Reasoning is shown inside a fixed-height live viewport that scrolls itself.
 * Never truncate live text — sliding-window previews make earlier characters
 * appear to vanish and fight the conversation scroller.
 */
export function resolveAgentReasoningPreview(
  reasoning: string,
  _live?: boolean,
  _maxLiveCharacters?: number
): AgentReasoningPreview {
  return {
    text: String(reasoning || ''),
    truncated: false
  }
}
