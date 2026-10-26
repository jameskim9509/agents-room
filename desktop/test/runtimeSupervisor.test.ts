/**
 * RuntimeSupervisor against tiny inline Node runtimes: the shared handshake
 * (env, ready line, stdin), the failure kinds, and the quit path.
 */
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { afterEach, describe, expect, it } from 'vitest';

import { RuntimeCommandError } from '../src/runtimeCommand.js';
import type { RuntimeFailure } from '../src/runtimeFailure.js';
import { RuntimeLog } from '../src/runtimeLog.js';
import { RuntimeSupervisor } from '../src/runtimeSupervisor.js';

/** Exits on stdin EOF, like the real runtime. */
const EXIT_ON_EOF = 'process.stdin.on("end", () => process.exit(0)); process.stdin.resume();';
const READY = (port: number, eol = '\\n') =>
  `process.stdout.write('AGENTS_ROOM_READY {"port":${port}}${eol}');`;
const ECHO_ENV = [
  'const e = process.env;',
  'console.error("token=" + e.AGENTS_ROOM_TOKEN);',
  'console.error("data=" + e.AGENTS_ROOM_DATA_DIR);',
  'console.error("origins=" + e.AGENTS_ROOM_ALLOWED_ORIGINS);',
  'console.error("command-set=" + (e.AGENTS_ROOM_RUNTIME_COMMAND !== undefined));',
  // Built at run time, so the shell's own "starting the runtime: <argv>" line can't match it.
  'console.error("echo-" + "done");',
].join(' ');

const cleanups: Array<() => Promise<void> | void> = [];
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) await cleanup();
});

function setup(script: string | null, opts: { readyTimeoutMs?: number } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-supervisor-'));
  const log = new RuntimeLog(path.join(dir, 'logs'));
  const failures: RuntimeFailure[] = [];
  const supervisor = new RuntimeSupervisor({
    log,
    dataDir: dir,
    allowedOrigins: ['app://bundle'],
    readyTimeoutMs: opts.readyTimeoutMs ?? 10_000,
    resolveCommand: () => {
      if (script === null) throw new RuntimeCommandError('missing', 'no command');
      return [process.execPath, '-e', script];
    },
    baseEnv: { ...process.env, AGENTS_ROOM_RUNTIME_COMMAND: '["leaks?"]' },
    onFailure: (failure) => failures.push(failure),
  });
  cleanups.push(async () => {
    await supervisor.stop();
    await log.close();
    fs.rmSync(dir, { recursive: true, force: true });
  });
  // The log stream opens asynchronously; until it has, there is nothing to read.
  const readLog = () => (fs.existsSync(log.path) ? fs.readFileSync(log.path, 'utf8') : '');
  return { supervisor, failures, readLog, dir };
}

async function until(check: () => boolean, timeoutMs = 10_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() > deadline) throw new Error('condition not met in time');
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

describe('RuntimeSupervisor', () => {
  it('hands out the endpoint only after the ready line, with a fresh token in the env', async () => {
    const { supervisor, failures, readLog, dir } = setup(
      `${ECHO_ENV} ${READY(4321)} ${EXIT_ON_EOF}`,
    );
    supervisor.start();

    const endpoint = await supervisor.waitForEndpoint();

    expect(endpoint.url).toBe('ws://127.0.0.1:4321/ws');
    expect(Buffer.from(endpoint.token, 'base64url')).toHaveLength(32);
    await until(() => readLog().includes('echo-done'));
    const log = readLog();
    expect(log).not.toContain(endpoint.token);
    expect(log).toContain('token=***');
    expect(log).toContain(`data=${dir}`);
    expect(log).toContain('origins=app://bundle');
    // Every launch is on record with its PID (the e2e suite counts starts by it).
    expect(log).toMatch(/\[shell\] the runtime started \(pid \d+\)/);
    // Shell-only knobs never reach the runtime.
    expect(log).toContain('command-set=false');
    expect(failures).toEqual([]);
  });

  it('accepts a CRLF ready line', async () => {
    const { supervisor } = setup(`${READY(4322, '\\r\\n')} ${EXIT_ON_EOF}`);
    supervisor.start();
    expect((await supervisor.waitForEndpoint()).url).toBe('ws://127.0.0.1:4322/ws');
  });

  it('reports a missing launch command without spawning anything', () => {
    const { supervisor, failures } = setup(null);
    supervisor.start();
    expect(failures).toEqual([{ kind: 'no-command', exitCode: null, signal: null }]);
  });

  it('reports a runtime that exits before the ready line, with its exit code', async () => {
    const { supervisor, failures } = setup('process.exit(3)');
    supervisor.start();
    await until(() => failures.length > 0);
    expect(failures).toEqual([{ kind: 'exit-before-ready', exitCode: 3, signal: null }]);
  });

  it('reports a timeout once and kills the silent runtime', async () => {
    const { supervisor, failures, readLog } = setup(`setInterval(() => {}, 1000); ${EXIT_ON_EOF}`, {
      readyTimeoutMs: 300,
    });
    supervisor.start();
    await until(() => readLog().includes('the runtime exited'));
    expect(failures).toEqual([{ kind: 'timeout', exitCode: null, signal: null }]);
  });

  it('reports an unexpected exit after ready, and a restart gets a new endpoint', async () => {
    const { supervisor, failures } = setup(
      `${READY(4323)} setTimeout(() => process.exit(7), 200);`,
    );
    supervisor.start();
    const first = await supervisor.waitForEndpoint();
    await until(() => failures.length > 0);
    expect(failures).toEqual([{ kind: 'exit', exitCode: 7, signal: null }]);

    // No endpoint while nothing runs: the renderer's next ask waits for the restart.
    let second: { url: string; token: string } | null = null;
    void supervisor.waitForEndpoint().then((endpoint) => (second = endpoint));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(second).toBeNull();

    supervisor.start();
    await until(() => second !== null);
    expect(second!.token).not.toBe(first.token);
  });

  it('stops a cooperative runtime through stdin EOF', async () => {
    const { supervisor, failures, readLog } = setup(`${READY(4324)} ${EXIT_ON_EOF}`);
    supervisor.start();
    await supervisor.waitForEndpoint();
    const started = Date.now();
    await supervisor.stop();
    expect(Date.now() - started).toBeLessThan(2_500);
    expect(readLog()).toContain('the runtime exited (code 0');
    expect(failures).toEqual([]);
  });

  it('kills a runtime that ignores stdin EOF after the grace period', async () => {
    const { supervisor, failures, readLog } = setup(
      `${READY(4325)} process.stdin.resume(); setInterval(() => {}, 1000);`,
    );
    supervisor.start();
    await supervisor.waitForEndpoint();
    await supervisor.stop();
    expect(readLog()).toContain('killing its process tree');
    expect(readLog()).toContain('the runtime exited');
    expect(failures).toEqual([]);
  }, 15_000);
});
