/** What an agent's controls allow, and how its card marks it. Pure, for the Node test runner. */
import type { AgentStatus, Readiness } from '../../../core/src/messages.js';
import type { AgentView } from './agentView.js';

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
 * The harness badge: the name an adapter last reported (agentInfo.title, kept
 * after its session ended), else the harness id before any report (ux.md 에이전트 카드).
 */
export function harnessBadge(view: AgentView): string {
  const info = view.reportedAgentInfo;
  return info?.title ?? info?.name ?? view.agent.harnessId;
}
