/**
 * What the desktop renderer holds from the local runtime's frames (ADR 0005).
 *
 * The runtime sends `snapshot` first on every connect, reconnects included,
 * then result frames and `agentEvent` frames. A snapshot replaces everything
 * held before it: the renderer rebuilds its view from it instead of patching
 * an old one. Events the snapshot already covers (`seq <= lastSeq`) are
 * dropped (critique D4, architecture.md "Renderer gateway").
 *
 * The reducer only records frames. Deriving a status, a current tool or the
 * Detail Panel rows from them is the panel's job. Frames are held as the wire
 * carried them: an optional field the wire omits stays absent (`undefined`,
 * as the generated `?:` types say). Only `project`, which the snapshot may
 * omit, is held as `null` when absent. The panel shows either as "미지정" (D3).
 */
import type {
  AgentEvent,
  AgentSnapshot,
  AgentSummary,
  CommandRejected,
  ConnectionChecked,
  ProjectInfo,
  ProjectOpenFailed,
  RecentProject,
  RuntimeServerMessage,
} from '../../core/src/messages.js';
import { RUNTIME_RECENT_EVENTS_MAX } from './constants.js';
import type { MessageTransport } from './transport/types.js';

export interface RuntimeState {
  /** How many snapshots this renderer has received (one per connect). 0: none yet. */
  snapshots: number;
  currentProjectId: string | null;
  /** As of the last snapshot: the runtime sends the list only there. */
  recentProjects: RecentProject[];
  project: ProjectInfo | null;
  /** The current project's agents: from the snapshot, replaced by `agentsUpdated`. */
  agents: AgentSummary[];
  /** Per agent id: the runtime's view at the last snapshot. */
  agentSnapshots: Record<string, AgentSnapshot>;
  /** Per agent id: the snapshot's event tail and every newer event, oldest first. */
  recentEvents: Record<string, AgentEvent[]>;
  /** Per agent id: the newest `seq` held. Events at or below it are dropped. */
  lastSeq: Record<string, number>;
  /** Per agent id: the latest `checkConnection` result. */
  connectionChecks: Record<string, ConnectionChecked>;
  /** The latest failed `openProject`; cleared when a project opens. */
  projectOpenFailed: ProjectOpenFailed | null;
  /** The latest rejected command. */
  commandRejected: CommandRejected | null;
}

export const INITIAL_RUNTIME_STATE: RuntimeState = {
  snapshots: 0,
  currentProjectId: null,
  recentProjects: [],
  project: null,
  agents: [],
  agentSnapshots: {},
  recentEvents: {},
  lastSeq: {},
  connectionChecks: {},
  projectOpenFailed: null,
  commandRejected: null,
};

/** Keeps only the entries of `record` whose key is in `ids`. */
function pick<T>(record: Record<string, T>, ids: ReadonlySet<string>): Record<string, T> {
  return Object.fromEntries(Object.entries(record).filter(([id]) => ids.has(id)));
}

function applyAgentEvent(state: RuntimeState, event: AgentEvent): RuntimeState {
  const { agentId, seq } = event;
  if (seq <= (state.lastSeq[agentId] ?? 0)) return state;
  const events = [...(state.recentEvents[agentId] ?? []), event];
  return {
    ...state,
    lastSeq: { ...state.lastSeq, [agentId]: seq },
    recentEvents: {
      ...state.recentEvents,
      [agentId]: events.slice(-RUNTIME_RECENT_EVENTS_MAX),
    },
  };
}

/**
 * The state after one runtime frame. Returns `state` itself when the frame
 * changes nothing (a duplicate event), so subscribers can skip a re-render.
 */
export function applyRuntimeFrame(state: RuntimeState, frame: RuntimeServerMessage): RuntimeState {
  switch (frame.type) {
    case 'snapshot': {
      const agentSnapshots: Record<string, AgentSnapshot> = {};
      const recentEvents: Record<string, AgentEvent[]> = {};
      const lastSeq: Record<string, number> = {};
      for (const snapshot of frame.agents) {
        const { agentId } = snapshot.agent;
        agentSnapshots[agentId] = snapshot;
        recentEvents[agentId] = snapshot.recentEvents.slice(-RUNTIME_RECENT_EVENTS_MAX);
        lastSeq[agentId] = snapshot.lastSeq;
      }
      return {
        ...INITIAL_RUNTIME_STATE,
        snapshots: state.snapshots + 1,
        currentProjectId: frame.currentProjectId,
        recentProjects: frame.recentProjects,
        project: frame.project ?? null,
        agents: frame.agents.map((snapshot) => snapshot.agent),
        agentSnapshots,
        recentEvents,
        lastSeq,
      };
    }
    case 'projectOpened':
      return {
        ...state,
        currentProjectId: frame.project.projectId,
        project: frame.project,
        projectOpenFailed: null,
      };
    case 'projectOpenFailed':
      return { ...state, projectOpenFailed: frame };
    case 'agentsUpdated': {
      // Agents that left the list (another project opened) take their records with them.
      const ids = new Set(frame.agents.map((agent) => agent.agentId));
      return {
        ...state,
        agents: frame.agents,
        agentSnapshots: pick(state.agentSnapshots, ids),
        recentEvents: pick(state.recentEvents, ids),
        lastSeq: pick(state.lastSeq, ids),
        connectionChecks: pick(state.connectionChecks, ids),
      };
    }
    case 'connectionChecked':
      return {
        ...state,
        connectionChecks: { ...state.connectionChecks, [frame.agentId]: frame },
      };
    case 'commandRejected':
      return { ...state, commandRejected: frame };
    case 'agentEvent':
      return applyAgentEvent(state, frame.event);
    default: {
      // Typed `never`, so a frame added to the contract fails the build here until handled.
      // At run time: a frame this renderer does not know (a newer runtime) changes nothing.
      const unknownFrame: never = frame;
      console.warn('[Webview] Ignored a runtime frame of unknown type', unknownFrame);
      return state;
    }
  }
}

/** The renderer's runtime state, for `useSyncExternalStore`. */
export interface RuntimeStateStore {
  getState(): RuntimeState;
  subscribe(listener: () => void): () => void;
}

/** A store fed by every frame `transport` receives, from its first one on. */
export function createRuntimeStateStore(
  transport: Pick<MessageTransport<RuntimeServerMessage>, 'onMessage'>,
): RuntimeStateStore {
  let state = INITIAL_RUNTIME_STATE;
  const listeners = new Set<() => void>();
  transport.onMessage((frame) => {
    const next = applyRuntimeFrame(state, frame);
    if (next === state) return;
    state = next;
    for (const listener of [...listeners]) listener();
  });
  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
