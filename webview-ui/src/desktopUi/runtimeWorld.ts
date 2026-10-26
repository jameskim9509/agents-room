/**
 * Keeps the pixel world's characters in step with the runtime's agents
 * (SCRUM-27, D20): one character per agent of the open project, named by the
 * agent's handle (agentHandles.ts), with the behaviour its view calls for
 * (characterBehaviour.ts).
 *
 * The engine is called only on transitions. Applying the same views again, or
 * views where only an unrelated field moved (a repeated `toolCallUpdate`, a
 * message chunk), changes nothing: re-calling `setAgentActive` would cut a
 * walk short and restart poses, and a second `addAgent` would be a duplicate.
 *
 * Run-outcome bubbles (ux.md 상태 어휘, 실행 결과) are shown once per run that
 * ends while the world watches: a run that had already ended when the agent
 * first appeared (a snapshot after reconnecting) shows none.
 */
import type { OfficeState } from '../office/engine/officeState.js';
import type { CharacterBubble } from '../office/types.js';
import { AgentHandles } from './agentHandles.js';
import type { AgentView } from './agentView.js';
import {
  type CharacterBehaviour,
  characterBehaviour,
  sameBehaviour,
} from './characterBehaviour.js';

/** What e2e reads per runtime agent (testHooks `getRuntimeAgents`). */
export interface RuntimeCharacterInfo {
  agentId: string;
  characterId: number;
  behaviour: CharacterBehaviour;
  /** How many times the world changed this character after creating it. */
  engineUpdates: number;
}

/** The inherited chimes (notificationSound.ts), passed in so this module needs no DOM. */
export interface WorldSounds {
  done: () => void;
  permission: () => void;
}

const SILENT: WorldSounds = { done: () => {}, permission: () => {} };

interface Tracked {
  handle: number;
  behaviour: CharacterBehaviour | null;
  /** The last ended run this world has seen for the agent. */
  lastRunId: string | null;
  /** Pending permission requests already announced (sound). */
  permissionIds: Set<string>;
  engineUpdates: number;
}

const OUTCOME_BUBBLES: Record<string, CharacterBubble> = {
  completed: 'waiting',
  cancelled: 'cancelled',
  refused: 'warning',
  limit_reached: 'warning',
  failed: 'error',
};

export class RuntimeWorld {
  readonly handles = new AgentHandles();
  private readonly tracked = new Map<string, Tracked>();
  private applied = false;

  private readonly officeState: () => OfficeState;
  private readonly sounds: WorldSounds;

  constructor(officeState: () => OfficeState, sounds: WorldSounds = SILENT) {
    this.officeState = officeState;
    this.sounds = sounds;
  }

  /**
   * Make the world show `views`. Call it once the office layout (its seats) is
   * loaded. Agents present the first time appear without the spawn effect
   * (they existed before this renderer did); later ones materialize.
   */
  apply(views: readonly AgentView[]): void {
    const os = this.officeState();
    const ids = new Set(views.map((view) => view.agent.agentId));
    for (const [agentId, tracked] of this.tracked) {
      if (ids.has(agentId)) continue;
      os.removeAgent(tracked.handle);
      this.tracked.delete(agentId);
    }
    for (const view of views) this.applyAgent(os, view);
    this.applied = true;
  }

  /** The agent whose character `characterId` is, or null. */
  agentOf(characterId: number | null): string | null {
    const agentId = this.handles.agentFor(characterId);
    return agentId !== null && this.tracked.has(agentId) ? agentId : null;
  }

  /** The character of `agentId`, or null when the world does not show it. */
  characterOf(agentId: string): number | null {
    return this.tracked.get(agentId)?.handle ?? null;
  }

  describe(): RuntimeCharacterInfo[] {
    return [...this.tracked.entries()].map(([agentId, tracked]) => ({
      agentId,
      characterId: tracked.handle,
      behaviour: tracked.behaviour ?? characterBehaviourAtRest(),
      engineUpdates: tracked.engineUpdates,
    }));
  }

  private applyAgent(os: OfficeState, view: AgentView): void {
    const { agentId } = view.agent;
    let tracked = this.tracked.get(agentId);
    if (!tracked) {
      const handle = this.handles.handleFor(agentId);
      // The agent left and came back while its character still dematerialized:
      // drop that one, or addAgent would see it and the effect would then delete it.
      if (os.characters.get(handle)?.matrixEffect === 'despawn') os.characters.delete(handle);
      os.addAgent(handle, view.agent.palette, view.agent.hueShift, undefined, !this.applied);
      const ch = os.characters.get(handle);
      if (ch) {
        ch.isRuntimeAgent = true;
        ch.agentName = view.agent.name;
      }
      tracked = {
        handle,
        behaviour: null,
        lastRunId: view.lastRun?.runId ?? null,
        permissionIds: new Set(view.pendingPermissions.map((p) => p.permissionId)),
        engineUpdates: 0,
      };
      this.tracked.set(agentId, tracked);
    }

    const next = characterBehaviour(view);
    if (!sameBehaviour(tracked.behaviour, next)) {
      if (tracked.behaviour !== null) tracked.engineUpdates++;
      applyBehaviour(os, tracked.handle, tracked.behaviour, next);
      tracked.behaviour = next;
    }

    // A permission request that arrived while the world watched: the inherited chime.
    for (const pending of view.pendingPermissions) {
      if (tracked.permissionIds.has(pending.permissionId)) continue;
      tracked.permissionIds.add(pending.permissionId);
      this.sounds.permission();
    }
    for (const id of tracked.permissionIds) {
      if (!view.pendingPermissions.some((p) => p.permissionId === id)) {
        tracked.permissionIds.delete(id);
      }
    }

    const runId = view.lastRun?.runId ?? null;
    if (runId !== tracked.lastRunId) {
      tracked.lastRunId = runId;
      // A lasting bubble (an error, a new permission request) outranks the outcome.
      if (view.lastRun && next.bubble === null) {
        const bubble = OUTCOME_BUBBLES[view.lastRun.outcome] ?? 'error';
        os.showBubble(tracked.handle, bubble, true);
        if (view.lastRun.outcome === 'completed') this.sounds.done();
      }
    }
  }
}

function characterBehaviourAtRest(): CharacterBehaviour {
  return { pose: 'offline', tool: null, icon: null, bubble: null };
}

/** Engine calls for one behaviour change, each only when its part changed. */
function applyBehaviour(
  os: OfficeState,
  id: number,
  previous: CharacterBehaviour | null,
  next: CharacterBehaviour,
): void {
  if (previous?.pose !== next.pose) {
    os.setAgentPinned(
      id,
      next.pose === 'offline' || next.pose === 'seated' || next.pose === 'frozen',
    );
    os.setAgentOffline(id, next.pose === 'offline');
    // `frozen` keeps whatever the character was doing; the others decide.
    // (A new character is born active, so an `offline` one is switched off here.)
    const active = next.pose === 'working';
    if (next.pose !== 'frozen' && os.characters.get(id)?.isActive !== active) {
      os.setAgentActive(id, active);
    }
  }
  if (previous?.tool !== next.tool) os.setAgentTool(id, next.tool);
  if (previous?.icon !== next.icon) os.setAgentActivityIcon(id, next.icon);
  if (previous?.bubble !== next.bubble) {
    if (previous?.bubble) os.clearBubble(id, previous.bubble);
    if (next.bubble) os.showBubble(id, next.bubble, false);
  }
}
