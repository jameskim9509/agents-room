/**
 * The desktop shell's panels and pixel world, driven through the DOM like a
 * user would (SCRUM-18, 19, 21, 22, 27, 31): the built shell on the real
 * runtime jar, whose seeded Codex agent runs as the mock ACP agent
 * (e2e/desktop/fixtures/mock-acp-agent.cjs, AGENTS_ROOM_E2E=1).
 *
 * Specs click the project panel, the agent cards, the Detail Panel and the
 * office canvas, and assert on what the window shows. Characters live only on
 * the canvas, so their behaviour is read through the renderer's test hooks
 * (`getRuntimeAgents`, `getCharacters`), and a character is clicked with the
 * real mouse at the point `getCharacterClientPoint` reports. The OS folder
 * dialog is stubbed in the main process.
 *
 * Needs `npm run runtime:build` (or `runtime:assemble`); without it the suite
 * is skipped locally and fails in CI, which builds the jar first.
 */
import fs from 'fs';
import path from 'path';

import { expect, test } from './fixtures/desktop';
import {
  clickCharacter,
  type DesktopSession,
  DEV_RUNTIME_DESCRIPTOR,
  enableTestHooks,
  engineCharacter,
  engineCharacters,
  expectOfficeConnected,
  folderPickerCalls,
  type MockAgentScenario,
  readDevRuntimeDescriptor,
  readRuntimeLog,
  rendererRuntimeState,
  runtimeCharacters,
  selectedRuntimeAgent,
  stubFolderPicker,
} from './helpers/desktop';

const descriptor = readDevRuntimeDescriptor();
const IN_CI = process.env['CI'] !== undefined && process.env['CI'] !== '';

/** A cold JVM and Spring context on a slow CI machine; the shell itself waits 30 s. */
const READY_WAIT_MS = 30_000;
/** An agent start or one mock turn, on a slow CI machine. */
const AGENT_WAIT_MS = 20_000;
/** How long each tool call of RUN_SCENARIO stays in progress: long enough to look at it. */
const TOOL_STEP_MS = 1_500;
/** A cold JVM, an agent start and a few turns, with room on a slow CI machine. */
const TEST_TIMEOUT_MS = 150_000;
const READY_LINE_LOGGED = /\[shell\] the runtime is ready on port (\d+)/;

/** Fixed ids for the seeded project and agents (canonical UUIDs, as the runtime checks). */
const SEEDED_PROJECT_ID = '3b1f7c2e-5d4a-4e6b-9c8d-7a6b5c4d3e2f';
const CODEX_ID = '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';
const OTHER_ID = '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b';

function toolCall(id: string, title: string, kind: string) {
  return {
    update: { sessionUpdate: 'tool_call', toolCallId: id, title, kind, status: 'in_progress' },
  };
}

function toolDone(id: string) {
  return { update: { sessionUpdate: 'tool_call_update', toolCallId: id, status: 'completed' } };
}

/**
 * One turn that stays on each tool kind for TOOL_STEP_MS: read, search, edit,
 * execute (reported 20 more times, unchanged) and a kind ACP does not define,
 * then a sub-agent-looking tool call and a raw `subagent_spawned` update (no
 * child session id: neither may become a character, SCRUM-21), then the answer.
 */
const RUN_SCENARIO: MockAgentScenario = {
  extends: 'happy',
  prompts: [
    {
      stopReason: 'end_turn',
      steps: [
        { echo: 'cwd' },
        {
          update: {
            sessionUpdate: 'plan',
            entries: [
              { content: 'Read the README', priority: 'medium', status: 'in_progress' },
              { content: 'Run git status', priority: 'medium', status: 'pending' },
            ],
          },
        },
        toolCall('call-read', "Read file 'README.md'", 'read'),
        { delayMs: TOOL_STEP_MS },
        toolDone('call-read'),
        toolCall('call-search', "Search 'TODO'", 'search'),
        { delayMs: TOOL_STEP_MS },
        toolDone('call-search'),
        toolCall('call-edit', 'Edit notes.txt', 'edit'),
        { delayMs: TOOL_STEP_MS },
        toolDone('call-edit'),
        toolCall('call-exec', 'Run git status', 'execute'),
        ...Array.from({ length: 20 }, () => ({
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-exec',
            status: 'in_progress',
          },
        })),
        { delayMs: TOOL_STEP_MS },
        toolDone('call-exec'),
        toolCall('call-spawn', 'Spawn a sub-agent', 'frobnicate'),
        { update: { sessionUpdate: 'subagent_spawned', subagentId: 'sub-1' } },
        { delayMs: TOOL_STEP_MS },
        toolDone('call-spawn'),
        {
          update: {
            sessionUpdate: 'agent_message_chunk',
            messageId: 'msg-answer',
            content: { type: 'text', text: 'The README is read. ' },
          },
        },
        {
          update: {
            sessionUpdate: 'agent_message_chunk',
            messageId: 'msg-answer',
            content: { type: 'text', text: 'All checks pass.' },
          },
        },
      ],
    },
  ],
};

