import { type ChildProcess, spawn } from 'child_process';
import { randomBytes } from 'crypto';

import type { RuntimeEndpoint } from '../../core/src/desktopBridge.js';
import {
  DEV_ONLY_ENV,
  ENV_RUNTIME_ALLOWED_ORIGINS,
  ENV_RUNTIME_DATA_DIR,
  ENV_RUNTIME_TOKEN,
  LOG_STDOUT_PREFIX,
  RUNTIME_KILL_WAIT_MS,
  RUNTIME_QUIT_GRACE_MS,
  RUNTIME_TOKEN_BYTES,
  RUNTIME_WS_HOST,
  RUNTIME_WS_PATH,
} from './constants.js';
import { killProcessTree } from './processTree.js';
import { LineSplitter, parseReadyLine } from './readyLine.js';
import { RuntimeCommandError } from './runtimeCommand.js';
import type { RuntimeFailure, RuntimeFailureKind } from './runtimeFailure.js';
import type { RuntimeLog } from './runtimeLog.js';

export interface RuntimeSupervisorOptions {
  log: RuntimeLog;
  /** `AGENTS_ROOM_DATA_DIR`; the runtime keeps its files under `<dataDir>/runtime/`. */
  dataDir: string;
  /** `AGENTS_ROOM_ALLOWED_ORIGINS`: the renderer origins the runtime's `/ws` accepts. */
  allowedOrigins: readonly string[];
  readyTimeoutMs: number;
  /** Builds the launch argv; throws `RuntimeCommandError` when there is none. */
  resolveCommand: () => string[];
  baseEnv: NodeJS.ProcessEnv;
  /** Called once per failed start or unexpected exit, never while stopping. */
  onFailure: (failure: RuntimeFailure) => void;
}

/** One launched runtime process. A restart is a new attempt with a new token. */
interface Attempt {
  child: ChildProcess;
  token: string;
  ready: boolean;
  /** Its failure was reported; later events of this process are ignored. */
  reported: boolean;
  readyTimer: ReturnType<typeof setTimeout> | null;
}

function isAlive(child: ChildProcess): boolean {
  return child.pid !== undefined && child.exitCode === null && child.signalCode === null;
}

function waitForExit(child: ChildProcess): Promise<void> {
  if (!isAlive(child)) return Promise.resolve();
  return new Promise((resolve) => child.once('exit', () => resolve()));
}

/** Resolves `true` when `promise` settles within `ms`, `false` on timeout. */
function within(promise: Promise<void>, ms: number): Promise<boolean> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), ms);
    void promise.then(() => {
      clearTimeout(timer);
      resolve(true);
    });
  });
}

/**
 * Starts and watches the local runtime (ADR 0001 decisions 3 and 5).
 *
 * The shared handshake: the shell passes a fresh token, the data dir and the
 * Origin allowlist in the env, keeps stdin open for the runtime's lifetime,
 * and waits for exactly one stdout line, `AGENTS_ROOM_READY {"port":N}`.
 * Everything else the runtime writes goes to `runtime.log`, masked. The
 * endpoint (`{url, token}`) is handed out only after the ready line.
 */
export class RuntimeSupervisor {
  private attempt: Attempt | null = null;
  private endpoint: RuntimeEndpoint | null = null;
  private waiters: Array<(endpoint: RuntimeEndpoint) => void> = [];
  private stopping = false;

  constructor(private readonly options: RuntimeSupervisorOptions) {}

