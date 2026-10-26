/** What an agent's controls allow, and how its card marks it. Pure, for the Node test runner. */
import type { AgentStatus, Readiness } from '../../../core/src/messages.js';
import type { AgentView } from './agentView.js';
import { T } from './strings.js';

/** The status dot's token (ux.md 상태 어휘: inherited status tokens). */
export const STATUS_DOT_CLASS: Record<AgentStatus, string> = {
  offline: 'bg-border',
  starting: 'bg-status-active',
  ready: 'bg-status-success',
  running: 'bg-status-active',
  awaiting_permission: 'bg-status-permission',
  cancelling: 'bg-status-permission',
  error: 'bg-status-error',
};

/** "에이전트 시작" only where it can start: no process, and a readiness that runs (ux.md 설정 상태). */
export function canStart(view: AgentView): boolean {
  const startable: Readiness[] = ['configured', 'auth_required'];
  return (
    (view.status === 'offline' || view.status === 'error') &&
    startable.includes(view.agent.readiness)
  );
}

/**
 * "연결 확인" where the runtime takes `checkConnection`: no process (ADR 0005
 * decision 4). It is also the way back from a readiness that cannot start, such
 * as `unsupported` after an adapter that did not offer the sign-in method (ux.md 설정 상태).
 */
export function canCheck(view: AgentView): boolean {
  return view.status === 'offline' || view.status === 'error';
}

/** "에이전트 종료" whenever there is a process (ADR 0005: `stopAgent` outside offline and error). */
export function canStop(view: AgentView): boolean {
  return view.status !== 'offline' && view.status !== 'error';
}

/**
 * The harness badge: the name an adapter last reported (agentInfo.title, else
 * agentInfo.name when it has no title; kept after its session ended), else the
 * harness id before any report (ux.md 에이전트 카드).
 */
export function harnessBadge(view: AgentView): string {
  const info = view.reportedAgentInfo;
  return info?.title ?? info?.name ?? view.agent.harnessId;
}

/** What names an agent on screen (`agentIdentity`). */
export interface AgentIdentity {
  /** The agent's name, as saved. */
  name: string;
  /** The harness badge beside the name; null when the name already says it. */
  badge: string | null;
  /** The name's tooltip ("실행기: Codex") when the badge is left out, else null. */
  nameTitle: string | null;
}

/** Same words, ignoring the blanks around them and case. */
function sameName(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

/**
 * The one rule for every place that names an agent next to its harness: the
 * character label, the agent card and the Detail Panel header (ux.md 에이전트
 * 이름 식별, SCRUM-27). The name, then the harness badge, unless the name is
 * the badge's text (trimmed, ignoring case): the seeded "Codex" agent of the
 * Codex harness reads "Codex", not "Codex Codex", and keeps the harness in
 * the name's tooltip. A name that differs from its harness keeps the badge.
 */
export function agentIdentity(view: AgentView): AgentIdentity {
  const { name } = view.agent;
  const badge = harnessBadge(view);
  if (sameName(name, badge)) {
    return { name, badge: null, nameTitle: `${T.harnessLabel}: ${badge}` };
  }
  return { name, badge, nameTitle: null };
}