/** A long first turn (SCRUM-22), then a turn whose adapter dies mid-run. */
const LONG_THEN_CRASH: MockAgentScenario = {
  extends: 'happy',
  prompts: [
    {
      stopReason: 'end_turn',
      steps: [
        toolCall('call-suite', 'Run the test suite', 'execute'),
        ...Array.from({ length: 12 }, (_, i) => [
          { delayMs: 500 },
          {
            update: {
              sessionUpdate: 'agent_message_chunk',
              messageId: 'msg-progress',
              content: { type: 'text', text: `tick ${i + 1}. ` },
            },
          },
        ]).flat(),
        toolDone('call-suite'),
      ],
    },
    {
      stopReason: 'end_turn',
      steps: [toolCall('call-build', 'Run the build', 'execute'), { delayMs: 300 }, { crash: 3 }],
    },
  ],
};

/** One chunk of an answer message. */
function answerChunk(messageId: string, text: string) {
  return {
    update: {
      sessionUpdate: 'agent_message_chunk',
      messageId,
      content: { type: 'text', text },
    },
  };
}

/** How long LONG_ANSWER holds its run open after streaming, for the mid-run checks. */
const LONG_ANSWER_HOLD_MS = 10_000;

/**
 * One run that streams far more events than the renderer's 200-event lists
 * hold (a real Codex answer sends one event per chunk): 300 separate messages,
 * then one message in 250 chunks, then a pause in which the run is still in
 * progress, then the end.
 */
const LONG_ANSWER: MockAgentScenario = {
  extends: 'happy',
  prompts: [
    {
      stopReason: 'end_turn',
      steps: [
        toolCall('call-long', 'Run the long job', 'execute'),
        { burst: 300 },
        answerChunk('msg-long', 'BEGIN '),
        ...Array.from({ length: 248 }, (_, i) => answerChunk('msg-long', `w${i + 1} `)),
        answerChunk('msg-long', 'END'),
        { delayMs: LONG_ANSWER_HOLD_MS },
        toolDone('call-long'),
      ],
    },
  ],
};

/** Waits for the runtime and the office, with the renderer's test hooks on. */
async function openApp(session: DesktopSession): Promise<void> {
  await expect
    .poll(() => readRuntimeLog(session), { timeout: READY_WAIT_MS })
    .toMatch(READY_LINE_LOGGED);
  await expectOfficeConnected(session.window);
  await enableTestHooks(session.window);
  await expectOfficeConnected(session.window);
  await expect.poll(async () => (await rendererRuntimeState(session.window))?.snapshots).toBe(1);
}

/** A scratch project folder with a README and a two-level tree. */
function makeProject(tmpBase: string, name: string): string {
  const folder = path.join(tmpBase, name);
  fs.mkdirSync(path.join(folder, 'src', 'deep', 'deeper'), { recursive: true });
  fs.mkdirSync(path.join(folder, 'node_modules', 'pkg'), { recursive: true });
  fs.writeFileSync(path.join(folder, 'README.md'), '# demo\n');
  fs.writeFileSync(path.join(folder, 'src', 'main.ts'), 'export {};\n');
  fs.writeFileSync(path.join(folder, 'src', 'deep', 'deeper', 'far.txt'), 'too deep\n');
  fs.writeFileSync(path.join(folder, 'node_modules', 'pkg', 'index.js'), '\n');
  return folder;
}

/**
 * What an earlier session would have left in the runtime's data dir: one
 * recent project, with the seeded Codex agent and a second agent whose harness
 * the catalog does not know (`unsupported`, no tool details: ux.md R3).
 */
