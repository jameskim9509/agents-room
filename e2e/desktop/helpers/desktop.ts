/**
 * Desktop shell e2e helpers: launch the built Electron shell (desktop/dist +
 * dist/webview) through Playwright's `_electron`, against the fake runtime
 * (e2e/desktop/fixtures/fake-runtime.cjs) passed in AGENTS_ROOM_RUNTIME_COMMAND,
 * or against the real runtime the shell finds through runtime/build/dev-runtime.json.
 * A real runtime can run its seeded Codex agent on the mock ACP agent
 * (e2e/desktop/fixtures/mock-acp-agent.cjs, `mockAgent`).
 *
 * Every launch gets its own HOME/USERPROFILE/APPDATA/LOCALAPPDATA, CODEX_HOME
 * and Electron userData, so the single-instance lock, runtime.log and the
 * runtime's data dir never touch a developer's real profile. Every launch sets
 * AGENTS_ROOM_E2E=1: the runtime then never starts the installed codex-acp,
 * only the adapter command a test passes (fail closed).
 */
import {
  _electron as electron,
  type ElectronApplication,
  expect,
  type Page,
} from '@playwright/test';
import { type ChildProcess, execFileSync } from 'child_process';
import crypto from 'crypto';
import fs from 'fs';
import http from 'http';
import { createRequire } from 'module';
import os from 'os';
import path from 'path';

import type { AgentEvent, RuntimeClientMessage } from '../../../core/src/messages.js' with {
  'resolution-mode': 'import',
};
import type { RuntimeState } from '../../../webview-ui/src/runtimeState.js' with {
  'resolution-mode': 'import',
};

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const DESKTOP_DIR = path.join(REPO_ROOT, 'desktop');
/** Written by `npm run runtime:build` or `runtime:assemble`; the unpackaged shell launches the runtime it names. */
export const DEV_RUNTIME_DESCRIPTOR = path.join(REPO_ROOT, 'runtime', 'build', 'dev-runtime.json');
const FAKE_RUNTIME = path.join(__dirname, '..', 'fixtures', 'fake-runtime.cjs');
const MOCK_ACP_AGENT = path.join(__dirname, '..', 'fixtures', 'mock-acp-agent.cjs');
const IS_WINDOWS = process.platform === 'win32';
/** One `<pid> <command line>` row per java.exe process. */
const WINDOWS_JAVA_PROCESSES = `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object { "$($_.ProcessId) $($_.CommandLine)" }`;
/** One `<pid> <command line>` row per process. */
const WINDOWS_PROCESSES = `Get-CimInstance Win32_Process | ForEach-Object { "$($_.ProcessId) $($_.CommandLine)" }`;
/**
 * The runtime's adapter knobs: set per launch, never inherited from a
 * developer's shell. Every harness's command override
 * (`AGENTS_ROOM_HARNESS_<ID>_COMMAND`), `AGENTS_ROOM_NODE*`, the e2e switch,
 * the adapters folder and the Codex home, in any case (Windows env names are
 * case-insensitive).
 */
const INHERITED_ADAPTER_ENV =
  /^(AGENTS_ROOM_HARNESS_.+_COMMAND|AGENTS_ROOM_NODE.*|AGENTS_ROOM_E2E|AGENTS_ROOM_ADAPTERS_DIR|CODEX_HOME)$/i;

/**
 * The Electron executable Playwright launches: the `electron` package exports
 * its path. In the shell's main process this is `process.execPath`, which the
 * shell hands the runtime as the adapters' Node (AGENTS_ROOM_NODE).
 */
export function electronExecutable(): string {
  return createRequire(path.join(DESKTOP_DIR, 'package.json'))('electron') as string;
}

/** A mock ACP agent scenario: the keys in mock-acp-agent.cjs (`extends` starts from a built-in one). */
export type MockAgentScenario = Record<string, unknown>;

