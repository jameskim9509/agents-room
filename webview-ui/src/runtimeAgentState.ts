/**
 * What the desktop renderer keeps of one agent beyond the frames themselves
 * (runtimeState.ts): its lasting state, folded from the agent's last snapshot
 * and every event accepted after it, and the event list the Detail Panel's
 * "최근 이벤트" rows come from.
 *
 * ADR 0005 decision 2: the renderer replaces an agent's state with the
 * snapshot, then applies every newer event. The store applies each event here
 * once, as it arrives, so the state never depends on how many events the
 * 200-event windows still hold: a run whose answer streams thousands of chunks
 * still reads as running, with its session, request, tool and whole answer
 * (SCRUM-19, 22, 27).
 *
 * The runtime computes every status and merges every tool call (ADR 0005
 * decisions 4, 6); this module only mirrors what its events say, never
 * recomputes them. Nothing is invented (ux.md 관찰한 것만 보여준다): a field
 * the runtime did not report stays null. Pure, for the Node test runner.
 */
import type {
  AgentErrorData,
  AgentEvent,
  AgentInfo,
  AgentSnapshot,
  AgentStatus,
  AuthStatus,
  PermissionOptionKind,
  PermissionRequestedData,
  PlanEntry,
  RunEndedData,
  SessionEndedData,
  Stage,
  ToolCallState,
  UsageData,
} from '../../core/src/messages.js';
import { RUNTIME_RECENT_EVENTS_MAX } from './constants.js';

export interface AgentState {
  status: AgentStatus;
  /** The open session, or null. */
  sessionId: string | null;
  /** How the last session ended; cleared when a new one opens. */
  sessionEnd: SessionEndedData | null;
  /** The latest stage of the start in progress; null outside `starting`. */
  startingStage: Stage | null;
  /** The run in progress, or null. */
  runId: string | null;
  /** The prompt of the run in progress, else of the last run that ended. */
  prompt: string | null;
  /** The run in progress's tool calls as the runtime merged them, first seen first. */
  toolCalls: readonly ToolCallState[];
  /** The last run that ended, cleared when a new one starts. */
  lastRun: RunEndedData | null;
  /** The last ended run's tool calls (for the titles of its unfinished ones). */
  lastRunToolCalls: readonly ToolCallState[];
  /**
   * Whether `prompt`, `lastRun` and `response` belong to the agent's latest
   * start: false from the moment a new start begins, so a run of an earlier
   * session never reads as part of the new one (ux.md R8).
   */
  runOfLatestStart: boolean;
  /**
   * The answer text of the run in progress, else of the last run: every
   * `agentMessageChunk` of the run, joined, a new line between two messages.
   */
  response: string | null;
  /**
   * The answer may miss its beginning: it was rebuilt from a snapshot whose
   * event tail no longer holds the run's start (ux.md 현재 실행).
   */
  responsePartial: boolean;
  /** `messageId` of the answer's last chunk (null: none reported), to tell messages apart. */
  responseMessageId: string | null;
  pendingPermissions: readonly PermissionRequestedData[];
  /** Per answered permission request: the kind of the option the runtime says was chosen. */
  answeredOptions: Readonly<Record<string, PermissionOptionKind>>;
  /** The error that put the agent in `error` (the runtime keeps it only there). */
  lastError: AgentErrorData | null;
  authStatus: AuthStatus | null;
  /** The open session's `agentInfo`. */
  agentInfo: AgentInfo | null;
  /** The last `agentInfo` an adapter reported, kept after its session ended (the harness badge). */
  reportedAgentInfo: AgentInfo | null;
  /** Session-scoped reports of the open session (null until reported). */
  plan: PlanEntry[] | null;
  usage: UsageData | null;
  sessionTitle: string | null;
  currentModeId: string | null;
  modes: Record<string, unknown> | null;
  configOptions: Record<string, unknown>[] | null;
}

type SessionReports = Pick<
  AgentState,
  'agentInfo' | 'plan' | 'usage' | 'sessionTitle' | 'currentModeId' | 'modes' | 'configOptions'
>;

