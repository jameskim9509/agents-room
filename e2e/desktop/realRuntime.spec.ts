/**
 * Desktop shell + the real local runtime: the first end-to-end check of the
 * shared handshake (docs/architecture.md "셸과 런타임의 연결"). The shell
 * launches the boot jar that runtime/build/dev-runtime.json names, exactly as
 * in development; nothing is faked on either side.
 *
 * Needs `npm run runtime:build` (or `npm run runtime:assemble`, which skips
 * the runtime tests). Without its output the suite is skipped locally and
 * fails in CI, which builds the jar first.
 */
import { expect, test } from './fixtures/desktop';
import {
  DEV_RUNTIME_DESCRIPTOR,
  enableTestHooks,
  expectOfficeConnected,
  isProcessAlive,
  javaProcessesRunning,
  killAppAbruptly,
  readDevRuntimeDescriptor,
  readRuntimeLog,
  rendererRuntimeState,
  runtimeStarts,
  webSocketHandshakeStatus,
} from './helpers/desktop';

type BridgeWindow = Window & {
  agentsRoom: { getRuntimeEndpoint(): Promise<{ url: string; token: string }> };
};

const descriptor = readDevRuntimeDescriptor();
const IN_CI = process.env['CI'] !== undefined && process.env['CI'] !== '';

/** A cold JVM and Spring context on a slow CI machine; the shell itself waits 30 s. */
const READY_WAIT_MS = 30_000;
/** The runtime's own exit on stdin EOF, on a slow CI machine. */
const PROCESS_EXIT_WAIT_MS = 15_000;
/** Negative check: long enough for a rejected command's answer to arrive. */
const NO_REJECTION_WAIT_MS = 1_000;
const READY_LINE_LOGGED = /\[shell\] the runtime is ready on port (\d+)/;
// The shell's own runtime.log lines (desktop/src/runtimeSupervisor.ts).
const LOG_TREE_KILL = '[shell] the runtime is still running; killing its process tree';
const LOG_RUNTIME_EXITED = '[shell] the runtime exited';

test.describe('Desktop shell with the real local runtime', () => {
  test.skip(
    descriptor === null && !IN_CI,
    `${DEV_RUNTIME_DESCRIPTOR} (or the java and jar it names) is missing: run npm run runtime:build (or runtime:assemble) first`,
  );

  test.beforeEach(() => {
    expect(descriptor, `CI must run npm run runtime:assemble before e2e:desktop`).not.toBeNull();
  });

  test('the shell and the runtime complete the handshake, the renderer gets the snapshot, and quitting leaves no java process @area:desktop-runtime', async ({
    desktop,
  }) => {
    const jar = descriptor!.jar;
    const javaBefore = javaProcessesRunning(jar);
    const session = await desktop.launch({ realRuntime: true });
    const { app, window: page } = session;

    // Ready line: the shell read the port from the runtime's stdout.
    await expect
      .poll(() => readRuntimeLog(session), { timeout: READY_WAIT_MS })
      .toMatch(READY_LINE_LOGGED);
    const port = Number(READY_LINE_LOGGED.exec(readRuntimeLog(session))![1]);
    const [runtimePid] = runtimeStarts(session);
    expect(javaProcessesRunning(jar)).toContain(runtimePid);

    // The renderer joined from app://bundle with the token: the runtime admits nothing else.
    await expectOfficeConnected(page);
    await expect(page).toHaveURL('app://bundle/index.html');
    const endpoint = await page.evaluate(() =>
      (window as unknown as BridgeWindow).agentsRoom.getRuntimeEndpoint(),
    );
    expect(endpoint.url).toBe(`ws://127.0.0.1:${port}/ws`);
    const tokened = `${endpoint.url}?token=${endpoint.token}`;
    expect(await webSocketHandshakeStatus(endpoint.url)).toBe(401);
    expect(await webSocketHandshakeStatus(`${endpoint.url}?token=${'x'.repeat(43)}`)).toBe(401);
    expect(await webSocketHandshakeStatus(tokened, { Origin: 'http://127.0.0.1:9' })).toBe(403);
    expect(await webSocketHandshakeStatus(tokened, { Origin: 'app://bundle' })).toBe(101);

    // The connect-time snapshot reached the renderer's runtime state: a fresh
    // data dir has no project yet, so no agents.
    await enableTestHooks(page);
    await expectOfficeConnected(page);
    await expect.poll(async () => (await rendererRuntimeState(page))?.snapshots).toBe(1);
    expect(await rendererRuntimeState(page)).toMatchObject({
      currentProjectId: null,
      recentProjects: [],
      project: null,
      agents: [],
    });
    // The office sent the runtime nothing it rejects (it would reject any upstream command).
    await page.waitForTimeout(NO_REJECTION_WAIT_MS);
    expect((await rendererRuntimeState(page))?.commandRejected).toBeNull();

    // The token appears nowhere in runtime.log (the runtime never logs it; the shell masks it).
    expect(readRuntimeLog(session)).not.toContain(endpoint.token);

    await app.close();

    // It exited on stdin EOF by itself, before the shell's 3 s grace ran out.
    const log = readRuntimeLog(session);
    expect(log).toContain(`${LOG_RUNTIME_EXITED} (code 0`);
    expect(log).not.toContain(LOG_TREE_KILL);
    expect(isProcessAlive(runtimePid)).toBe(false);
    expect(javaProcessesRunning(jar).filter((pid) => !javaBefore.includes(pid))).toEqual([]);
  });

  test('a shell that ends abruptly leaves no java process: the runtime notices and exits by itself @area:desktop-runtime', async ({
    desktop,
  }) => {
    const jar = descriptor!.jar;
    const javaBefore = javaProcessesRunning(jar);
    const session = await desktop.launch({ realRuntime: true });
    await expect
      .poll(() => readRuntimeLog(session), { timeout: READY_WAIT_MS })
      .toMatch(READY_LINE_LOGGED);
    await expectOfficeConnected(session.window);
    const [runtimePid] = runtimeStarts(session);

    // No before-quit, no tree kill: only the runtime's own watches (stdin EOF, parent exit) end it.
    await killAppAbruptly(session);

    await expect
      .poll(() => isProcessAlive(runtimePid), { timeout: PROCESS_EXIT_WAIT_MS })
      .toBe(false);
    expect(javaProcessesRunning(jar).filter((pid) => !javaBefore.includes(pid))).toEqual([]);
  });
});