/** How the fake runtime behaves for one launch. See the flag list in fake-runtime.cjs. */
export interface FakeRuntimeOptions {
  mode?: 'ready' | 'silent' | 'exit';
  exitCode?: number;
  /** --mode=exit waits until the test calls `session.openGate()`. */
  waitForGate?: boolean;
  rejectAll?: boolean;
  ignoreStdinEof?: boolean;
  spawnChild?: boolean;
}

export interface LaunchDesktopOptions {
  runtime?: FakeRuntimeOptions;
  /**
   * Launch the real runtime instead of the fake: no AGENTS_ROOM_RUNTIME_COMMAND,
   * so the shell reads DEV_RUNTIME_DESCRIPTOR itself. `runtime` is ignored.
   */
  realRuntime?: boolean;
  /** AGENTS_ROOM_READY_TIMEOUT_MS; the shell's default is 30 s. */
  readyTimeoutMs?: number;
  /**
   * With `realRuntime`: the runtime starts its Codex adapter as the mock ACP
   * agent playing this scenario, written to `<tmpBase>/mock-acp-scenario.json`.
   * The command, in AGENTS_ROOM_HARNESS_CODEX_COMMAND, is
   * `[<electron>, mock-acp-agent.cjs, --scenario-file, <file>]`: Electron is the
   * shell's own executable, so the runtime runs it as Node, as it would codex-acp.
   */
  mockAgent?: MockAgentScenario;
  /**
   * Runs before the app starts, with the launch's fresh directories: lets a
   * spec seed what a previous session would have left, such as the runtime's
   * data dir (`<userDataDir>/runtime`, architecture.md Persistence).
   */
  beforeLaunch?: (dirs: { tmpBase: string; userDataDir: string }) => void;
}

export interface DesktopSession {
  app: ElectronApplication;
  /** The Electron main process. Captured at launch: `app.process()` throws once the app is gone. */
  appProcess: ChildProcess;
  window: Page;
  tmpBase: string;
  userDataDir: string;
  /** Where the fake runtime writes fake-runtime-<pid>.json (its child's PID, for cleanup). */
  stateDir: string;
  /** `<userData>/logs/runtime.log` */
  runtimeLogPath: string;
  /**
   * The mock agent's scenario file (`mockAgent`), `null` without one. Its path
   * is unique to the launch and on every mock process's command line.
   */
  mockAgentScenarioFile: string | null;
  /** Lets a `waitForGate` runtime (and every restarted one) exit. */
  openGate: () => void;
  cleanup: () => Promise<void>;
}

export interface FakeRuntimeState {
  pid: number;
  childPid: number | null;
}

/** The launch descriptor the runtime's Gradle build writes. */
export interface DevRuntimeDescriptor {
  java: string;
  jar: string;
}

/**
 * The dev descriptor, or `null` when the runtime is not built here: no
 * descriptor, or one naming a java or jar that does not exist.
 */
export function readDevRuntimeDescriptor(): DevRuntimeDescriptor | null {
  try {
    const descriptor = JSON.parse(
      fs.readFileSync(DEV_RUNTIME_DESCRIPTOR, 'utf8'),
    ) as Partial<DevRuntimeDescriptor>;
    const { java, jar } = descriptor;
    if (typeof java !== 'string' || typeof jar !== 'string') return null;
    return fs.existsSync(java) && fs.existsSync(jar) ? { java, jar } : null;
  } catch {
    return null;
  }
}

/**
 * PIDs of the java processes whose command line runs `jar`, read from the OS
 * process table. Any java started from this jar counts, whoever started it, so
 * callers compare against a list taken before the launch.
 */
export function javaProcessesRunning(jar: string): number[] {
  // The Windows table already holds java.exe only; elsewhere the command line must name java.
  return processesRunning(jar, WINDOWS_JAVA_PROCESSES, (commandLine) =>
    IS_WINDOWS ? true : /\bjava\b/.test(commandLine),
  );
}

/**
 * PIDs of the processes whose command line contains `needle` (a path),
 * read from the OS process table. Given a launch's mock agent scenario file:
 * every mock agent process that launch's runtime started.
 */
export function processesWithArgument(needle: string): number[] {
  return processesRunning(needle, WINDOWS_PROCESSES, () => true);
}

