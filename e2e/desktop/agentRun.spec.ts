/**
 * Desktop shell + the real local runtime + the mock ACP agent: one Codex run
 * end to end (SCRUM-23, 22, 31). The shell launches the runtime jar as in
 * development; the runtime starts its seeded Codex agent as the mock agent
 * (e2e/desktop/fixtures/mock-acp-agent.cjs) through
 * AGENTS_ROOM_HARNESS_CODEX_COMMAND, with AGENTS_ROOM_E2E=1 so it can never
 * start the real codex-acp. The mock runs on the shell's own Electron as Node
 * (AGENTS_ROOM_NODE, ADR 0003 decision 12), as codex-acp would.
 *
 * The project and agent panels come later, so the specs send commands on the
 * renderer's runtime socket (the `sendRuntimeCommand` test hook) and assert on
 * the frames the renderer's runtime state holds (`getRuntimeState`), the
 * runtime.log and the OS process table.
 *
 * Needs `npm run runtime:build` (or `runtime:assemble`); without it the suite
 * is skipped locally and fails in CI, which builds the jar first.
 */
import fs from 'fs';
import path from 'path';

import { expect, test } from './fixtures/desktop';
import {
  agentEvents,
  agentStatus,
  type DesktopSession,
  DEV_RUNTIME_DESCRIPTOR,
  electronExecutable,
  enableTestHooks,
  eventsOfKind,
  expectOfficeConnected,
  javaProcessesRunning,
  type MockAgentScenario,
  processesWithArgument,
  readDevRuntimeDescriptor,
  readRuntimeLog,
  rendererRuntimeState,
  sendRuntimeCommand,
} from './helpers/desktop';

type BridgeWindow = Window & { agentsRoom: { pickProjectFolder(): Promise<string | null> } };

const descriptor = readDevRuntimeDescriptor();
const IN_CI = process.env['CI'] !== undefined && process.env['CI'] !== '';

/** A cold JVM and Spring context on a slow CI machine; the shell itself waits 30 s. */
const READY_WAIT_MS = 30_000;
/** Start (spawn, initialize, session/new) or one mock turn, on a slow CI machine. */
const AGENT_WAIT_MS = 20_000;
/** The runtime's and the adapter's own exits, on a slow CI machine. */
const PROCESS_EXIT_WAIT_MS = 15_000;
/** A cold JVM, an agent start and a turn or two, with room on a slow CI machine. */
const TEST_TIMEOUT_MS = 120_000;
const READY_LINE_LOGGED = /\[shell\] the runtime is ready on port (\d+)/;
const LOG_TREE_KILL = '[shell] the runtime is still running; killing its process tree';
const LOG_RUNTIME_EXITED = '[shell] the runtime exited';
/** The mock's account status carries this email; the runtime must drop it (ADR 0004 decision 9). */
const MOCK_ACCOUNT_EMAIL = 'mock-user@example.com';

/**
 * One turn: where it runs (the Node and Electron versions, the cwd), then a
 * read of the README as a tool call and a two-chunk answer. The rest of the
 * "happy" scenario (signed in with a ChatGPT account) stays.
 */
const ONE_TURN: MockAgentScenario = {
  extends: 'happy',
  prompts: [
    {
      stopReason: 'end_turn',
      steps: [
        { echo: 'runtime' },
        { echo: 'cwd' },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-read',
            title: "Read file 'README.md'",
            kind: 'read',
            status: 'pending',
            locations: [{ path: '${cwd}/README.md', line: 1 }],
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-read',
            status: 'completed',
          },
        },
        {
          update: {
            sessionUpdate: 'agent_message_chunk',
            messageId: 'msg-answer',
            content: { type: 'text', text: 'The README says ' },
          },
        },
        {
          update: {
            sessionUpdate: 'agent_message_chunk',
            messageId: 'msg-answer',
            content: { type: 'text', text: 'hello.' },
          },
        },
      ],
    },
  ],
};

/** A tool call, then the adapter process dies mid-run with exit code 3. */
const CRASH_MID_RUN: MockAgentScenario = { extends: 'crash-mid-run' };

