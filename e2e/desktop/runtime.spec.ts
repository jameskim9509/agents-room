/**
 * Desktop shell: runtime supervision (ready line, failure dialog, quit).
 * The runtime is the fake one (e2e/desktop/fixtures/fake-runtime.cjs).
 * How many runtimes the shell started, and their PIDs, come from the shell's
 * own runtime.log lines; process liveness is read from the OS. The fake's
 * state file is read only to find its child process.
 */
import { expect, test } from './fixtures/desktop';
import {
  answerMessageBox,
  expectOfficeConnected,
  isProcessAlive,
  killAppAbruptly,
  messageBoxCalls,
  readFakeRuntimeStates,
  readRuntimeLog,
  runtimeStarts,
  stubMessageBox,
  waitForAppExit,
} from './helpers/desktop';

const RUNTIME_FAILED = '로컬 런타임을 시작하지 못했습니다';
const BUTTONS = ['다시 시작', '종료'];
const RESTART = 0;
const QUIT = 1;
/** Enough for the ready timeout (5 s) plus a slow CI machine. */
const DIALOG_WAIT_MS = 20_000;
/** A tree kill, or a runtime's own cleanup on stdin EOF, on a slow CI machine. */
const PROCESS_EXIT_WAIT_MS = 15_000;
// The shell's own runtime.log lines (desktop/src/runtimeSupervisor.ts).
const LOG_TREE_KILL = '[shell] the runtime is still running; killing its process tree';
const LOG_RUNTIME_EXITED = '[shell] the runtime exited';

test.describe('Desktop shell runtime supervision', () => {
  test('a runtime that never prints the ready line is killed and gets the failure dialog, and 종료 quits @area:desktop-shell', async ({
    desktop,
  }) => {
    const session = await desktop.launch({ runtime: { mode: 'silent' }, readyTimeoutMs: 5_000 });
    const { app, window: page, runtimeLogPath } = session;
    await stubMessageBox(app);

    // The renderer is not blocked while the runtime starts.
    await expect(page.locator('canvas').first()).toBeVisible();
    await expect(page.getByText('Connecting…')).toBeVisible();

    await expect.poll(() => messageBoxCalls(app), { timeout: DIALOG_WAIT_MS }).toHaveLength(1);
    const [call] = await messageBoxCalls(app);
    expect(call).toMatchObject({ type: 'error', message: RUNTIME_FAILED, buttons: BUTTONS });
    expect(call.detail).toContain('준비 신호가 제한 시간 안에 오지 않았습니다.');
    expect(call.detail).toContain(runtimeLogPath);

    // The shell ended the silent runtime itself while the app is still running.
    const starts = runtimeStarts(session);
    expect(starts).toHaveLength(1);
    await expect
      .poll(() => isProcessAlive(starts[0]), { timeout: PROCESS_EXIT_WAIT_MS })
      .toBe(false);
    await expect.poll(() => readRuntimeLog(session)).toContain(LOG_RUNTIME_EXITED);

    await answerMessageBox(app, 0, QUIT);
    await waitForAppExit(session);
    expect(runtimeStarts(session)).toHaveLength(1);
  });

  test('a runtime that exits before ready: 다시 시작 starts exactly one new runtime, 종료 quits @area:desktop-shell', async ({
    desktop,
  }) => {
    const session = await desktop.launch({
      runtime: { mode: 'exit', exitCode: 3, waitForGate: true },
    });
    const { app } = session;
    await stubMessageBox(app);
    await expect.poll(() => runtimeStarts(session)).toHaveLength(1);

    session.openGate();
    await expect.poll(() => messageBoxCalls(app), { timeout: DIALOG_WAIT_MS }).toHaveLength(1);
    const [first] = await messageBoxCalls(app);
    expect(first).toMatchObject({ message: RUNTIME_FAILED, buttons: BUTTONS });
    expect(first.detail).toContain('런타임 프로세스가 준비 전에 종료되었습니다. (종료 코드 3)');
    // No automatic restart while the dialog waits for the user.
    expect(runtimeStarts(session)).toHaveLength(1);

    await answerMessageBox(app, 0, RESTART);
    // The new runtime finds the gate open and exits too: a second process, a second dialog.
    await expect.poll(() => messageBoxCalls(app), { timeout: DIALOG_WAIT_MS }).toHaveLength(2);
    await expect.poll(() => runtimeStarts(session)).toHaveLength(2);
    const [firstPid, secondPid] = runtimeStarts(session);
    expect(secondPid).not.toBe(firstPid);

    await answerMessageBox(app, 1, QUIT);
    await waitForAppExit(session);
    expect(runtimeStarts(session)).toHaveLength(2);
  });

  test('quitting the app ends a runtime that exits on stdin EOF @area:desktop-shell', async ({
    desktop,
  }) => {
    const session = await desktop.launch();
    await expectOfficeConnected(session.window);
    const [runtimePid] = runtimeStarts(session);
    expect(isProcessAlive(runtimePid)).toBe(true);

    await session.app.close();

    await expect.poll(() => isProcessAlive(runtimePid)).toBe(false);
    // It went on stdin EOF: the shell never needed its tree kill.
    const log = readRuntimeLog(session);
    expect(log).toContain(`${LOG_RUNTIME_EXITED} (code 0`);
    expect(log).not.toContain(LOG_TREE_KILL);
  });

  test('quitting the app kills a runtime that ignores stdin EOF, with its child process @area:desktop-shell', async ({
    desktop,
  }) => {
    const session = await desktop.launch({ runtime: { ignoreStdinEof: true, spawnChild: true } });
    await expectOfficeConnected(session.window);
    const [runtimePid] = runtimeStarts(session);
    const [state] = readFakeRuntimeStates(session.stateDir);
    expect(state.childPid).not.toBeNull();
    const childPid = state.childPid as number;
    expect(isProcessAlive(runtimePid)).toBe(true);
    expect(isProcessAlive(childPid)).toBe(true);

    await session.app.close();

    // The shell's tree kill ended the runtime before the app exited. Nothing
    // else would have: the runtime is in no job object of the shell's, and the
    // fake's child is in none of the fake's.
    const log = readRuntimeLog(session);
    const treeKill = log.indexOf(LOG_TREE_KILL);
    expect(treeKill).toBeGreaterThan(-1);
    expect(log.indexOf(LOG_RUNTIME_EXITED, treeKill)).toBeGreaterThan(treeKill);
    await expect
      .poll(() => [isProcessAlive(runtimePid), isProcessAlive(childPid)], {
        timeout: PROCESS_EXIT_WAIT_MS,
      })
      .toEqual([false, false]);
  });

  test('a shell that ends abruptly still lets the runtime clean up its child on stdin EOF @area:desktop-shell', async ({
    desktop,
  }) => {
    const session = await desktop.launch({ runtime: { spawnChild: true } });
    await expectOfficeConnected(session.window);
    const [runtimePid] = runtimeStarts(session);
    const [state] = readFakeRuntimeStates(session.stateDir);
    expect(state.childPid).not.toBeNull();
    const childPid = state.childPid as number;
    expect(isProcessAlive(childPid)).toBe(true);

    // No before-quit, no tree kill: only the closed stdin pipe tells the runtime.
    await killAppAbruptly(session);

    // The runtime outlived the shell long enough to end its child, then itself.
    // Had the OS killed it with the shell, the child would be left running.
    await expect
      .poll(() => [isProcessAlive(runtimePid), isProcessAlive(childPid)], {
        timeout: PROCESS_EXIT_WAIT_MS,
      })
      .toEqual([false, false]);
  });
});