function processesRunning(
  needle: string,
  windowsQuery: string,
  accept: (commandLine: string) => boolean,
): number[] {
  const table = IS_WINDOWS
    ? execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', windowsQuery], {
        encoding: 'utf8',
        windowsHide: true,
      })
    : execFileSync('ps', ['-eo', 'pid=,args='], { encoding: 'utf8' });
  // Windows paths are case-insensitive.
  const wanted = IS_WINDOWS ? needle.toLowerCase() : needle;
  const pids: number[] = [];
  for (const line of table.split(/\r?\n/)) {
    const match = /^\s*(\d+)\s+(.*)$/.exec(line);
    if (!match) continue;
    const commandLine = IS_WINDOWS ? match[2].toLowerCase() : match[2];
    if (commandLine.includes(wanted) && accept(commandLine)) pids.push(Number(match[1]));
  }
  return pids;
}

function fakeRuntimeArgv(
  runtime: FakeRuntimeOptions,
  stateDir: string,
  gateFile: string,
): string[] {
  const argv = [process.execPath, FAKE_RUNTIME, `--state-dir=${stateDir}`];
  if (runtime.mode) argv.push(`--mode=${runtime.mode}`);
  if (runtime.exitCode !== undefined) argv.push(`--exit-code=${runtime.exitCode}`);
  if (runtime.waitForGate) argv.push(`--gate=${gateFile}`);
  if (runtime.rejectAll) argv.push('--reject-all');
  if (runtime.ignoreStdinEof) argv.push('--ignore-stdin-eof');
  if (runtime.spawnChild) argv.push('--spawn-child');
  return argv;
}

function isolatedEnv(
  tmpHome: string,
  userDataDir: string,
  runtimeArgv: string[] | null,
  readyTimeoutMs: number | undefined,
  adapterArgv: string[] | null,
): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined) env[key] = value;
  }
  // A developer's `desktop:dev` shell must not leak dev mode, or a runtime command, into the test.
  delete env['AGENTS_ROOM_RENDERER_URL'];
  delete env['AGENTS_ROOM_RUNTIME_COMMAND'];
  delete env['ELECTRON_RUN_AS_NODE'];
  for (const name of Object.keys(env)) {
    if (INHERITED_ADAPTER_ENV.test(name)) delete env[name];
  }
  // The runtime never starts the installed adapter in e2e (fail closed), and
  // an adapter never sees a developer's Codex home.
  env['AGENTS_ROOM_E2E'] = '1';
  env['CODEX_HOME'] = path.join(tmpHome, 'codex-home');
  if (adapterArgv) env['AGENTS_ROOM_HARNESS_CODEX_COMMAND'] = JSON.stringify(adapterArgv);
  env['HOME'] = tmpHome;
  if (IS_WINDOWS) {
    env['USERPROFILE'] = tmpHome;
    env['APPDATA'] = path.join(tmpHome, 'AppData', 'Roaming');
    env['LOCALAPPDATA'] = path.join(tmpHome, 'AppData', 'Local');
    delete env['HOMEDRIVE'];
    delete env['HOMEPATH'];
    fs.mkdirSync(env['APPDATA'], { recursive: true });
    fs.mkdirSync(env['LOCALAPPDATA'], { recursive: true });
  }
  env['AGENTS_ROOM_USER_DATA_DIR'] = userDataDir;
  if (runtimeArgv) env['AGENTS_ROOM_RUNTIME_COMMAND'] = JSON.stringify(runtimeArgv);
  if (readyTimeoutMs !== undefined) env['AGENTS_ROOM_READY_TIMEOUT_MS'] = String(readyTimeoutMs);
  return env;
}

/**
 * Fake runtimes this launch started, read from their state files (a half-written
 * one is skipped). Specs use it only to find the fake's child in the OS process
 * table; how many runtimes the shell started, and their PIDs, come from
 * runtime.log (`runtimeStarts`).
 */