const NO_SESSION_REPORTS: SessionReports = {
  agentInfo: null,
  plan: null,
  usage: null,
  sessionTitle: null,
  currentModeId: null,
  modes: null,
  configOptions: null,
};

/**
 * An agent the renderer has no snapshot of (it arrived with `agentsUpdated`):
 * a project's agents get a fresh runtime controller when it opens.
 */
export const OFFLINE_AGENT_STATE: AgentState = {
  status: 'offline',
  sessionId: null,
  sessionEnd: null,
  startingStage: null,
  runId: null,
  prompt: null,
  toolCalls: [],
  lastRun: null,
  lastRunToolCalls: [],
  runOfLatestStart: true,
  response: null,
  responsePartial: false,
  responseMessageId: null,
  pendingPermissions: [],
  answeredOptions: {},
  lastError: null,
  authStatus: null,
  reportedAgentInfo: null,
  ...NO_SESSION_REPORTS,
};

function currentModeOf(modes: Record<string, unknown> | undefined): string | null {
  const id = modes?.['currentModeId'];
  return typeof id === 'string' ? id : null;
}

/** `calls` with `call` in place of the one of its id, or added last. */
function withToolCall(calls: readonly ToolCallState[], call: ToolCallState): ToolCallState[] {
  const index = calls.findIndex((c) => c.toolCallId === call.toolCallId);
  if (index === -1) return [...calls, call];
  const next = [...calls];
  next[index] = call;
  return next;
}

/** `answered` with one more entry, keeping the newest RUNTIME_RECENT_EVENTS_MAX (the event list's reach). */
function withAnswer(
  answered: Readonly<Record<string, PermissionOptionKind>>,
  permissionId: string,
  kind: PermissionOptionKind,
): Record<string, PermissionOptionKind> {
  const entries = Object.entries(answered).filter(([id]) => id !== permissionId);
  entries.push([permissionId, kind]);
  return Object.fromEntries(entries.slice(-RUNTIME_RECENT_EVENTS_MAX));
}

/** The text chunk data of a message or thought chunk, else null (the runtime's RecentEvents rule). */
function textChunk(event: AgentEvent): { messageId?: string; text: string } | null {
  if (
    event.kind !== 'agentMessageChunk' &&
    event.kind !== 'userMessageChunk' &&
    event.kind !== 'agentThoughtChunk'
  ) {
    return null;
  }
  const { data } = event;
  return data.contentType === 'text' && data.text !== undefined
    ? { messageId: data.messageId, text: data.text }
    : null;
}

/** Whether `next` continues `last`'s message: same kind, same `messageId` (or, without one, run). */
function continuesMessage(last: AgentEvent, next: AgentEvent): boolean {
  const previous = textChunk(last);
  const current = textChunk(next);
  if (!previous || !current || last.kind !== next.kind) return false;
  if (previous.messageId !== undefined || current.messageId !== undefined) {
    return previous.messageId === current.messageId;
  }
  return last.runId === next.runId;
}

/** Adds one answer chunk of the run to `state` (changes it: pass a copy). */
function withAnswerChunk(state: AgentState, messageId: string | undefined, text: string): void {
  const id = messageId ?? null;
  const sameMessage = state.response !== null && state.responseMessageId === id;
  state.response =
    state.response === null ? text : `${state.response}${sameMessage ? '' : '\n'}${text}`;
  state.responseMessageId = id;
}

/**
 * The state after one event newer than everything in `state`, as a new object
 * (`state` is not changed). Mirrors the runtime's own transitions.
 */