function seedProjectWithTwoAgents(userDataDir: string, folder: string): void {
  const dataDir = path.join(userDataDir, 'runtime');
  const agentsDir = path.join(dataDir, 'projects', SEEDED_PROJECT_ID);
  fs.mkdirSync(agentsDir, { recursive: true });
  fs.writeFileSync(
    path.join(dataDir, 'recent-projects.json'),
    JSON.stringify({
      version: 1,
      projects: [
        {
          projectId: SEEDED_PROJECT_ID,
          name: path.basename(folder),
          path: folder,
          lastOpenedAt: Date.now(),
        },
      ],
    }),
  );
  const agent = (agentId: string, name: string, harnessId: string, palette: number) => ({
    agentId,
    projectId: SEEDED_PROJECT_ID,
    name,
    harnessId,
    providerId: harnessId === 'codex' ? 'openai' : 'google',
    modelId: null,
    authMethodId: harnessId === 'codex' ? 'chat-gpt' : 'oauth',
    palette,
    hueShift: 0,
  });
  fs.writeFileSync(
    path.join(agentsDir, 'agents.json'),
    JSON.stringify({
      version: 1,
      agents: [agent(CODEX_ID, 'Codex', 'codex', 0), agent(OTHER_ID, 'Gemini', 'gemini-cli', 1)],
    }),
  );
}

/** Launches with RUN_SCENARIO and the seeded project, and opens it from 최근 프로젝트. */
async function launchSeeded(
  desktop: { launch: (opts: object) => Promise<DesktopSession> },
  mockAgent: MockAgentScenario,
): Promise<{ session: DesktopSession; folder: string }> {
  let folder = '';
  const session = await desktop.launch({
    realRuntime: true,
    mockAgent,
    beforeLaunch: ({ tmpBase, userDataDir }: { tmpBase: string; userDataDir: string }) => {
      folder = makeProject(tmpBase, 'demo-app');
      seedProjectWithTwoAgents(userDataDir, folder);
    },
  });
  await openApp(session);
  const page = session.window;
  const recent = page.getByTestId('recent-projects');
  await expect(recent.getByText('최근 프로젝트')).toBeVisible();
  await recent.getByRole('button', { name: /demo-app/ }).click();
  await expect(page.getByTestId('project-name')).toHaveText('demo-app', {
    timeout: AGENT_WAIT_MS,
  });
  return { session, folder };
}

function card(page: DesktopSession['window'], agentId: string) {
  return page.locator(`[data-testid="agent-card"][data-agent-id="${agentId}"]`);
}

