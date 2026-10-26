/**
 * The rows of the Detail Panel's "최근 이벤트" (ux.md Detail Panel 4): the
 * agent's events in receive (`seq`) order, one row each, except that a run of
 * consecutive text chunks of the same kind and message (or, without a
 * `messageId`, of the same run) is one row with the texts joined (D24). The
 * store already joins them as they arrive, before it keeps the newest 200
 * (runtimeAgentState.ts `appendEventLog`), so the cap counts rows, not chunks;
 * joining here too keeps any list the function is given right. Thought chunks
 * become a collapsed row that never leaves memory (ux.md, SCRUM-16). Pure, for
 * the Node test runner.
 */
import type {
  AgentEvent,
  PermissionOptionKind,
  ToolCallState,
} from '../../../core/src/messages.js';
import { toolBehaviour } from './agentView.js';
import { outcomeText, type OutcomeTone, outcomeTone } from './detailText.js';
import {
  AGENT_STATUS_LABELS,
  ERROR_CODE_LABELS,
  EVENT_LABELS,
  PERMISSION_OPTION_LABELS,
  SESSION_STATE_LABELS,
  STAGE_LABELS,
  T,
  TOOL_BEHAVIOUR_LABELS,
  TOOL_STATUS_LABELS,
} from './strings.js';

export type EventRowTone =
  'normal' | 'muted' | 'active' | 'permission' | 'success' | 'warning' | 'error';

/** A run outcome's row tone: the same reading as "실행 결과" (detailText.ts outcomeTone). */
const OUTCOME_ROW_TONE: Record<OutcomeTone, EventRowTone> = {
  completed: 'success',
  cancelled: 'muted',
  warning: 'warning',
  error: 'error',
};

export interface EventRow {
  /** `seq` of the row's first event: stable React key and receive order. */
  seq: number;
  /** `seq` of its last event (a merged chunk row spans several). */
  lastSeq: number;
  kind: AgentEvent['kind'];
  label: string;
  text: string;
  tone: EventRowTone;
  /** Collapsed by default (thought chunks). */
  collapsed: boolean;
}

/** Title, behaviour and status of a tool call, with "미지정" for what was not reported (R6). */
export function describeToolCall(call: ToolCallState): string {
  const behaviour = toolBehaviour(call.kind);
  const parts = [
    call.title ?? T.unspecified,
    behaviour ? TOOL_BEHAVIOUR_LABELS[behaviour] : T.unspecified,
    call.status ? TOOL_STATUS_LABELS[call.status] : T.unspecified,
  ];
  return parts.join(' · ');
}

function chunkGroup(event: AgentEvent): string | null {
  if (
    event.kind !== 'agentMessageChunk' &&
    event.kind !== 'userMessageChunk' &&
    event.kind !== 'agentThoughtChunk'
  ) {
    return null;
  }
  return `${event.kind}|${event.data.messageId ?? `run:${event.runId ?? ''}`}`;
}

