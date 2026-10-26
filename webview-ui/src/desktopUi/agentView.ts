/**
 * What the desktop panels and the pixel world show for one agent, read from
 * what the renderer holds of the runtime's frames (runtimeState.ts). Pure, so
 * it runs under the Node test runner.
 *
 * The lasting state (status, session, run, tool calls, permission requests,
 * answer text) is the one the store folded from the agent's last snapshot and
 * every event after it, each applied once as it arrived (runtimeAgentState.ts).
 * Nothing here replays the capped event lists, so a long run reads the same
 * after its 201st event as after its first. The runtime computes every status
 * and merges every tool call (ADR 0005 decisions 4, 6); this module never
 * recomputes them.
 *
 * Nothing here is invented (ux.md "관찰한 것만 보여준다"): a field the runtime
 * did not report stays `null` and the panel writes "미지정" or "상태 정보 없음".
 */
import type {
  AgentErrorData,
  AgentEvent,
  AgentInfo,
  AgentStatus,
  AgentSummary,
  AuthStatus,
  PermissionOptionKind,
  PermissionRequestedData,
  PlanEntry,
  RunEndedData,
  Stage,
  ToolCallState,
  ToolKind,
  UsageData,
} from '../../../core/src/messages.js';
import { type AgentState, OFFLINE_AGENT_STATE } from '../runtimeAgentState.js';
import type { RuntimeState } from '../runtimeState.js';

/** "세션 상태" (ux.md 상태 어휘, D23). */
export type SessionState = 'none' | 'open' | 'ended_stopped' | 'ended_exited' | 'ended_aborted';

/** The Jira behaviour of a tool kind (ux.md 행동 매핑). */
export type ToolBehaviour = 'search' | 'read' | 'edit' | 'execute' | 'other';

export interface AgentView {
  agent: AgentSummary;
  status: AgentStatus;
  sessionId: string | null;
  sessionState: SessionState;
  /** The latest start stage while `starting`, else null. */
  startingStage: Stage | null;
  /** The run in progress, or null. */
  runId: string | null;
  /**
   * The prompt of the run in progress, else of the last run that ended. Like
   * `lastRun` and `response`, null once a new start began after that run (R8).
   */
  prompt: string | null;
  /** The run in progress's tool calls as the runtime merged them, first seen first. */
  toolCalls: readonly ToolCallState[];
  /** The most recently started tool call of the run that has not finished (ux.md 행동 매핑). */
  currentTool: ToolCallState | null;
  /** The last run that ended, cleared when a new one starts. */
  lastRun: RunEndedData | null;
  /** The last ended run's tool calls, for the titles of its unfinished ones. */
  lastRunToolCalls: readonly ToolCallState[];
  /** Answer text the agent reported for the run in progress, else for the last one (agentMessageChunk). */
  response: string | null;
  /** `response` may miss its beginning (rebuilt from a snapshot tail without the run's start). */
  responsePartial: boolean;
  pendingPermissions: readonly PermissionRequestedData[];
  /** Per answered permission request: the kind of the chosen option (event row labels). */
  answeredOptions: Readonly<Record<string, PermissionOptionKind>>;
  /** The error that put the agent in `error`; null in every other status. */
  lastError: AgentErrorData | null;
  authStatus: AuthStatus | null;
  /** The open session's `agentInfo`. */
  agentInfo: AgentInfo | null;
  /** The last `agentInfo` an adapter reported, kept after its session ended (harness badge). */
  reportedAgentInfo: AgentInfo | null;
  /** Session-scoped reports of the open session (null until reported). */
  plan: PlanEntry[] | null;
  usage: UsageData | null;
  sessionTitle: string | null;
  currentModeId: string | null;
  modes: Record<string, unknown> | null;
  configOptions: Record<string, unknown>[] | null;
  /** The "최근 이벤트" list: receive (`seq`) order, chunks of one message joined, newest 200. */
  events: readonly AgentEvent[];
  /** The newest `seq` received for the agent (0: none). */
  lastSeq: number;
}

/** Stages of `startAgent` before a session exists: an error here means "세션 없음". */
const START_STAGES: ReadonlySet<Stage> = new Set([
  'spawn',
  'initialize',
  'authenticate',
  'session_new',
  'config',
]);

/** Whether the adapter reported the tool call finished (the runtime's `ToolCalls.isFinished`). */
export function isToolCallFinished(call: ToolCallState): boolean {
  return call.status === 'completed' || call.status === 'failed';
}

/** The Jira behaviour of a reported kind (ux.md 행동 매핑); null when no kind was reported. */
export function toolBehaviour(kind: ToolKind | undefined): ToolBehaviour | null {
  switch (kind) {
    case undefined:
      return null;
    case 'search':
      return 'search';
    case 'read':
    case 'fetch':
      return 'read';
    case 'edit':
    case 'delete':
    case 'move':
      return 'edit';
    case 'execute':
      return 'execute';
    default:
      return 'other';
  }
}

function sessionStateOf(state: AgentState): SessionState {
  if (state.status === 'starting') return 'none';
  if (state.sessionId !== null) return 'open';
  // A start that failed before session/new opened nothing, whatever ended before it.
  if (state.status === 'error' && state.lastError && START_STAGES.has(state.lastError.stage)) {
    return 'none';
  }
  if (!state.sessionEnd) return 'none';
  switch (state.sessionEnd.reason) {
    case 'stopped':
      return 'ended_stopped';
    case 'exited':
      return 'ended_exited';
    case 'aborted':
      return 'ended_aborted';
  }
}

/** The view of one agent of `state` (see the module comment). */
export function deriveAgentView(state: RuntimeState, agent: AgentSummary): AgentView {
  const { agentId } = agent;
  const folded = state.agentStates[agentId] ?? OFFLINE_AGENT_STATE;
  // A run of an earlier start is not the current one (R8); the event list still has it.
  const showRun = folded.runId !== null || folded.runOfLatestStart;
  const currentTool =
    folded.runId === null
      ? null
      : ([...folded.toolCalls].reverse().find((call) => !isToolCallFinished(call)) ?? null);
  return {
    agent,
    status: folded.status,
    sessionId: folded.sessionId,
    sessionState: sessionStateOf(folded),
    startingStage: folded.status === 'starting' ? folded.startingStage : null,
    runId: folded.runId,
    prompt: showRun ? folded.prompt : null,
    toolCalls: folded.toolCalls,
    currentTool,
    lastRun: showRun ? folded.lastRun : null,
    lastRunToolCalls: showRun ? folded.lastRunToolCalls : [],
    response: showRun ? folded.response : null,
    responsePartial: showRun && folded.responsePartial,
    pendingPermissions: folded.pendingPermissions,
    answeredOptions: folded.answeredOptions,
    lastError: folded.status === 'error' ? folded.lastError : null,
    authStatus: folded.authStatus,
    agentInfo: folded.agentInfo,
    reportedAgentInfo: folded.reportedAgentInfo,
    plan: folded.plan,
    usage: folded.usage,
    sessionTitle: folded.sessionTitle,
    currentModeId: folded.currentModeId,
    modes: folded.modes,
    configOptions: folded.configOptions,
    events: state.eventLogs[agentId] ?? [],
    lastSeq: state.lastSeq[agentId] ?? 0,
  };
}

/** Every agent of the current project, in the runtime's list order. */
export function deriveAgentViews(state: RuntimeState): AgentView[] {
  return state.agents.map((agent) => deriveAgentView(state, agent));
}