export function readFakeRuntimeStates(stateDir: string): FakeRuntimeState[] {
  if (!fs.existsSync(stateDir)) return [];
  const states: FakeRuntimeState[] = [];
  for (const name of fs.readdirSync(stateDir)) {
    if (!name.startsWith('fake-runtime-') || !name.endsWith('.json')) continue;
    try {
      states.push(
        JSON.parse(fs.readFileSync(path.join(stateDir, name), 'utf8')) as FakeRuntimeState,
      );
    } catch {
      // still being written
    }
  }
  return states;
}

/** runtime.log as written so far ('' before the shell has opened it). */
export function readRuntimeLog(session: DesktopSession): string {
  return fs.existsSync(session.runtimeLogPath)
    ? fs.readFileSync(session.runtimeLogPath, 'utf8')
    : '';
}

const RUNTIME_STARTED = /\[shell\] the runtime started \(pid (\d+)\)/g;

/** PIDs of the runtimes the shell launched, in order, from its "the runtime started" lines. */
export function runtimeStarts(session: DesktopSession): number[] {
  return [...readRuntimeLog(session).matchAll(RUNTIME_STARTED)].map((match) => Number(match[1]));
}

/** True while the OS still has a process with this PID. */
export function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return (err as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function forceKill(pid: number): void {
  try {
    if (IS_WINDOWS) {
      execFileSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      process.kill(pid, 'SIGKILL');
    }
  } catch {
    // already gone
  }
}

function hasExited(child: ChildProcess): boolean {
  return child.exitCode !== null || child.signalCode !== null;
}

/**
 * Ends the Electron main process the way a crash or Task Manager would: no quit
 * handlers run. The PID comes from the main process itself, because the process
 * Playwright spawned (`appProcess`) is a launcher whose child is the real main.
 * Resolves once the main process and the launcher are gone.
 */
export async function killAppAbruptly(session: DesktopSession): Promise<void> {
  const mainPid = await session.app.evaluate(() => process.pid);
  process.kill(mainPid, 'SIGKILL');
  await expect.poll(() => isProcessAlive(mainPid)).toBe(false);
  await waitForAppExit(session);
}

/** Waits until the Electron main process has exited (after a quit). */
export function waitForAppExit(session: DesktopSession): Promise<void> {
  const child = session.appProcess;
  if (hasExited(child)) return Promise.resolve();
  return new Promise((resolve) => child.once('exit', () => resolve()));
}

/**
 * The office is on screen and the renderer's WebSocket is open. The canvas
 * comes first: before React mounts there is no connection badge either, so
 * its absence alone would prove nothing.
 */
export async function expectOfficeConnected(page: Page): Promise<void> {
  await expect(page.locator('canvas').first()).toBeVisible();
  await expect(page.getByText('Connecting…')).toHaveCount(0);
  await expect(page.getByText('Reconnecting…')).toHaveCount(0);
}

export async function launchDesktop(opts: LaunchDesktopOptions = {}): Promise<DesktopSession> {
  const tmpBase = fs.realpathSync.native(
    fs.mkdtempSync(path.join(os.tmpdir(), 'agents-room-e2e-')),
  );
  const tmpHome = path.join(tmpBase, 'home');
  const userDataDir = path.join(tmpBase, 'userdata');
  const stateDir = path.join(tmpBase, 'fake-runtime');
  const gateFile = path.join(tmpBase, 'fake-runtime-gate');
  fs.mkdirSync(tmpHome, { recursive: true });
  fs.mkdirSync(userDataDir, { recursive: true });
  let mockAgentScenarioFile: string | null = null;
  if (opts.mockAgent) {
    mockAgentScenarioFile = path.join(tmpBase, 'mock-acp-scenario.json');
    fs.writeFileSync(mockAgentScenarioFile, JSON.stringify(opts.mockAgent));
  }

  opts.beforeLaunch?.({ tmpBase, userDataDir });

  const env = isolatedEnv(
    tmpHome,
    userDataDir,
    opts.realRuntime ? null : fakeRuntimeArgv(opts.runtime ?? {}, stateDir, gateFile),
    opts.readyTimeoutMs,
    mockAgentScenarioFile
      ? [electronExecutable(), MOCK_ACP_AGENT, '--scenario-file', mockAgentScenarioFile]
      : null,
  );
  // Real runtime: the JVMs running its jar before the launch are someone else's.
  const realJar = opts.realRuntime ? readDevRuntimeDescriptor()?.jar : undefined;
  const javaBefore = new Set(realJar ? javaProcessesRunning(realJar) : []);

  let app: ElectronApplication | undefined;
  let appProcess: ChildProcess | undefined;
  const cleanup = async (): Promise<void> => {
    if (app && appProcess && !hasExited(appProcess)) {
      await app.close().catch(() => undefined);
    }
    for (const state of readFakeRuntimeStates(stateDir)) {
      if (state.childPid !== null) forceKill(state.childPid);
      forceKill(state.pid);
    }
    if (realJar) {
      for (const pid of javaProcessesRunning(realJar)) if (!javaBefore.has(pid)) forceKill(pid);
    }
    if (mockAgentScenarioFile) {
      for (const pid of processesWithArgument(mockAgentScenarioFile)) forceKill(pid);
    }
    fs.rmSync(tmpBase, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  };

  try {
    app = await electron.launch({ args: [DESKTOP_DIR], env, cwd: tmpBase, timeout: 60_000 });
    appProcess = app.process();
    const window = await app.firstWindow();
    return {
      app,
      appProcess,
      window,
      tmpBase,
      userDataDir,
      stateDir,
      runtimeLogPath: path.join(userDataDir, 'logs', 'runtime.log'),
      mockAgentScenarioFile,
      openGate: () => fs.writeFileSync(gateFile, ''),
      cleanup,
    };
  } catch (err) {
    await cleanup();
    throw err;
  }
}

// ── Native dialog stubs (Playwright cannot drive OS dialogs) ─────────────────

/** What the shell passed to `dialog.showMessageBox`. */
export interface MessageBoxCall {
  type?: string;
  title?: string;
  message: string;
  detail?: string;
  buttons?: string[];
}

interface MessageBoxStubGlobals {
  __messageBoxCalls: MessageBoxCall[];
  __messageBoxReplies: Array<(response: number) => void>;
}

/**
 * Replaces `dialog.showMessageBox` in the main process. Each call is recorded
 * and stays open until the test answers it with `answerMessageBox`, the way a
 * user would click a button.
 */
export async function stubMessageBox(app: ElectronApplication): Promise<void> {
  await app.evaluate(({ dialog }) => {
    const g = globalThis as unknown as MessageBoxStubGlobals;
    g.__messageBoxCalls = [];
    g.__messageBoxReplies = [];
    dialog.showMessageBox = ((...args: unknown[]) => {
      const options = args[args.length - 1] as MessageBoxCall;
      g.__messageBoxCalls.push({
        type: options.type,
        title: options.title,
        message: options.message,
        detail: options.detail,
        buttons: options.buttons,
      });
      return new Promise((resolve) => {
        g.__messageBoxReplies.push((response) => resolve({ response, checkboxChecked: false }));
      });
    }) as typeof dialog.showMessageBox;
  });
}

export function messageBoxCalls(app: ElectronApplication): Promise<MessageBoxCall[]> {
  return app.evaluate(() => (globalThis as unknown as MessageBoxStubGlobals).__messageBoxCalls);
}

/** Clicks button `response` on the `index`-th recorded message box. */
export async function answerMessageBox(
  app: ElectronApplication,
  index: number,
  response: number,
): Promise<void> {
  await app.evaluate(
    (_electron, [i, r]) => {
      (globalThis as unknown as MessageBoxStubGlobals).__messageBoxReplies[i]?.(r);
    },
    [index, response] as const,
  );
}

// ── Renderer observations ────────────────────────────────────────────────────

/**
 * Distinct colors on a coarse grid of the office canvas. An office that drew
 * its floor, walls and furniture has many; a blank canvas has one.
 */
export function officeCanvasColorCount(window: Page): Promise<number> {
  return window.evaluate(() => {
    const canvas = document.querySelector('canvas');
    if (!canvas || canvas.width === 0 || canvas.height === 0) return 0;
    const ctx = canvas.getContext('2d');
    if (!ctx) return 0;
    const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const colors = new Set<number>();
    const step = 7;
    for (let y = 0; y < canvas.height; y += step) {
      for (let x = 0; x < canvas.width; x += step) {
        const i = (y * canvas.width + x) * 4;
        colors.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
      }
    }
    return colors.size;
  });
}

// ── Renderer test hooks and the runtime's /ws gate ───────────────────────────

/**
 * Turns on the renderer's e2e test hooks (`window.__pixelAgentsTestHooks`):
 * the flag must be set before any app script runs, so the page is reloaded.
 * The reload also opens a new runtime connection, which gets its own snapshot.
 */
export async function enableTestHooks(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { __PIXEL_AGENTS_E2E: boolean }).__PIXEL_AGENTS_E2E = true;
  });
  await page.reload();
}