export function applyAgentStateEvent(state: AgentState, event: AgentEvent): AgentState {
  const next: AgentState = { ...state };
  switch (event.kind) {
    case 'agentStatusChanged':
      next.status = event.data.status;
      // The runtime keeps lastError only while the agent is in `error`.
      if (next.status !== 'error') next.lastError = null;
      if (next.status === 'starting') {
        // A new start, so a new session (R8): the run so far belongs to the one before.
        next.startingStage = null;
        next.runOfLatestStart = false;
      }
      break;
    case 'agentStarting':
      next.startingStage = event.data.stage;
      break;
    case 'agentError':
      next.lastError = event.data;
      break;
    case 'authStatus':
      next.authStatus = event.data;
      break;
    case 'sessionStarted':
      Object.assign(next, NO_SESSION_REPORTS);
      next.sessionId = event.data.sessionId;
      next.sessionEnd = null;
      next.agentInfo = event.data.agentInfo ?? null;
      next.reportedAgentInfo = event.data.agentInfo ?? state.reportedAgentInfo;
      next.modes = event.data.modes ?? null;
      next.currentModeId = currentModeOf(event.data.modes);
      next.configOptions = event.data.configOptions ?? null;
      break;
    case 'sessionEnded':
      Object.assign(next, NO_SESSION_REPORTS);
      next.sessionId = null;
      next.sessionEnd = event.data;
      break;
    case 'runStarted':
      next.runId = event.data.runId;
      next.prompt = event.data.prompt;
      next.toolCalls = [];
      next.lastRun = null;
      next.lastRunToolCalls = [];
      next.runOfLatestStart = true;
      next.response = null;
      next.responsePartial = false;
      next.responseMessageId = null;
      break;
    case 'runEnded':
      next.lastRun = event.data;
      next.lastRunToolCalls = state.toolCalls;
      next.runId = null;
      next.toolCalls = [];
      break;
    case 'toolCall':
    case 'toolCallUpdate': {
      // The event carries the runtime's merged state. A late update (after the
      // run's answer, runId null) belongs to no run in progress; like the
      // runtime, it still updates the last run's call of that id.
      const call = event.data.toolCall;
      if (event.runId !== null && event.runId === state.runId) {
        next.toolCalls = withToolCall(state.toolCalls, call);
      } else if (
        event.runId === null &&
        state.lastRunToolCalls.some((c) => c.toolCallId === call.toolCallId)
      ) {
        next.lastRunToolCalls = withToolCall(state.lastRunToolCalls, call);
      }
      break;
    }
    case 'agentMessageChunk':
      // Only the run in progress's answer (a late chunk, runId null, belongs to no run).
      if (event.runId === null || event.runId !== state.runId) return state;
      if (event.data.contentType !== 'text' || !event.data.text) return state;
      withAnswerChunk(next, event.data.messageId, event.data.text);
      break;
    case 'permissionRequested':
      if (!state.pendingPermissions.some((p) => p.permissionId === event.data.permissionId)) {
        next.pendingPermissions = [...state.pendingPermissions, event.data];
      }
      break;
    case 'permissionResolved': {
      const { permissionId, outcome, optionId } = event.data;
      const request = state.pendingPermissions.find((p) => p.permissionId === permissionId);
      const chosen =
        outcome === 'selected' ? request?.options.find((o) => o.optionId === optionId) : undefined;
      if (chosen)
        next.answeredOptions = withAnswer(state.answeredOptions, permissionId, chosen.kind);
      next.pendingPermissions = state.pendingPermissions.filter(
        (p) => p.permissionId !== permissionId,
      );
      break;
    }
    case 'plan':
      next.plan = event.data.entries;
      break;
    case 'usage':
      next.usage = event.data;
      break;
    case 'sessionInfo':
      if (event.data.title !== undefined) next.sessionTitle = event.data.title;
      break;
    case 'currentMode':
      next.currentModeId = event.data.currentModeId;
      break;
    case 'configOptions':
      next.configOptions = event.data.configOptions;
      break;
    case 'userMessageChunk':
    case 'agentThoughtChunk':
    case 'availableCommands':
    case 'unknownUpdate':
      return state;
    default: {
      // Typed `never`: a kind added to the contract fails the build here until handled.
      const unknownKind: never = event;
      console.warn('[Webview] Ignored an agent event of unknown kind', unknownKind);
      return state;
    }
  }
  return next;
}

/**
 * An agent's state from its snapshot, which already reflects every event up
 * to its `lastSeq`. What the snapshot does not carry (the start stage, the
 * answer text, which start a run belongs to, the last adapter name) comes from
 * its event tail, which the runtime keeps merged and without thought chunks.
 */
