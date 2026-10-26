import * as fs from 'fs';
import * as path from 'path';

import { ENV_RUNTIME_COMMAND, RUNTIME_JVM_ARGS } from './constants.js';

/** Why no launch command could be built. Each maps to a cause line in the failure dialog. */
export type RuntimeCommandProblem = 'missing' | 'invalid';

export class RuntimeCommandError extends Error {
  constructor(
    readonly problem: RuntimeCommandProblem,
    message: string,
  ) {
    super(message);
    this.name = 'RuntimeCommandError';
  }
}

/** The dev launch descriptor the runtime's Gradle build writes (`runtime/build/dev-runtime.json`). */
interface DevRuntimeDescriptor {
  java: string;
  jar: string;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function parseCommandOverride(raw: string): string[] {
  let argv: unknown;
  try {
    argv = JSON.parse(raw);
  } catch {
    throw new RuntimeCommandError('invalid', `${ENV_RUNTIME_COMMAND} is not valid JSON`);
  }
  if (!Array.isArray(argv) || argv.length === 0 || !argv.every(isNonEmptyString)) {
    throw new RuntimeCommandError(
      'invalid',
      `${ENV_RUNTIME_COMMAND} must be a non-empty JSON array of strings`,
    );
  }
  return argv;
}

function parseDescriptor(descriptorPath: string, text: string): string[] {
  let descriptor: unknown;
  try {
    descriptor = JSON.parse(text);
  } catch {
    throw new RuntimeCommandError('invalid', `${descriptorPath} is not valid JSON`);
  }
  const { java, jar } = (descriptor ?? {}) as Partial<DevRuntimeDescriptor>;
  if (!isNonEmptyString(java) || !isNonEmptyString(jar)) {
    throw new RuntimeCommandError('invalid', `${descriptorPath} needs "java" and "jar" strings`);
  }
  if (!path.isAbsolute(java) || !path.isAbsolute(jar)) {
    throw new RuntimeCommandError('invalid', `${descriptorPath} paths must be absolute`);
  }
  return [java, ...RUNTIME_JVM_ARGS, '-jar', jar];
}

/**
 * Builds the runtime's launch argv, in order of precedence:
 * 1. `override`: the `AGENTS_ROOM_RUNTIME_COMMAND` value (a JSON argv array, used
 *    as-is; tests and debugging). The caller passes it only for an unpackaged app
 *    (`readDevOverrides`).
 * 2. the dev descriptor (`{java, jar}`, launched with the UTF-8 JVM flags),
 * 3. otherwise a `missing` error. A packaged app has no descriptor yet (Sprint 2).
 */
export function resolveRuntimeCommand(
  override: string | undefined,
  descriptorPath: string | null,
): string[] {
  if (override !== undefined && override.trim() !== '') return parseCommandOverride(override);

  if (descriptorPath === null) {
    throw new RuntimeCommandError('missing', 'no runtime launch command is configured');
  }
  let text: string;
  try {
    text = fs.readFileSync(descriptorPath, 'utf8');
  } catch {
    throw new RuntimeCommandError('missing', `${descriptorPath} not found`);
  }
  return parseDescriptor(descriptorPath, text);
}