/**
 * Launches the shell on the real runtime with the mock agent, waits for the
 * renderer's first snapshot with the test hooks on, opens a scratch project
 * through the stubbed folder picker and returns the seeded agent's id.
 */
async function openProjectWithAgent(
  session: DesktopSession,
): Promise<{ agentId: string; projectPath: string }> {
  const { app, window: page } = session;
  await expect
    .poll(() => readRuntimeLog(session), { timeout: READY_WAIT_MS })
    .toMatch(READY_LINE_LOGGED);
  await expectOfficeConnected(page);
  await enableTestHooks(page);
  await expectOfficeConnected(page);
  await expect.poll(async () => (await rendererRuntimeState(page))?.snapshots).toBe(1);

  const folder = path.join(session.tmpBase, 'project');
  fs.mkdirSync(folder, { recursive: true });
  fs.writeFileSync(path.join(folder, 'README.md'), '# demo\n');
  // Playwright cannot drive the OS folder dialog: the main process answers with the folder.
  await app.evaluate(({ dialog }, picked) => {
    dialog.showOpenDialog = (async () => ({
      canceled: false,
      filePaths: [picked],
    })) as typeof dialog.showOpenDialog;
  }, folder);
  const picked = await page.evaluate(() =>
    (window as unknown as BridgeWindow).agentsRoom.pickProjectFolder(),
  );
  expect(picked).toBe(folder);

  await sendRuntimeCommand(page, { type: 'openProject', path: picked! });
  await expect
    .poll(async () => (await rendererRuntimeState(page))?.agents.length, { timeout: AGENT_WAIT_MS })
    .toBe(1);
  const state = await rendererRuntimeState(page);
  // tmpBase is already a real path, so the runtime's validated path is the picked one.
  expect(state?.project?.path).toBe(folder);
  expect(state?.agents[0]).toMatchObject({
    name: 'Codex',
    harnessId: 'codex',
    readiness: 'configured',
  });
  return { agentId: state!.agents[0].agentId, projectPath: folder };
}

async function waitForStatus(page: DesktopSession['window'], agentId: string, status: string) {
  await expect
    .poll(async () => agentStatus(await rendererRuntimeState(page), agentId), {
      timeout: AGENT_WAIT_MS,
    })
    .toBe(status);
}

/** Quits the app and checks that the runtime and every mock agent process are gone. */
async function quitLeavingNothing(session: DesktopSession, javaBefore: number[]): Promise<void> {
  await session.app.close();
  const log = readRuntimeLog(session);
  expect(log).toContain(`${LOG_RUNTIME_EXITED} (code 0`);
  expect(log).not.toContain(LOG_TREE_KILL);
  await expect
    .poll(() => javaProcessesRunning(descriptor!.jar).filter((pid) => !javaBefore.includes(pid)), {
      timeout: PROCESS_EXIT_WAIT_MS,
    })
    .toEqual([]);
  expect(processesWithArgument(session.mockAgentScenarioFile!)).toEqual([]);
}