/** What the renderer holds from the runtime's frames (`getRuntimeState` test hook). */
export function rendererRuntimeState(page: Page): Promise<RuntimeState | null> {
  return page.evaluate(
    () =>
      (
        window as unknown as {
          __pixelAgentsTestHooks?: { getRuntimeState?: () => RuntimeState | null };
        }
      ).__pixelAgentsTestHooks?.getRuntimeState?.() ?? null,
  );
}

/**
 * Sends a command to the runtime on the renderer's own runtime socket (the
 * `sendRuntimeCommand` test hook), as the project and agent panels will. Its
 * answers land in the renderer's runtime state (`rendererRuntimeState`).
 */
export async function sendRuntimeCommand(page: Page, command: RuntimeClientMessage): Promise<void> {
  const sent = await page.evaluate(
    (frame) =>
      (
        window as unknown as {
          __pixelAgentsTestHooks?: {
            sendRuntimeCommand?: (command: RuntimeClientMessage) => boolean;
          };
        }
      ).__pixelAgentsTestHooks?.sendRuntimeCommand?.(frame) ?? false,
    command,
  );
  expect(sent, 'the desktop renderer has the sendRuntimeCommand test hook').toBe(true);
}

/** The events the renderer holds for one agent, oldest first. */
export function agentEvents(state: RuntimeState | null, agentId: string): AgentEvent[] {
  return state?.recentEvents[agentId] ?? [];
}

