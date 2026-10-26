import { playDoneSound, playPermissionSound } from '../notificationSound.js';
import type { OfficeState } from '../office/engine/officeState.js';
import { setProviderCapabilities } from '../office/toolUtils.js';
import { READING_TOOL_KINDS } from './characterBehaviour.js';
import { RuntimeWorld } from './runtimeWorld.js';

/**
 * The desktop shell's world, with the inherited chimes. Also tells the engine
 * which tool names read rather than type: the ACP kinds `read`, `search`,
 * `fetch` (ux.md 행동 매핑), and that no tool spawns a sub-agent character
 * (R4, SCRUM-21). The desktop receives no `providerCapabilities`, so nothing
 * overrides this.
 */
export function createRuntimeWorld(officeState: () => OfficeState): RuntimeWorld {
  setProviderCapabilities({ readingTools: [...READING_TOOL_KINDS], subagentToolNames: [] });
  return new RuntimeWorld(officeState, { done: playDoneSound, permission: playPermissionSound });
}