test.describe('Desktop panels and pixel world', () => {
  test.skip(
    descriptor === null && !IN_CI,
    `${DEV_RUNTIME_DESCRIPTOR} (or the java and jar it names) is missing: run npm run runtime:build (or runtime:assemble) first`,
  );

  test.describe.configure({ timeout: TEST_TIMEOUT_MS });

  test.beforeEach(() => {
    expect(descriptor, `CI must run npm run runtime:assemble before e2e:desktop`).not.toBeNull();
  });

  test('the start screen opens a project through the folder picker: cancel keeps it, a bad folder shows why, a good one shows name, path and tree @area:desktop-ui', async ({
    desktop,
  }) => {
    const session = await desktop.launch({ realRuntime: true, mockAgent: { extends: 'happy' } });
    const { app, window: page } = session;
    await openApp(session);

    // SCRUM-18 AC1: the pixel world, the project selection state and the agent panel, on one screen.
    await expect(page.locator('canvas').first()).toBeVisible();
    await expect(page.getByTestId('no-project')).toHaveText('프로젝트 없음');
    const openButton = page.getByRole('button', { name: '프로젝트 열기' });
    await expect(openButton).toBeEnabled();
    await expect(page.getByTestId('agent-list')).toContainText(
      '프로젝트를 열면 에이전트가 표시됩니다',
    );
    await expect(page.getByTestId('detail-panel')).toContainText(
      '캐릭터나 에이전트 카드를 선택하세요',
    );

    // Cancel: the picker answered, and the screen stays as it was.
    await stubFolderPicker(app, null);
    await openButton.click();
    await expect.poll(() => folderPickerCalls(app)).toBe(1);
    await expect(openButton).toBeEnabled();
    await expect(page.getByTestId('no-project')).toBeVisible();
    await expect(page.getByTestId('project-error')).toHaveCount(0);

    // A folder that does not exist, then a file: the code's copy, and the button to pick again.
    const missing = path.join(session.tmpBase, 'no-such-folder');
    await stubFolderPicker(app, missing);
    await openButton.click();
    const error = page.getByTestId('project-error');
    await expect(error).toContainText('폴더를 찾을 수 없습니다', { timeout: AGENT_WAIT_MS });
    await expect(error).toContainText('다른 폴더를 선택하세요');
    await expect(error).toContainText(missing);
    await expect(page.getByTestId('no-project')).toBeVisible();
    const file = path.join(session.tmpBase, 'a-file.txt');
    fs.writeFileSync(file, 'not a folder\n');
    await stubFolderPicker(app, file);
    await expect(openButton).toBeEnabled();
    await openButton.click();
    await expect(error).toContainText('폴더가 아닙니다', { timeout: AGENT_WAIT_MS });

    // A good folder: its name, real path and tree (depth 2, node_modules not entered).
    const folder = makeProject(session.tmpBase, 'demo-app');
    await stubFolderPicker(app, folder);
    await openButton.click();
    await expect(page.getByTestId('project-name')).toHaveText('demo-app', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(page.getByTestId('project-path')).toHaveText(folder);
    await expect(page.getByTestId('project-error')).toHaveCount(0);
    const tree = page.getByTestId('file-tree');
    for (const entry of ['node_modules', 'src', 'src/deep', 'src/main.ts', 'README.md']) {
      await expect(tree.locator(`[data-path="${entry}"]`)).toBeVisible();
    }
    await expect(tree.locator('[data-path="src/deep/deeper"]')).toHaveCount(0);
    await expect(tree.locator('[data-path="node_modules/pkg"]')).toHaveCount(0);
    await expect(page.getByTestId('file-tree-truncated')).toHaveCount(0);

    // The seeded Codex agent: its card, opened in the Detail Panel, and an offline character.
    const codexCard = page.getByTestId('agent-card');
    await expect(codexCard).toHaveCount(1);
    await expect(codexCard.getByTestId('agent-card-name')).toHaveText('Codex');
    await expect(codexCard.getByTestId('agent-card-readiness')).toHaveText('설정 완료');
    await expect(codexCard.getByTestId('agent-card-status')).toHaveText('꺼짐');
    await expect(codexCard.getByRole('button', { name: '에이전트 시작' })).toBeEnabled();
    await expect(codexCard.getByRole('button', { name: '에이전트 종료' })).toBeDisabled();
    const detail = page.getByTestId('detail-panel');
    await expect(detail.getByTestId('detail-agent-name')).toHaveText('Codex');
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 없음');
    await expect(detail.getByTestId('detail-current-tool')).toHaveText('상태 정보 없음');
    await expect(detail.getByTestId('detail-notices')).toHaveText(
      '하위 Agent 정보 미제공 · 일부 활동만 관찰 가능',
    );
    const agentId = (await codexCard.getAttribute('data-agent-id'))!;
    await expect.poll(async () => (await engineCharacter(page, agentId))?.isOffline).toBe(true);
    const offline = await engineCharacter(page, agentId);
    expect(offline).toMatchObject({ pinnedToSeat: true, isActive: false, bubbleType: null });
    await expect(
      page.locator(`[data-testid="agent-label"][data-agent-id="${agentId}"]`),
    ).toContainText('Codex');

    // "연결 확인": the adapter's initialize only, its result on the card (ux.md 설정 상태).
    await codexCard.getByRole('button', { name: '연결 확인' }).click();
    await expect(codexCard.getByTestId('agent-card-check')).toHaveText(
      '연결 확인: 설정 완료 · ChatGPT Plus',
      { timeout: AGENT_WAIT_MS },
    );
    await expect(codexCard.getByRole('button', { name: '연결 확인' })).toBeEnabled();
    await expect(codexCard.getByTestId('agent-card-status')).toHaveText('꺼짐');

    // A folder with more than the tree's 500 entries says the tree is cut.
    const big = path.join(session.tmpBase, 'big-project');
    fs.mkdirSync(big);
    for (let i = 0; i < 510; i++) fs.writeFileSync(path.join(big, `file-${i}.txt`), '');
    await stubFolderPicker(app, big);
    await page.getByRole('button', { name: '프로젝트 열기' }).click();
    await expect(page.getByTestId('project-name')).toHaveText('big-project', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(page.getByTestId('file-tree-truncated')).toHaveText(
      '항목이 많아 일부만 표시합니다',
    );
  });

  test('a run shows its request, session state, current tool by kind, events in order and the final response, and the character follows it @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session, folder } = await launchSeeded(desktop, RUN_SCENARIO);
    const page = session.window;
    const detail = page.getByTestId('detail-panel');

    // Two agents, two characters. The one the catalog does not know cannot start (ux.md 설정 상태).
    await expect(page.getByTestId('agent-card')).toHaveCount(2);
    const other = card(page, OTHER_ID);
    await expect(other.getByTestId('agent-card-readiness')).toHaveText('지원되지 않음');
    await expect(other.getByRole('button', { name: '에이전트 시작' })).toBeDisabled();
    await expect.poll(async () => (await runtimeCharacters(page)).length).toBe(2);

    // A click on a character selects it in the Detail Panel; a card click selects the character.
    await clickCharacter(page, OTHER_ID);
    await expect(detail.getByTestId('detail-agent-name')).toHaveText('Gemini');
    await expect(detail.getByTestId('detail-notices')).toContainText(
      '이 실행기는 세부 활동을 제공하지 않습니다',
    );
    await expect(other).toHaveAttribute('data-selected', 'true');
    await card(page, CODEX_ID).getByTestId('agent-card-select').click();
    await expect(detail.getByTestId('detail-agent-name')).toHaveText('Codex');
    await expect.poll(() => selectedRuntimeAgent(page)).toBe(CODEX_ID);
    await expect(detail.getByTestId('detail-notices')).toHaveText(
      '하위 Agent 정보 미제공 · 일부 활동만 관찰 가능',
    );

    // Start: the session opens and the agent waits for a task.
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('대기', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 열림');
    await expect(card(page, CODEX_ID).getByTestId('agent-card-status')).toHaveText('대기');

    // The task, through the input.
    const input = detail.getByRole('textbox', { name: '작업을 입력하세요' });
    await input.fill('Read the README and check the repo');
    await detail.getByRole('button', { name: '보내기' }).click();
    await expect(detail.getByTestId('detail-prompt')).toHaveText(
      'Read the README and check the repo',
    );
    await expect(detail.getByTestId('detail-status')).toHaveText('실행 중', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(detail.getByTestId('detail-plan')).toContainText('Read the README');

    // Each tool kind: the Detail Panel's current tool, and the character's tool and icon (ux.md 행동 매핑).
    const currentTool = detail.getByTestId('detail-current-tool');
    const character = () => engineCharacter(page, CODEX_ID);
    const fast = { intervals: [100], timeout: AGENT_WAIT_MS };
    const steps: Array<[string, string, string | null]> = [
      ["Read file 'README.md' · 읽기 · 진행 중", 'read', null],
      ["Search 'TODO' · 검색 · 진행 중", 'search', 'search'],
      ['Edit notes.txt · 편집 · 진행 중', 'edit', null],
      ['Run git status · 실행 · 진행 중', 'execute', 'terminal'],
      ['Spawn a sub-agent · 기타 · 진행 중', 'other', 'generic'],
    ];
    for (const [text, tool, icon] of steps) {
      await expect(currentTool).toHaveText(text, { timeout: AGENT_WAIT_MS });
      await expect
        .poll(async () => {
          const ch = await character();
          return ch && { tool: ch.currentTool, icon: ch.activityIcon ?? null, active: ch.isActive };
        }, fast)
        .toEqual({ tool, icon, active: true });
      // Still two characters, whatever this step reported.
      expect((await engineCharacters(page)).length).toBe(2);
    }

    // The end: the outcome, the final response (with the cwd the adapter got, SCRUM-31).
    await expect(detail.getByTestId('detail-run-status')).toHaveText('완료', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(detail.getByTestId('detail-status')).toHaveText('대기');
    const response = detail.getByTestId('detail-response');
    await expect(response).toContainText('The README is read. All checks pass.');
    await expect(response).toContainText(`cwd: ${folder}`);
    await expect(currentTool).toHaveText('상태 정보 없음');

    // Events in receive order, the request before the tools before the answer before the outcome.
    const rows = detail.getByTestId('detail-events').locator('li');
    const seqs = await rows.evaluateAll((items) => items.map((li) => Number(li.dataset['seq'])));
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    const texts = await rows.allInnerTexts();
    const firstIndex = (needle: string) => texts.findIndex((text) => text.includes(needle));
    const order = [
      firstIndex('작업 요청: Read the README and check the repo'),
      firstIndex("Tool 호출: Read file 'README.md'"),
      firstIndex('Tool 호출: Run git status'),
      firstIndex('에이전트 메시지: The README is read. All checks pass.'),
      firstIndex('실행 결과: 완료'),
    ];
    expect(order.every((index) => index >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b));

    // SCRUM-21, 27: after the sub-agent-looking call, the raw subagent_spawned and the 20
    // repeats, the engine holds exactly the two agents' characters: no sub-agent (negative id),
    // no duplicate. The world changed the character far fewer times than events arrived.
    const runtime = await runtimeCharacters(page);
    expect(runtime.map((row) => row.agentId).sort()).toEqual([CODEX_ID, OTHER_ID].sort());
    const engine = await engineCharacters(page);
    expect(engine.map((ch) => ch.id).sort()).toEqual(runtime.map((row) => row.characterId).sort());
    expect(engine.every((ch) => ch.id > 0 && ch.isRuntimeAgent === true)).toBe(true);
    const codex = runtime.find((row) => row.agentId === CODEX_ID)!;
    expect(codex.engineUpdates).toBeLessThan(20);
    await expect.poll(async () => (await character())?.isActive).toBe(false);
  });

  test('a permission request is answered with the buttons of its options, and a click on the character keeps it @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, { extends: 'permission' });
    const page = session.window;
    const detail = page.getByTestId('detail-panel');
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('대기', {
      timeout: AGENT_WAIT_MS,
    });
    await detail.getByRole('textbox', { name: '작업을 입력하세요' }).fill('Clean the build');
    await detail.getByRole('textbox', { name: '작업을 입력하세요' }).press('Enter');

    const request = detail.getByTestId('permission-request');
    await expect(request).toBeVisible({ timeout: AGENT_WAIT_MS });
    await expect(detail.getByTestId('detail-status')).toHaveText('승인 대기');
    await expect(request.getByTestId('permission-target')).toContainText('Run rm -rf build');
    await expect(request.getByRole('button')).toHaveText(['이번만 허용', '항상 허용', '거부']);
    await expect
      .poll(async () => (await engineCharacter(page, CODEX_ID))?.bubbleType)
      .toBe('permission');

    // R9: closing or clicking around never answers. The bubble and the request stay.
    // The click lands: the selected character is deselected by it.
    await expect.poll(() => selectedRuntimeAgent(page)).toBe(CODEX_ID);
    await clickCharacter(page, CODEX_ID);
    await expect.poll(() => selectedRuntimeAgent(page)).toBeNull();
    await expect
      .poll(async () => (await engineCharacter(page, CODEX_ID))?.bubbleType)
      .toBe('permission');
    await card(page, CODEX_ID).getByTestId('agent-card-select').click();
    await expect(request).toBeVisible();

    await request.getByRole('button', { name: '이번만 허용' }).click();
    await expect(request).toHaveCount(0, { timeout: AGENT_WAIT_MS });
    await expect(detail.getByTestId('detail-run-status')).toHaveText('완료', {
      timeout: AGENT_WAIT_MS,
    });
    // The adapter got the chosen option (the mock says which), and the event list shows the answer.
    await expect(detail.getByTestId('detail-response')).toContainText(
      'permission: selected allow_once',
    );
    await expect(detail.getByTestId('detail-events')).toContainText('권한 응답: 이번만 허용');
    await expect
      .poll(async () => (await engineCharacter(page, CODEX_ID))?.bubbleType)
      .not.toBe('permission');
    // SCRUM-19, R2: this adapter reported no plan, so there is no Plan section.
    await expect(detail.getByTestId('detail-plan')).toHaveCount(0);
  });

  test('a run that streams more events than the lists hold keeps its state, the whole answer and the open project @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, LONG_ANSWER);
    const { app, window: page } = session;
    const detail = page.getByTestId('detail-panel');
    const codex = card(page, CODEX_ID);
    await codex.getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('대기', {
      timeout: AGENT_WAIT_MS,
    });
    const prompt = 'Run the long job and report';
    await detail.getByRole('textbox', { name: '작업을 입력하세요' }).fill(prompt);
    await detail.getByRole('button', { name: '보내기' }).click();

    // Mid-run, after about 550 events: the run as it is, not as the first 200 left it.
    const events = detail.getByTestId('detail-events');
    await expect(events.locator('li').last()).toContainText('BEGIN w1 w2', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(events.locator('li').last()).toContainText('w248 END');
    expect(await events.locator('li').count()).toBeLessThanOrEqual(200);
    await expect(detail.getByTestId('detail-status')).toHaveText('실행 중');
    await expect(codex.getByTestId('agent-card-status')).toHaveText('실행 중');
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 열림');
    await expect(detail.getByTestId('detail-prompt')).toHaveText(prompt);
    await expect(detail.getByTestId('detail-current-tool')).toHaveText(
      'Run the long job · 실행 · 진행 중',
    );
    await expect(codex.getByRole('button', { name: '에이전트 시작' })).toBeDisabled();
    await expect(codex.getByRole('button', { name: '에이전트 종료' })).toBeEnabled();
    expect(await engineCharacter(page, CODEX_ID)).toMatchObject({
      isOffline: false,
      isActive: true,
      currentTool: 'execute',
    });

    // Another folder while the agent runs: refused (INVALID_STATE), the open project stays.
    const other = makeProject(session.tmpBase, 'other-app');
    await stubFolderPicker(app, other);
    await page.getByRole('button', { name: '프로젝트 열기' }).click();
    await expect(page.getByTestId('project-rejected')).toHaveText(
      '에이전트를 모두 종료한 뒤 다른 프로젝트를 여세요',
      { timeout: AGENT_WAIT_MS },
    );
    await expect(page.getByTestId('project-name')).toHaveText('demo-app');
    await expect(detail.getByTestId('detail-status')).toHaveText('실행 중');

    // The end: the whole answer, every message of the run from the first one.
    await expect(detail.getByTestId('detail-run-status')).toHaveText('완료', {
      timeout: LONG_ANSWER_HOLD_MS + AGENT_WAIT_MS,
    });
    const answer = await detail.getByTestId('detail-response').textContent();
    expect(answer?.startsWith('update 1\nupdate 2\nupdate 3\n')).toBe(true);
    expect(answer).toContain('\nupdate 300\nBEGIN w1 w2 ');
    expect(answer?.endsWith('w248 END')).toBe(true);
    await expect(detail.getByTestId('detail-response-partial')).toHaveCount(0);
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 열림');
    await expect(detail.getByTestId('detail-prompt')).toHaveText(prompt);
    await expect(detail.getByTestId('detail-status')).toHaveText('대기');
  });

  test('a sign-in that fails reads as an authentication failure at the sign-in stage, apart from a run failure @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, { extends: 'auth-fails' });
    const page = session.window;
    const detail = page.getByTestId('detail-panel');
    const codex = card(page, CODEX_ID);
    await codex.getByRole('button', { name: '에이전트 시작' }).click();

    // SCRUM-65 AC2, SCRUM-23: the code, the stage and the sign-in action.
    const error = detail.getByTestId('detail-error');
    await expect(error.getByTestId('detail-error-code')).toHaveText('인증 실패', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(error).toContainText('단계: 인증 확인');
    await expect(error).toContainText('조치: "에이전트 시작"으로 로그인을 다시 하세요');
    await expect(detail.getByTestId('detail-status')).toHaveText('오류');
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 없음');
    await expect(detail.getByTestId('detail-run-status')).toHaveText('미지정');
    await expect(codex.getByTestId('agent-card-readiness')).toHaveText('인증 필요');
    await expect(codex.getByRole('button', { name: '에이전트 시작' })).toBeEnabled();
    await expect
      .poll(async () => (await engineCharacter(page, CODEX_ID))?.bubbleType)
      .toBe('error');
  });

  test('a session that cannot be created reads as such, with the stage and the generic action @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, { extends: 'session-new-fails' });
    const page = session.window;
    const detail = page.getByTestId('detail-panel');
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();

    const error = detail.getByTestId('detail-error');
    await expect(error.getByTestId('detail-error-code')).toHaveText('세션 생성 실패', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(error).toContainText('단계: 세션 생성');
    await expect(error).toContainText('조치: "에이전트 시작"으로 다시 시작하세요');
    // The adapter's message names a key: the cause line shows it masked (ADR 0004 decision 8).
    await expect(error).toContainText('원인: ');
    await expect(error).not.toContainText('sk-mockmock');
    await expect(detail.getByTestId('detail-status')).toHaveText('오류');
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 없음');
    await expect(card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' })).toBeEnabled();
  });

  test('during a long run the window resizes and a character click selects it; an adapter crash shows the error and the app keeps working @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, LONG_THEN_CRASH);
    const { app, window: page } = session;
    const detail = page.getByTestId('detail-panel');
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('대기', {
      timeout: AGENT_WAIT_MS,
    });
    await detail.getByRole('textbox', { name: '작업을 입력하세요' }).fill('Run the tests');
    await detail.getByRole('button', { name: '보내기' }).click();
    await expect(detail.getByTestId('detail-current-tool')).toHaveText(
      'Run the test suite · 실행 · 진행 중',
      { timeout: AGENT_WAIT_MS },
    );

    // SCRUM-22: resize while events stream in; the world column and its canvas follow.
    const setSize = (width: number, height: number) =>
      app.evaluate(
        ({ BrowserWindow }, [w, h]) => {
          BrowserWindow.getAllWindows()[0].setSize(w, h);
        },
        [width, height] as const,
      );
    const worldWidth = () =>
      page
        .getByTestId('world-column')
        .evaluate((el) => Math.round(el.getBoundingClientRect().width));
    const canvasWidth = () =>
      page
        .locator('canvas')
        .first()
        .evaluate((el) => Math.round(el.getBoundingClientRect().width));
    // The window's first size depends on the display (CI runners are smaller), so take the
    // wide baseline at an explicit 1280x800 once the layout has settled.
    const settledWorldWidth = async () => {
      let last = -1;
      await expect
        .poll(async () => {
          const width = await worldWidth();
          const settled = width === last;
          last = width;
          return settled;
        })
        .toBe(true);
      return last;
    };
    await setSize(1280, 800);
    const wide = await settledWorldWidth();
    await setSize(900, 600);
    await expect.poll(worldWidth).toBeLessThan(wide);
    await expect.poll(async () => (await canvasWidth()) === (await worldWidth())).toBe(true);
    await expect(page.getByTestId('project-panel')).toBeVisible();
    await expect(detail.getByTestId('detail-status')).toHaveText('실행 중');

    // A click on the selected (working) character deselects it, the next one selects it again.
    await clickCharacter(page, CODEX_ID);
    await expect(detail).toContainText('캐릭터나 에이전트 카드를 선택하세요');
    await expect.poll(() => selectedRuntimeAgent(page)).toBeNull();
    await clickCharacter(page, CODEX_ID);
    await expect(detail.getByTestId('detail-agent-name')).toHaveText('Codex');
    await expect(detail.getByTestId('detail-status')).toHaveText('실행 중');
    await expect(detail.getByTestId('detail-events')).toContainText('tick');
    await setSize(1280, 800);
    await expect.poll(worldWidth).toBe(wide);

    await expect(detail.getByTestId('detail-run-status')).toHaveText('완료', {
      timeout: AGENT_WAIT_MS,
    });

    // The adapter dies mid-run: the error with its stage, cause and action; never "완료" (R7).
    await detail.getByRole('textbox', { name: '작업을 입력하세요' }).fill('Run the build');
    await detail.getByRole('button', { name: '보내기' }).click();
    const error = detail.getByTestId('detail-error');
    await expect(error.getByTestId('detail-error-code')).toHaveText('에이전트 프로세스가 종료됨', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(error).toContainText('단계: 실행');
    await expect(error).toContainText('원인: ');
    await expect(error).toContainText('조치: "에이전트 시작"으로 다시 시작하세요');
    await expect(detail.getByTestId('detail-status')).toHaveText('오류');
    await expect(detail.getByTestId('detail-run-status')).toHaveText('에이전트 프로세스가 종료됨');
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 종료됨(비정상 종료)');
    await expect
      .poll(async () => (await engineCharacter(page, CODEX_ID))?.bubbleType)
      .toBe('error');
    // The badge keeps the name the adapter reported, after its session ended (ux.md 에이전트 카드).
    await expect(card(page, CODEX_ID).getByTestId('agent-card-harness')).toHaveText('Codex');

    // The IDE keeps running: the agent starts again, as a new session.
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('대기', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 열림');
    await expect(detail.getByTestId('detail-error')).toHaveCount(0);
    // R8: the crashed run belongs to the session before; the new one has no run yet.
    await expect(detail.getByTestId('detail-prompt')).toHaveText('미지정');
    await expect(detail.getByTestId('detail-run-status')).toHaveText('미지정');
    await expect(detail.getByTestId('detail-final')).toHaveCount(0);
    await expect(detail.getByTestId('detail-events')).toContainText('에이전트 프로세스가 종료됨');
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 종료' }).click();
    await expect(detail.getByTestId('detail-status')).toHaveText('꺼짐', {
      timeout: AGENT_WAIT_MS,
    });
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 종료됨(중지)');
  });

  test('the sign-in stage asks for the browser login, and "에이전트 종료" ends it @area:desktop-ui', async ({
    desktop,
  }) => {
    const { session } = await launchSeeded(desktop, { extends: 'auth-hangs' });
    const page = session.window;
    const detail = page.getByTestId('detail-panel');
    await card(page, CODEX_ID).getByRole('button', { name: '에이전트 시작' }).click();
    await expect(detail.getByTestId('detail-login')).toHaveText(
      '브라우저에서 ChatGPT 로그인을 완료하세요',
      { timeout: AGENT_WAIT_MS },
    );
    await expect(detail.getByTestId('detail-status')).toHaveText('시작 중');
    await expect(detail.getByTestId('detail-starting').locator('[aria-current="step"]')).toHaveText(
      '인증 확인',
    );
    await expect(detail.getByTestId('detail-session-state')).toHaveText('세션 없음');
    await detail
      .getByTestId('detail-starting')
      .getByRole('button', { name: '에이전트 종료' })
      .click();
    await expect(detail.getByTestId('detail-status')).toHaveText('꺼짐', {
      timeout: AGENT_WAIT_MS,
    });
  });
});