/** The events of one kind, typed by it, in their order. */
export function eventsOfKind<K extends AgentEvent['kind']>(
  events: readonly AgentEvent[],
  kind: K,
): Array<Extract<AgentEvent, { kind: K }>> {
  return events.filter((event): event is Extract<AgentEvent, { kind: K }> => event.kind === kind);
}

/**
 * The agent's status as the runtime last reported it: the newest
 * `agentStatusChanged` event the renderer holds, else the snapshot's value.
 */
export function agentStatus(state: RuntimeState | null, agentId: string): string | null {
  const changes = eventsOfKind(agentEvents(state, agentId), 'agentStatusChanged');
  const last = changes[changes.length - 1];
  if (last) return last.data.status;
  return state?.agentSnapshots[agentId]?.status ?? null;
}

/**
 * Sends a WebSocket upgrade request from the test process and resolves with
 * the status: 101 when the runtime accepts it, otherwise the HTTP status it
 * refused it with. An accepted socket is closed at once.
 */
export function webSocketHandshakeStatus(
  url: string,
  headers: Record<string, string> = {},
): Promise<number> {
  return new Promise((resolve, reject) => {
    const request = http.request(url.replace(/^ws:/, 'http:'), {
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'),
        ...headers,
      },
    });
    request.on('upgrade', (response, socket) => {
      socket.destroy();
      resolve(response.statusCode ?? 101);
    });
    request.on('response', (response) => {
      response.resume();
      resolve(response.statusCode ?? 0);
    });
    request.on('error', reject);
    request.end();
  });
}

// ── The desktop panels (desktopUi/): folder picker, characters ───────────────

