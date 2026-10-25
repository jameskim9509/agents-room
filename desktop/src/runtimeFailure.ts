import type { MessageBoxOptions } from 'electron';

import {
  APP_TITLE,
  COPY_CAUSE_BAD_COMMAND,
  COPY_CAUSE_EXIT,
  COPY_CAUSE_EXIT_BEFORE_READY,
  COPY_CAUSE_NO_COMMAND,
  COPY_CAUSE_SPAWN,
  COPY_CAUSE_TIMEOUT,
  COPY_EXIT_CODE,
  COPY_LOG_PATH,
  COPY_QUIT,
  COPY_RESTART,
  COPY_RUNTIME_FAILED,
} from './constants.js';

/** Why the runtime is not (or no longer) available. */
export type RuntimeFailureKind =
  'no-command' | 'bad-command' | 'spawn' | 'timeout' | 'exit-before-ready' | 'exit';

export interface RuntimeFailure {
  kind: RuntimeFailureKind;
  exitCode: number | null;
  signal: string | null;
}

/** Index of "다시 시작" in the dialog's buttons; the other button quits. */
export const RESTART_BUTTON_INDEX = 0;
const QUIT_BUTTON_INDEX = 1;

function withExitStatus(cause: string, failure: RuntimeFailure): string {
  if (failure.exitCode !== null) return `${cause} (${COPY_EXIT_CODE} ${failure.exitCode})`;
  if (failure.signal !== null) return `${cause} (${failure.signal})`;
  return cause;
}

/** One line for the dialog that says what went wrong, in the user's words. */
export function failureCause(failure: RuntimeFailure): string {
  switch (failure.kind) {
    case 'no-command':
      return COPY_CAUSE_NO_COMMAND;
    case 'bad-command':
      return COPY_CAUSE_BAD_COMMAND;
    case 'spawn':
      return COPY_CAUSE_SPAWN;
    case 'timeout':
      return COPY_CAUSE_TIMEOUT;
    case 'exit-before-ready':
      return withExitStatus(COPY_CAUSE_EXIT_BEFORE_READY, failure);
    case 'exit':
      return withExitStatus(COPY_CAUSE_EXIT, failure);
  }
}

/**
 * The native dialog for a runtime that failed to start or exited (D25):
 * "다시 시작" starts a new runtime once, "종료" quits. There is no automatic
 * restart loop.
 */
export function failureDialogOptions(failure: RuntimeFailure, logPath: string): MessageBoxOptions {
  return {
    type: 'error',
    title: APP_TITLE,
    message: COPY_RUNTIME_FAILED,
    detail: `${failureCause(failure)}\n${COPY_LOG_PATH}: ${logPath}`,
    buttons: [COPY_RESTART, COPY_QUIT],
    defaultId: RESTART_BUTTON_INDEX,
    cancelId: QUIT_BUTTON_INDEX,
    noLink: true,
  };
}