export function agentStateFromSnapshot(snapshot: AgentSnapshot): AgentState {
  const tail = snapshot.recentEvents;
  const session = snapshot.session;
  const state: AgentState = {
    ...OFFLINE_AGENT_STATE,
    status: snapshot.status,
    sessionId: snapshot.sessionId,
    sessionEnd: snapshot.sessionEnd ?? null,
    runId: snapshot.runId,
    prompt: snapshot.prompt ?? null,
    toolCalls: [...snapshot.toolCalls],
    lastRun: snapshot.lastRun ?? null,
    pendingPermissions: [...snapshot.pendingPermissions],
    lastError: snapshot.lastError ?? null,
    authStatus: snapshot.authStatus ?? null,
    agentInfo: session?.agentInfo ?? null,
    reportedAgentInfo: session?.agentInfo ?? null,
    plan: session?.plan ?? null,
    usage: session?.usage ?? null,
    sessionTitle: session?.sessionInfo?.title ?? null,
    currentModeId: session?.currentModeId ?? null,
    modes: session?.modes ?? null,
    configOptions: session?.configOptions ?? null,
  };
  const runId = snapshot.runId ?? snapshot.lastRun?.runId ?? null;
  let runSeen = false;
  const options = new Map<string, PermissionOptionKind>();
  for (const event of tail) {
    switch (event.kind) {
      case 'agentStatusChanged':
        if (event.data.status === 'starting') {
          state.startingStage = null;
          state.runOfLatestStart = false;
        }
        break;
      case 'agentStarting':
        state.startingStage = event.data.stage;
        break;
      case 'sessionStarted':
        if (event.data.agentInfo && !session?.agentInfo) {
          state.reportedAgentInfo = event.data.agentInfo;
        }
        break;
      case 'runStarted':
        if (event.data.runId === runId) {
          runSeen = true;
          state.runOfLatestStart = true;
        }
        break;
      case 'toolCall':
      case 'toolCallUpdate':
        if (snapshot.runId === null && runId !== null && event.runId === runId) {
          state.lastRunToolCalls = withToolCall(state.lastRunToolCalls, event.data.toolCall);
        }
        break;
      case 'agentMessageChunk':
        if (runId !== null && event.runId === runId && event.data.contentType === 'text') {
          if (event.data.text) withAnswerChunk(state, event.data.messageId, event.data.text);
        }
        break;
      case 'permissionRequested':
        for (const option of event.data.options) {
          options.set(`${event.data.permissionId}|${option.optionId}`, option.kind);
        }
        break;
      case 'permissionResolved': {
        const kind = options.get(`${event.data.permissionId}|${event.data.optionId ?? ''}`);
        if (event.data.outcome === 'selected' && kind) {
          state.answeredOptions = withAnswer(state.answeredOptions, event.data.permissionId, kind);
        }
        break;
      }
      default:
        break;
    }
  }
  if (snapshot.status !== 'starting') state.startingStage = null;
  // While starting, any run is an earlier start's; a run in progress is always the latest one's.
  if (snapshot.status === 'starting') state.runOfLatestStart = false;
  if (snapshot.runId !== null) state.runOfLatestStart = true;
  // Without the run's start in the tail, earlier chunks of its answer may have left it.
  state.responsePartial = runId !== null && !runSeen;
  return state;
}

/**
 * The event list with one more event, as the runtime keeps its tail
 * (RecentEvents, ADR 0005 decision 2): a text chunk that continues the last
 * held one's message is joined into it, then only the newest `max` are kept,
 * so a long answer is one entry, not hundreds (D24). Unlike the runtime's
 * tail, thought chunks stay (they are shown live, collapsed), and a joined
 * entry keeps its first chunk's `seq`, so its row's key stays put while the
 * message streams.
 */
export function appendEventLog(
  log: readonly AgentEvent[],
  event: AgentEvent,
  max = RUNTIME_RECENT_EVENTS_MAX,
): AgentEvent[] {
  const last = log[log.length - 1];
  if (last && continuesMessage(last, event)) {
    const text = (textChunk(last)?.text ?? '') + (textChunk(event)?.text ?? '');
    const joined = { ...last, data: { ...last.data, text } } as AgentEvent;
    return [...log.slice(0, -1), joined];
  }
  const next = [...log, event];
  return next.length > max ? next.slice(next.length - max) : next;
}
