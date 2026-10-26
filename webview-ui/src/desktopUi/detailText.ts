/**
 * The Detail Panel's derived lines (ux.md Detail Panel, 관찰한 것만 보여준다).
 * Pure, for the Node test runner: each returns exactly what the panel prints.
 */
import type { RunEndedData, RunOutcome } from '../../../core/src/messages.js';
import { PERMISSION_INPUT_PREVIEW_MAX } from '../constants.js';
import { type AgentView, toolBehaviour } from './agentView.js';
import {
  AGENT_STATUS_LABELS,
  PROVIDER_LABELS,
  RUN_FAILED_PROCESS_EXITED,
  RUN_OUTCOME_LABELS,
  T,
  TOOL_BEHAVIOUR_LABELS,
  TOOL_STATUS_LABELS,
} from './strings.js';

/** "현재 Tool": the latest unfinished tool call's title, behaviour and status, or R5's "상태 정보 없음". */
export function currentToolText(view: AgentView): {
  title: string;
  behaviour: string;
  status: string;
} | null {
  const call = view.currentTool;
  if (!call) return null;
  const behaviour = toolBehaviour(call.kind);
  return {
    title: call.title ?? T.unspecified,
    behaviour: behaviour ? TOOL_BEHAVIOUR_LABELS[behaviour] : T.unspecified,
    status: call.status ? TOOL_STATUS_LABELS[call.status] : T.unspecified,
  };
}

export type RunTone =
  'progress' | 'waiting' | 'completed' | 'cancelled' | 'warning' | 'error' | 'none';

/** How an ended run reads, the same in "실행 결과" and in its event row (ux.md 실행 결과 표). */
export type OutcomeTone = Extract<RunTone, 'completed' | 'cancelled' | 'warning' | 'error'>;

/**
 * The tone of a run outcome: done, cancelled (neutral), ended without an
 * answer the user asked for (refused, limit reached: a warning, never the
 * permission amber of "승인 대기"), or failed.
 */
export function outcomeTone(outcome: RunOutcome): OutcomeTone {
  switch (outcome) {
    case 'completed':
      return 'completed';
    case 'cancelled':
      return 'cancelled';
    case 'refused':
    case 'limit_reached':
      return 'warning';
    case 'failed':
      return 'error';
  }
}

/** The label of an ended run: a run whose adapter died is "에이전트 프로세스가 종료됨", never "완료" (R7). */
export function outcomeText(run: RunEndedData): string {
  return run.outcome === 'failed' && run.errorCode === 'PROCESS_EXITED'
    ? RUN_FAILED_PROCESS_EXITED
    : RUN_OUTCOME_LABELS[run.outcome];
}

/**
 * Where the run stands, so 진행 / 대기 / 오류 / 완료 read apart (SCRUM-19): the
 * run in progress by agent status, else the last run's outcome.
 */
export function runStatus(view: AgentView): { text: string; tone: RunTone } {
  if (view.runId !== null) {
    if (view.status === 'awaiting_permission') {
      return { text: AGENT_STATUS_LABELS.awaiting_permission, tone: 'waiting' };
    }
    if (view.status === 'running' || view.status === 'cancelling') {
      return { text: AGENT_STATUS_LABELS[view.status], tone: 'progress' };
    }
  }
  const run = view.lastRun;
  if (!run) return { text: T.unspecified, tone: 'none' };
  return { text: outcomeText(run), tone: outcomeTone(run.outcome) };
}

/** The title of one of the last run's tool calls, as the runtime last merged it; else its id. */
export function lastRunToolTitle(view: AgentView, toolCallId: string): string {
  return view.lastRunToolCalls.find((call) => call.toolCallId === toolCallId)?.title ?? toolCallId;
}

export function providerText(providerId: string): string {
  return PROVIDER_LABELS[providerId] ?? providerId;
}

/** The model the adapter reports in its `model` config option, if it does (ux.md "어댑터 보고"). */
export function reportedModel(view: AgentView): string | null {
  for (const option of view.configOptions ?? []) {
    if (option['category'] !== 'model' && option['id'] !== 'model') continue;
    const value = option['currentValue'];
    if (typeof value === 'string') return value;
  }
  return null;
}

/** The harness's own restriction: its reported mode's name, if one is reported (R10). */
export function reportedModeName(view: AgentView): string | null {
  const id = view.currentModeId;
  if (id === null) return null;
  const modes = view.modes?.['availableModes'];
  if (Array.isArray(modes)) {
    for (const mode of modes as unknown[]) {
      if (mode && typeof mode === 'object') {
        const entry = mode as Record<string, unknown>;
        if (entry['id'] === id && typeof entry['name'] === 'string') return entry['name'];
      }
    }
  }
  return id;
}

/** A tool call's raw input as the runtime masked it, short enough for the panel. */
export function rawInputPreview(rawInput: unknown): string | null {
  if (rawInput === undefined || rawInput === null) return null;
  let text: string;
  try {
    text = typeof rawInput === 'string' ? rawInput : JSON.stringify(rawInput);
  } catch {
    return null;
  }
  return text.length > PERMISSION_INPUT_PREVIEW_MAX
    ? `${text.slice(0, PERMISSION_INPUT_PREVIEW_MAX)}…`
    : text;
}

/** "used / size (n%)", then the cost when the adapter reports one (R12: only what is reported). */
export function usageText(view: AgentView): string | null {
  const usage = view.usage;
  if (!usage || usage.size <= 0) return null;
  const pct = Math.round((usage.used / usage.size) * 100);
  const cost = usage.cost ? ` · ${T.cost} ${usage.cost.amount} ${usage.cost.currency}` : '';
  return `${usage.used} / ${usage.size} (${pct}%)${cost}`;
}