interface FolderPickerStubGlobals {
  __folderPickerCalls: number;
}

/**
 * Replaces `dialog.showOpenDialog` in the main process: the next pick returns
 * `folder`, or a cancel when it is `null`. Counts the calls, so a spec knows
 * the click reached the picker before it asserts that nothing changed.
 */
export async function stubFolderPicker(
  app: ElectronApplication,
  folder: string | null,
): Promise<void> {
  await app.evaluate(({ dialog }, picked) => {
    const g = globalThis as unknown as FolderPickerStubGlobals;
    g.__folderPickerCalls ??= 0;
    dialog.showOpenDialog = (async () => {
      g.__folderPickerCalls++;
      return picked === null
        ? { canceled: true, filePaths: [] }
        : { canceled: false, filePaths: [picked] };
    }) as typeof dialog.showOpenDialog;
  }, folder);
}

/** How many times the stubbed folder picker answered. */
export function folderPickerCalls(app: ElectronApplication): Promise<number> {
  return app.evaluate(
    () => (globalThis as unknown as FolderPickerStubGlobals).__folderPickerCalls ?? 0,
  );
}

/** One runtime agent's character as the world drives it (`getRuntimeAgents` test hook). */
export interface RuntimeCharacter {
  agentId: string;
  characterId: number;
  behaviour: { pose: string; tool: string | null; icon: string | null; bubble: string | null };
  engineUpdates: number;
}

/** One character as the engine holds it (`getCharacters` test hook). */
export interface EngineCharacter {
  id: number;
  bubbleType: string | null;
  state: string;
  isActive: boolean;
  currentTool: string | null;
  isRuntimeAgent?: boolean;
  pinnedToSeat?: boolean;
  isOffline?: boolean;
  activityIcon?: string | null;
}

interface PanelTestHooks {
  getRuntimeAgents?: () => RuntimeCharacter[];
  getCharacters?: () => EngineCharacter[];
  getSelectedRuntimeAgent?: () => string | null;
  getCharacterClientPoint?: (agentId: string) => { x: number; y: number } | null;
}

type PanelWindow = Window & { __pixelAgentsTestHooks?: PanelTestHooks };

/** The runtime agents the world shows, with their characters' behaviour. */
export function runtimeCharacters(page: Page): Promise<RuntimeCharacter[]> {
  return page.evaluate(
    () => (window as PanelWindow).__pixelAgentsTestHooks?.getRuntimeAgents?.() ?? [],
  );
}

/**
 * Every character the engine holds, whoever made it (`getCharacters` test
 * hook): a sub-agent character or a duplicate would show here, not in
 * `runtimeCharacters`, which lists one row per runtime agent by construction.
 */
export function engineCharacters(page: Page): Promise<EngineCharacter[]> {
  return page.evaluate(
    () => (window as PanelWindow).__pixelAgentsTestHooks?.getCharacters?.() ?? [],
  );
}

/** The engine's character for a runtime agent, or null. */
export async function engineCharacter(
  page: Page,
  agentId: string,
): Promise<EngineCharacter | null> {
  const runtime = (await runtimeCharacters(page)).find((row) => row.agentId === agentId);
  if (!runtime) return null;
  const characters = await engineCharacters(page);
  return characters.find((ch) => ch.id === runtime.characterId) ?? null;
}

/** The agent the engine has selected (the one the Detail Panel shows), or null. */
export function selectedRuntimeAgent(page: Page): Promise<string | null> {
  return page.evaluate(
    () => (window as PanelWindow).__pixelAgentsTestHooks?.getSelectedRuntimeAgent?.() ?? null,
  );
}

/** Clicks an agent's character on the office canvas with the real mouse. */
export async function clickCharacter(page: Page, agentId: string): Promise<void> {
  const point = await page.evaluate(
    (id) => (window as PanelWindow).__pixelAgentsTestHooks?.getCharacterClientPoint?.(id) ?? null,
    agentId,
  );
  expect(point, `agent ${agentId} has a character on screen`).not.toBeNull();
  await page.mouse.click(point!.x, point!.y);
}
