/**
 * Which answer of the runtime belongs to which command the panels sent, so a
 * refused command is never silent (ux.md 문구 목록, 거절된 명령). The store
 * keeps only the latest `commandRejected` and the latest `connectionChecked`
 * per agent (runtimeState.ts); a command's answer is the first of those that
 * is newer than what the store held when the command went out. Pure, for the
 * Node test runner.
 */
import type {
  CommandRejected,
  ConnectionChecked,
  RuntimeClientMessage,
} from '../../../core/src/messages.js';
import type { RuntimeState } from '../runtimeState.js';

/**
 * What a command is about: one agent, or one permission request. The runtime
 * echoes the ids it received (ADR 0005 frame table), so a rejection names the
 * same thing. `openProject` and the other project commands are the project
 * panel's (ProjectPanel.tsx).
 */
export type CommandSubject = `agent:${string}` | `permission:${string}`;

export function commandSubject(command: RuntimeClientMessage): CommandSubject | null {
  switch (command.type) {
    case 'respondPermission':
      return `permission:${command.permissionId}`;
    case 'checkConnection':
    case 'startAgent':
    case 'stopAgent':
    case 'sendPrompt':
    case 'cancelRun':
      return `agent:${command.agentId}`;
    default:
      return null;
  }
}

function rejectionSubject(rejected: CommandRejected): CommandSubject | null {
  if (rejected.command === 'respondPermission') {
    return rejected.permissionId === undefined ? null : `permission:${rejected.permissionId}`;
  }
  return rejected.agentId === undefined ? null : `agent:${rejected.agentId}`;
}

/** What the store held for a subject when its last command went out. */
export interface SentMark {
  rejected: CommandRejected | null;
  /** For `checkConnection`: the agent's check result held then. */
  checked?: ConnectionChecked;
  command: RuntimeClientMessage['type'];
}

export type SentMarks = Readonly<Record<string, SentMark>>;

/** The mark to keep for `command`, sent while the store held `state`; null when not tracked. */
export function markSent(
  state: RuntimeState,
  command: RuntimeClientMessage,
): [CommandSubject, SentMark] | null {
  const subject = commandSubject(command);
  if (subject === null) return null;
  const mark: SentMark = { rejected: state.commandRejected, command: command.type };
  if (command.type === 'checkConnection') mark.checked = state.connectionChecks[command.agentId];
  return [subject, mark];
}

/**
 * The rejection that answered the last command sent about `subject`, if one
 * did: newer than the mark, and naming that subject. A later command clears it.
 */
export function rejectionOf(
  state: RuntimeState,
  marks: SentMarks,
  subject: CommandSubject,
): CommandRejected | null {
  const mark = marks[subject];
  const rejected = state.commandRejected;
  if (!mark || !rejected || rejected === mark.rejected) return null;
  return rejectionSubject(rejected) === subject ? rejected : null;
}

/** The answer to the last "연결 확인" of `agentId`, or `pending` while it runs (null: none sent). */
export function checkOf(
  state: RuntimeState,
  marks: SentMarks,
  agentId: string,
): { pending: true } | { pending: false; result: ConnectionChecked | null } | null {
  const subject: CommandSubject = `agent:${agentId}`;
  const mark = marks[subject];
  if (!mark || mark.command !== 'checkConnection') return null;
  const result = state.connectionChecks[agentId];
  if (result !== undefined && result !== mark.checked) return { pending: false, result };
  // Refused (another check runs, the agent started): no result is coming.
  if (rejectionOf(state, marks, subject)) return { pending: false, result: null };
  return { pending: true };
}

export function agentSubject(agentId: string): CommandSubject {
  return `agent:${agentId}`;
}

export function permissionSubject(permissionId: string): CommandSubject {
  return `permission:${permissionId}`;
}