test.describe('Desktop shell running an agent over ACP', () => {
  test.skip(
    descriptor === null && !IN_CI,
    `${DEV_RUNTIME_DESCRIPTOR} (or the java and jar it names) is missing: run npm run runtime:build (or runtime:assemble) first`,
  );

  test.describe.configure({ timeout: TEST_TIMEOUT_MS });

  test.beforeEach(() => {
    expect(descriptor, `CI must run npm run runtime:assemble before e2e:desktop`).not.toBeNull();
  });

  test('a prompt to the seeded Codex agent streams its events in order to the renderer, and stopping and quitting leave no process @area:desktop-agent', async ({
    desktop,
  }) => {
    const javaBefore = javaProcessesRunning(descriptor!.jar);
    const session = await desktop.launch({ realRuntime: true, mockAgent: ONE_TURN });
    const { app, window: page } = session;
    // The command names the same Electron the shell runs on, so the runtime runs it as Node.
    expect(await app.evaluate(() => process.execPath)).toBe(electronExecutable());
    const { agentId, projectPath } = await openProjectWithAgent(session);

    await sendRuntimeCommand(page, { type: 'startAgent', agentId });
    await waitForStatus(page, agentId, 'ready');
    await sendRuntimeCommand(page, { type: 'sendPrompt', agentId, text: 'Read the README' });
    await expect
      .poll(
        async () =>
          eventsOfKind(agentEvents(await rendererRuntimeState(page), agentId), 'runEnded').length,
        { timeout: AGENT_WAIT_MS },
      )
      .toBe(1);
    await waitForStatus(page, agentId, 'ready');

    const state = await rendererRuntimeState(page);
    const events = agentEvents(state, agentId);
    const kinds = events.map((event) => event.kind);
    // Receive order: seq from 1 per agent, without gaps (the renderer connected before the start).
    expect(events.map((event) => event.seq)).toEqual(events.map((_, i) => i + 1));
    expect(eventsOfKind(events, 'agentStatusChanged').map((event) => event.data.status)).toEqual([
      'starting',
      'ready',
      'running',
      'ready',
    ]);
    // Already signed in (account status): no authenticate stage.
    expect(eventsOfKind(events, 'agentStarting').map((event) => event.data.stage)).toEqual([
      'spawn',
      'initialize',
      'session_new',
    ]);
    expect(eventsOfKind(events, 'authStatus').map((event) => event.data)).toEqual([
      { kind: 'account', label: 'ChatGPT Plus' },
    ]);
    expect(JSON.stringify(state)).not.toContain(MOCK_ACCOUNT_EMAIL);

    // The run: runStarted, a tool call merged by its id, the answer chunks, runEnded, in that order.
    const [sessionStarted] = eventsOfKind(events, 'sessionStarted');
    const [runStarted] = eventsOfKind(events, 'runStarted');
    const [runEnded] = eventsOfKind(events, 'runEnded');
    expect(runStarted.data.prompt).toBe('Read the README');
    expect(runStarted.runId).not.toBeNull();
    expect(runEnded.runId).toBe(runStarted.runId);
    expect(runEnded.data).toMatchObject({
      runId: runStarted.runId,
      outcome: 'completed',
      stopReason: 'end_turn',
      unfinishedToolCalls: [],
    });
    const [toolCall] = eventsOfKind(events, 'toolCall');
    expect(toolCall.data.toolCall).toMatchObject({
      toolCallId: 'call-read',
      kind: 'read',
      status: 'pending',
      title: "Read file 'README.md'",
    });
    expect(eventsOfKind(events, 'toolCallUpdate').map((event) => event.data.toolCall)).toEqual([
      expect.objectContaining({ toolCallId: 'call-read', status: 'completed' }),
    ]);
    const chunks = eventsOfKind(events, 'agentMessageChunk');
    const answer = chunks.filter((event) => event.data.messageId === 'msg-answer');
    expect(answer.map((event) => event.data.text)).toEqual(['The README says ', 'hello.']);
    const at = (event: { seq: number }) => event.seq;
    expect(at(sessionStarted)).toBeLessThan(at(runStarted));
    expect(at(runStarted)).toBeLessThan(at(toolCall));
    expect(at(toolCall)).toBeLessThan(at(answer[0]));
    expect(at(answer[1])).toBeLessThan(at(runEnded));
    // Every run event carries the run and the session; session/new got the project path.
    for (const event of events.filter((e) => at(e) > at(runStarted) && at(e) <= at(runEnded))) {
      if (event.kind !== 'agentStatusChanged') expect(event.runId).toBe(runStarted.runId);
      expect(event.sessionId).toBe(sessionStarted.sessionId);
    }
    const texts = chunks.map((event) => event.data.text ?? '');
    const electron = await app.evaluate(() => process.versions.electron);
    expect(texts).toContain(
      `runtime: node ${await app.evaluate(() => process.versions.node)}, electron ${electron}`,
    );
    expect(texts.find((text) => text.startsWith('cwd: '))).toBe(
      `cwd: ${projectPath} | process cwd: ${projectPath}`,
    );
    expect(kinds).not.toContain('agentError');

    // Stopping ends the session as requested and the adapter process with it.
    await sendRuntimeCommand(page, { type: 'stopAgent', agentId });
    await waitForStatus(page, agentId, 'offline');
    const stopped = agentEvents(await rendererRuntimeState(page), agentId);
    expect(eventsOfKind(stopped, 'sessionEnded').map((event) => event.data.reason)).toEqual([
      'stopped',
    ]);
    await expect
      .poll(() => processesWithArgument(session.mockAgentScenarioFile!), {
        timeout: PROCESS_EXIT_WAIT_MS,
      })
      .toEqual([]);

    await quitLeavingNothing(session, javaBefore);
  });

  test('an adapter that crashes mid-run puts the agent in error, never completed, and a new start works @area:desktop-agent', async ({
    desktop,
  }) => {
    const javaBefore = javaProcessesRunning(descriptor!.jar);
    const session = await desktop.launch({ realRuntime: true, mockAgent: CRASH_MID_RUN });
    const { window: page } = session;
    const { agentId } = await openProjectWithAgent(session);

    await sendRuntimeCommand(page, { type: 'startAgent', agentId });
    await waitForStatus(page, agentId, 'ready');
    await sendRuntimeCommand(page, { type: 'sendPrompt', agentId, text: 'Run the build' });
    await waitForStatus(page, agentId, 'error');

    const events = agentEvents(await rendererRuntimeState(page), agentId);
    expect(eventsOfKind(events, 'agentStatusChanged').map((event) => event.data.status)).toEqual([
      'starting',
      'ready',
      'running',
      'error',
    ]);
    expect(eventsOfKind(events, 'runEnded').map((event) => event.data)).toEqual([
      expect.objectContaining({ outcome: 'failed', errorCode: 'PROCESS_EXITED' }),
    ]);
    expect(eventsOfKind(events, 'agentError').map((event) => event.data)).toEqual([
      expect.objectContaining({ code: 'PROCESS_EXITED', stage: 'prompt' }),
    ]);
    expect(eventsOfKind(events, 'sessionEnded').map((event) => event.data)).toEqual([
      expect.objectContaining({ reason: 'exited', exitCode: 3 }),
    ]);
    expect(JSON.stringify(events)).not.toContain('"completed"');
    await expect
      .poll(() => processesWithArgument(session.mockAgentScenarioFile!), {
        timeout: PROCESS_EXIT_WAIT_MS,
      })
      .toEqual([]);

    // The office and the runtime kept running: a reconnecting renderer gets the error in its snapshot.
    await page.reload();
    await expectOfficeConnected(page);
    await expect
      .poll(async () => (await rendererRuntimeState(page))?.agentSnapshots[agentId]?.status)
      .toBe('error');
    const reconnected = await rendererRuntimeState(page);
    expect(reconnected?.agentSnapshots[agentId]?.lastError).toMatchObject({
      code: 'PROCESS_EXITED',
      stage: 'prompt',
    });
    const seqAtReconnect = reconnected!.lastSeq[agentId];
    expect(seqAtReconnect).toBe(events[events.length - 1].seq);

    // A new start is a new process and a new session.
    const firstSession = eventsOfKind(events, 'sessionStarted')[0].sessionId;
    await sendRuntimeCommand(page, { type: 'startAgent', agentId });
    await waitForStatus(page, agentId, 'ready');
    const restarted = eventsOfKind(
      agentEvents(await rendererRuntimeState(page), agentId).filter(
        (event) => event.seq > seqAtReconnect,
      ),
      'sessionStarted',
    );
    expect(restarted).toHaveLength(1);
    expect(restarted[0].sessionId).not.toBe(firstSession);

    await sendRuntimeCommand(page, { type: 'stopAgent', agentId });
    await waitForStatus(page, agentId, 'offline');
    await quitLeavingNothing(session, javaBefore);
  });
});
