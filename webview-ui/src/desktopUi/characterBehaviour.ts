/**
 * How an agent's character behaves in the pixel world (ux.md 상태 어휘 and
 * 행동 매핑, SCRUM-27), from its view only: what the runtime reported, never a
 * guess. Pure, for the Node test runner.
 */
import type { ToolKind } from '../../../core/src/messages.js';
import type { ActivityIcon } from '../office/types.js';
import type { AgentView } from './agentView.js';

/**
 * - `offline`: seated, frozen, translucent (no process).
 * - `seated`: seated and frozen, opaque (`starting`: waits at its seat; `error`).
 * - `roaming`: the inherited idle wander (`ready`).
 * - `working`: at its seat with the tool kind's animation (`running`, `awaiting_permission`).
 * - `frozen`: holds its last action (`cancelling`).
 */
export type CharacterPose = 'offline' | 'seated' | 'roaming' | 'working' | 'frozen';

/** A bubble that lasts as long as the state behind it. */
export type LastingBubble = 'permission' | 'error';

export interface CharacterBehaviour {
  pose: CharacterPose;
  /**
   * The engine's tool name: the reported ACP kind. The engine reads `read`,
   * `search` and `fetch` as reading (see READING_TOOL_KINDS), anything else as
   * typing. `other` stands for the generic work display. null while not working.
   */
  tool: string | null;
  icon: ActivityIcon | null;
  bubble: LastingBubble | null;
}

/** The kinds the engine draws with the reading animation (ux.md 행동 매핑 구현 메모). */
export const READING_TOOL_KINDS: readonly ToolKind[] = ['read', 'search', 'fetch'];

/** The generic work display: typing, with the generic icon (an unknown, unreported or `other` kind). */
const GENERIC_TOOL = 'other';

/** The animation and icon of a reported kind (ux.md 행동 매핑). */
export function kindActivity(kind: ToolKind | undefined): {
  tool: string;
  icon: ActivityIcon | null;
} {
  switch (kind) {
    case 'search':
      return { tool: 'search', icon: 'search' };
    case 'read':
    case 'fetch':
      return { tool: kind, icon: null };
    case 'edit':
    case 'delete':
    case 'move':
      return { tool: kind, icon: null };
    case 'execute':
      return { tool: 'execute', icon: 'terminal' };
    default:
      // think, switch_mode, other, a kind this renderer does not know, or none reported.
      return { tool: GENERIC_TOOL, icon: 'generic' };
  }
}

const AT_REST: Omit<CharacterBehaviour, 'pose'> = { tool: null, icon: null, bubble: null };

export function characterBehaviour(view: AgentView): CharacterBehaviour {
  switch (view.status) {
    case 'offline':
      return { pose: 'offline', ...AT_REST };
    case 'starting':
      return { pose: 'seated', ...AT_REST };
    case 'ready':
      return { pose: 'roaming', ...AT_REST };
    case 'running':
    case 'awaiting_permission': {
      // No unfinished tool call reported: the generic work display (ux.md 상태 어휘 `running`).
      const activity = kindActivity(view.currentTool?.kind);
      const waits = view.status === 'awaiting_permission' || view.pendingPermissions.length > 0;
      return { pose: 'working', ...activity, bubble: waits ? 'permission' : null };
    }
    case 'cancelling':
      return { pose: 'frozen', ...kindActivity(view.currentTool?.kind), bubble: null };
    case 'error':
      return { pose: 'seated', ...AT_REST, bubble: 'error' };
  }
}

export function sameBehaviour(a: CharacterBehaviour | null, b: CharacterBehaviour): boolean {
  return (
    a !== null &&
    a.pose === b.pose &&
    a.tool === b.tool &&
    a.icon === b.icon &&
    a.bubble === b.bubble
  );
}