  /**
   * Resolves with the endpoint of a ready runtime. While none is ready (still
   * starting, failed, restarting) the promise stays pending: the renderer's
   * transport asks again before every reconnect, and a restarted runtime has a
   * new port and token.
   */
  waitForEndpoint(): Promise<RuntimeEndpoint> {
    if (this.endpoint) return Promise.resolve(this.endpoint);
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  /** Starts a runtime unless one is already running. Also the "다시 시작" action. */
  start(): void {
    if (this.stopping) return;
    const previous = this.attempt;
    if (previous && !previous.reported && isAlive(previous.child)) return;
    if (previous && isAlive(previous.child)) void killProcessTree(previous.child);
    this.attempt = null;
    this.endpoint = null;

    const { log } = this.options;
    let argv: string[];
    try {
      argv = this.options.resolveCommand();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.shell(`cannot launch the runtime: ${message}`);
      const kind: RuntimeFailureKind =
        err instanceof RuntimeCommandError && err.problem === 'invalid'
          ? 'bad-command'
          : 'no-command';
      this.options.onFailure({ kind, exitCode: null, signal: null });
      return;
    }

    const token = randomBytes(RUNTIME_TOKEN_BYTES).toString('base64url');
    log.addSecret(token);
    log.shell(`starting the runtime: ${argv.join(' ')}`);

    const env: NodeJS.ProcessEnv = { ...this.options.baseEnv };
    // Shell-only knobs. The runtime never sees them.
    for (const name of DEV_ONLY_ENV) delete env[name];
    env[ENV_RUNTIME_TOKEN] = token;
    env[ENV_RUNTIME_DATA_DIR] = this.options.dataDir;
    env[ENV_RUNTIME_ALLOWED_ORIGINS] = this.options.allowedOrigins.join(',');

    let child: ChildProcess;
    try {
      child = spawn(argv[0], argv.slice(1), {
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
        // Detached on every platform:
        // - POSIX: its own process group, so the whole tree can be signalled on quit.
        // - Windows: outside libuv's kill-on-close job object. Inside it, a shell that
        //   crashes or is force-ended takes the runtime down at once, before the
        //   runtime has cleaned up its adapters on stdin EOF (they would be orphaned).
        //   The quit path still ends the tree with taskkill /T.
        detached: true,
      });
    } catch (err) {
      log.shell(`cannot spawn the runtime: ${err instanceof Error ? err.message : String(err)}`);
      this.options.onFailure({ kind: 'spawn', exitCode: null, signal: null });
      return;
    }
    // One line per launched process: the record of every start, and the PID to find it by.
    if (child.pid !== undefined) log.shell(`the runtime started (pid ${child.pid})`);
    const attempt: Attempt = { child, token, ready: false, reported: false, readyTimer: null };
    this.attempt = attempt;
    this.watch(attempt);
  }

  /**
   * Quit path: close stdin (the runtime exits on EOF and cleans up its own
   * children), and kill the runtime's process tree if it is still alive after
   * the grace period.
   */
  async stop(): Promise<void> {
    this.stopping = true;
    this.endpoint = null;
    const attempt = this.attempt;
    if (!attempt) return;
    this.clearReadyTimer(attempt);
    const { child } = attempt;
    if (!isAlive(child)) return;
    const exited = waitForExit(child);
    this.options.log.shell('stopping the runtime: closing its stdin');
    child.stdin?.end();
    if (await within(exited, RUNTIME_QUIT_GRACE_MS)) return;
    this.options.log.shell('the runtime is still running; killing its process tree');
    await killProcessTree(child);
    await within(exited, RUNTIME_KILL_WAIT_MS);
  }

  private watch(attempt: Attempt): void {
    const { child } = attempt;
    const { log } = this.options;
    const isCurrent = (): boolean => this.attempt === attempt;

    // Writing to a runtime that already exited must not crash the main process.
    child.stdin?.on('error', () => undefined);

    const stdout = new LineSplitter();
    child.stdout?.setEncoding('utf8');
    child.stdout?.on('data', (chunk: string) => {
      for (const line of stdout.push(chunk)) this.onStdoutLine(attempt, line);
    });
    child.stdout?.on('end', () => {
      const rest = stdout.flush();
      if (rest !== null) this.onStdoutLine(attempt, rest);
    });

    const stderr = new LineSplitter();
    child.stderr?.setEncoding('utf8');
    child.stderr?.on('data', (chunk: string) => {
      for (const line of stderr.push(chunk)) log.write(line);
    });
    child.stderr?.on('end', () => {
      const rest = stderr.flush();
      if (rest !== null) log.write(rest);
    });

    child.on('error', (err) => {
      log.shell(`runtime process error: ${err.message}`);
      if (isCurrent() && !attempt.ready) {
        this.fail(attempt, { kind: 'spawn', exitCode: null, signal: null });
      }
    });

    child.on('exit', (code, signal) => {
      log.shell(`the runtime exited (code ${String(code)}, signal ${String(signal)})`);
      this.clearReadyTimer(attempt);
      if (!isCurrent()) return;
      this.endpoint = null;
      if (this.stopping) return;
      this.fail(attempt, {
        kind: attempt.ready ? 'exit' : 'exit-before-ready',
        exitCode: code,
        signal,
      });
    });

    attempt.readyTimer = setTimeout(() => {
      attempt.readyTimer = null;
      if (!isCurrent() || attempt.ready || attempt.reported) return;
      log.shell(`no ready line within ${this.options.readyTimeoutMs} ms`);
      this.fail(attempt, { kind: 'timeout', exitCode: null, signal: null });
      void killProcessTree(child);
    }, this.options.readyTimeoutMs);
  }

  private onStdoutLine(attempt: Attempt, line: string): void {
    const port = attempt.ready ? null : parseReadyLine(line);
    if (port === null) {
      // stdout is reserved for the ready line; anything else is logged, never parsed.
      this.options.log.write(`${LOG_STDOUT_PREFIX} ${line}`);
      return;
    }
    if (this.attempt !== attempt || attempt.reported || this.stopping) return;
    attempt.ready = true;
    this.clearReadyTimer(attempt);
    const endpoint: RuntimeEndpoint = {
      url: `ws://${RUNTIME_WS_HOST}:${port}${RUNTIME_WS_PATH}`,
      token: attempt.token,
    };
    this.endpoint = endpoint;
    this.options.log.shell(`the runtime is ready on port ${port}`);
    const waiters = this.waiters;
    this.waiters = [];
    for (const resolve of waiters) resolve(endpoint);
  }

  private fail(attempt: Attempt, failure: RuntimeFailure): void {
    if (attempt.reported) return;
    attempt.reported = true;
    this.clearReadyTimer(attempt);
    if (this.stopping) return;
    this.options.log.shell(`runtime failure: ${failure.kind}`);
    this.options.onFailure(failure);
  }

  private clearReadyTimer(attempt: Attempt): void {
    if (attempt.readyTimer !== null) {
      clearTimeout(attempt.readyTimer);
      attempt.readyTimer = null;
    }
  }
}
