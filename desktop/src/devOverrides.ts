import { DEV_ONLY_ENV } from './constants.js';

export type DevOnlyEnvName = (typeof DEV_ONLY_ENV)[number];

export interface DevOverrides {
  /** The knobs that are set and honoured. Always empty in a packaged app. */
  readonly values: Readonly<Partial<Record<DevOnlyEnvName, string>>>;
  /** The knobs that are set but ignored because the app is packaged. */
  readonly ignored: readonly DevOnlyEnvName[];
}

/**
 * Reads the shell's test and development env knobs (`DEV_ONLY_ENV`). A
 * packaged app honours none of them (ADR 0001 decision 5). An empty value
 * counts as unset.
 */
export function readDevOverrides(env: NodeJS.ProcessEnv, isPackaged: boolean): DevOverrides {
  const values: Partial<Record<DevOnlyEnvName, string>> = {};
  const ignored: DevOnlyEnvName[] = [];
  for (const name of DEV_ONLY_ENV) {
    const raw = env[name];
    if (raw === undefined || raw === '') continue;
    if (isPackaged) ignored.push(name);
    else values[name] = raw;
  }
  return { values, ignored };
}