function rowOf(event: AgentEvent, permissionOptions: Map<string, string>): EventRow {
  const base = { seq: event.seq, lastSeq: event.seq, kind: event.kind, collapsed: false };
  switch (event.kind) {
    case 'agentStatusChanged':
      return {
        ...base,
        label: EVENT_LABELS.status,
        text: `${AGENT_STATUS_LABELS[event.data.previous]} → ${AGENT_STATUS_LABELS[event.data.status]}`,
        tone: event.data.status === 'error' ? 'error' : 'normal',
      };
    case 'agentStarting':
      return {
        ...base,
        label: EVENT_LABELS.starting,
        text: STAGE_LABELS[event.data.stage],
        tone: 'muted',
      };
    case 'sessionStarted':
      return { ...base, label: T.sessionState, text: EVENT_LABELS.sessionStarted, tone: 'muted' };
    case 'sessionEnded': {
      const state =
        event.data.reason === 'stopped'
          ? SESSION_STATE_LABELS.ended_stopped
          : event.data.reason === 'exited'
            ? SESSION_STATE_LABELS.ended_exited
            : SESSION_STATE_LABELS.ended_aborted;
      const exit =
        event.data.exitCode !== undefined
          ? ` (${EVENT_LABELS.exitCode} ${event.data.exitCode})`
          : '';
      return {
        ...base,
        label: T.sessionState,
        text: `${state}${exit}`,
        tone: event.data.reason === 'stopped' ? 'muted' : 'error',
      };
    }
    case 'agentError':
      return {
        ...base,
        label: EVENT_LABELS.error,
        text: `${ERROR_CODE_LABELS[event.data.code]} (${T.errorStage}: ${STAGE_LABELS[event.data.stage]})`,
        tone: 'error',
      };
    case 'authStatus':
      return { ...base, label: EVENT_LABELS.authStatus, text: event.data.label, tone: 'muted' };
    case 'runStarted':
      return { ...base, label: EVENT_LABELS.runStarted, text: event.data.prompt, tone: 'active' };
    case 'runEnded':
      return {
        ...base,
        label: EVENT_LABELS.runEnded,
        text: outcomeText(event.data),
        tone: OUTCOME_ROW_TONE[outcomeTone(event.data.outcome)],
      };
    case 'userMessageChunk':
      return {
        ...base,
        label: EVENT_LABELS.userMessage,
        text: event.data.text ?? '',
        tone: 'muted',
      };
    case 'agentMessageChunk':
      return {
        ...base,
        label: EVENT_LABELS.agentMessage,
        text: event.data.text ?? '',
        tone: 'normal',
      };
    case 'agentThoughtChunk':
      return {
        ...base,
        label: T.reportedThought,
        text: event.data.text ?? '',
        tone: 'muted',
        collapsed: true,
      };
    case 'toolCall':
      return {
        ...base,
        label: EVENT_LABELS.toolCall,
        text: describeToolCall(event.data.toolCall),
        tone: 'active',
      };
    case 'toolCallUpdate':
      return {
        ...base,
        label: EVENT_LABELS.toolCallUpdate,
        text: describeToolCall(event.data.toolCall),
        tone: event.data.toolCall.status === 'failed' ? 'error' : 'normal',
      };
    case 'plan':
      return {
        ...base,
        label: EVENT_LABELS.plan,
        text: `${event.data.entries.length}${EVENT_LABELS.items}`,
        tone: 'muted',
      };
    case 'availableCommands':
      return {
        ...base,
        label: EVENT_LABELS.commands,
        text: `${event.data.commands.length}${EVENT_LABELS.items}`,
        tone: 'muted',
      };
    case 'currentMode':
      return { ...base, label: EVENT_LABELS.mode, text: event.data.currentModeId, tone: 'muted' };
    case 'configOptions':
      return {
        ...base,
        label: EVENT_LABELS.configOptions,
        text: `${event.data.configOptions.length}${EVENT_LABELS.items}`,
        tone: 'muted',
      };
    case 'sessionInfo':
      return {
        ...base,
        label: EVENT_LABELS.sessionInfo,
        text: event.data.title ?? T.unspecified,
        tone: 'muted',
      };
    case 'usage':
      return {
        ...base,
        label: EVENT_LABELS.usage,
        text: `${event.data.used} / ${event.data.size}`,
        tone: 'muted',
      };
    case 'permissionRequested':
      return {
        ...base,
        label: EVENT_LABELS.permissionRequested,
        text: event.data.toolCall.title ?? T.requestUnclear,
        tone: 'permission',
      };
    case 'permissionResolved': {
      const chosen =
        event.data.outcome === 'selected' && event.data.optionId !== undefined
          ? (permissionOptions.get(`${event.data.permissionId}|${event.data.optionId}`) ??
            permissionOptions.get(event.data.permissionId) ??
            event.data.optionId)
          : EVENT_LABELS.permissionCancelled;
      return { ...base, label: EVENT_LABELS.permissionResolved, text: chosen, tone: 'permission' };
    }
    case 'unknownUpdate':
      return { ...base, label: EVENT_LABELS.unknownUpdate, text: event.data.type, tone: 'muted' };
    default: {
      const unknownKind: never = event;
      return {
        ...base,
        kind: (unknownKind as AgentEvent).kind,
        label: EVENT_LABELS.unknownUpdate,
        text: '',
        tone: 'muted',
      };
    }
  }
}

/**
 * The rows for `events` (already in `seq` order, as the runtime state holds
 * them). `answered` names the chosen option's kind per permission request
 * (the agent's state keeps it), for a resolution whose request has already
 * left the list.
 */
export function eventRows(
  events: readonly AgentEvent[],
  answered: Readonly<Record<string, PermissionOptionKind>> = {},
): EventRow[] {
  // A resolution shows the label of the option the user picked, by kind (ux.md).
  const permissionOptions = new Map<string, string>();
  for (const [permissionId, kind] of Object.entries(answered)) {
    permissionOptions.set(permissionId, PERMISSION_OPTION_LABELS[kind]);
  }
  for (const event of events) {
    if (event.kind !== 'permissionRequested') continue;
    for (const option of event.data.options) {
      permissionOptions.set(
        `${event.data.permissionId}|${option.optionId}`,
        PERMISSION_OPTION_LABELS[option.kind],
      );
    }
  }
  const rows: EventRow[] = [];
  let lastGroup: string | null = null;
  for (const event of events) {
    const group = chunkGroup(event);
    const previous = rows[rows.length - 1];
    if (group !== null && group === lastGroup && previous) {
      previous.text += 'text' in event.data ? (event.data.text ?? '') : '';
      previous.lastSeq = event.seq;
      continue;
    }
    rows.push(rowOf(event, permissionOptions));
    lastGroup = group;
  }
  return rows;
}
