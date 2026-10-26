/**
 * The desktop panels' view of an agent (desktopUi/agentView.ts), its event rows
 * (eventRows.ts) and the Detail Panel lines (detailText.ts): derived from what
 * the renderer holds of the runtime's frames, never invented (ux.md R1–R12).
 * The snapshot input is the contract fixture, so it is one the runtime may send.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, test } from 'vitest';

import type {
  AgentEvent,
  AgentStatus,
  AgentSummary,
  CommandRejected,
  ConnectionChecked,
  Readiness,
  RunOutcome,
  RuntimeServerMessage,
  Snapshot,
} from '../../core/src/messages.ts';
import {
  agentIdentity,
  canCheck,
  canStart,
  canStop,
  harnessBadge,
} from '../src/desktopUi/agentControls.ts';
import { deriveAgentView, deriveAgentViews, toolBehaviour } from '../src/desktopUi/agentView.ts';
import {
  agentSubject,
  checkOf,
  markSent,
  permissionSubject,
  rejectionOf,
  type SentMarks,
} from '../src/desktopUi/commandFeedback.ts';
import { currentToolText, runStatus, usageText } from '../src/desktopUi/detailText.ts';
import { eventRows } from '../src/desktopUi/eventRows.ts';
import { treeRows } from '../src/desktopUi/fileTreeRows.ts';
import {
  applyRuntimeFrame,
  INITIAL_RUNTIME_STATE,
  type RuntimeState,
} from '../src/runtimeState.ts';

const FIXTURES = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'core',
  'fixtures',
  'valid',
  'server',
);

function fixture<T extends RuntimeServerMessage>(name: string): T {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, `${name}.json`), 'utf8')) as T;
}

const PROJECT = '6f1c2a3e-8b4d-4e5f-9a6b-7c8d9e0f1a2b';
const AGENT = '0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d';
const RUN = 'run-1';
const SESSION = 'session-1';

const SUMMARY: AgentSummary = {
  agentId: AGENT,
  projectId: PROJECT,
  name: 'Codex',
  harnessId: 'codex',
  providerId: 'openai',
  modelId: null,
  authMethodId: 'chat-gpt',
  readiness: 'configured',
  palette: 0,
  hueShift: 0,
  reportsToolDetails: true,
  reportsSubagentSessions: false,
};

/** Builds this agent's events with consecutive `seq` from `from`. */
function events(from = 1) {
  let seq = from - 1;
  return <K extends AgentEvent['kind']>(
    kind: K,
    data: Extract<AgentEvent, { kind: K }>['data'],
    ids: { runId?: string | null; sessionId?: string | null } = {},
  ): AgentEvent =>
    ({
      projectId: PROJECT,
      agentId: AGENT,
      sessionId: ids.sessionId ?? null,
      runId: ids.runId ?? null,
      seq: ++seq,
      receivedAt: 0,
      kind,
      data,
    }) as AgentEvent;
}

/** A renderer that connected with no project, then got the project and these events. */
function stateWith(...agentEvents: AgentEvent[]): RuntimeState {
  const frames: RuntimeServerMessage[] = [
    fixture<Snapshot>('snapshot.empty'),
    { type: 'agentsUpdated', agents: [SUMMARY] },
    ...agentEvents.map((event) => ({ type: 'agentEvent' as const, event })),
  ];
  return frames.reduce(applyRuntimeFrame, INITIAL_RUNTIME_STATE);
}

function view(state: RuntimeState) {
  return deriveAgentView(state, state.agents[0]);
}

/** A start, then a session (seq 1..5). */
function started(ev = events()) {
  return [
    ev('agentStatusChanged', { status: 'starting', previous: 'offline' }),
    ev('agentStarting', { stage: 'spawn' }),
    ev('sessionStarted', { sessionId: SESSION }, { sessionId: SESSION }),
    ev('agentStatusChanged', { status: 'ready', previous: 'starting' }, { sessionId: SESSION }),
  ];
}

describe('agent view', () => {
  test('an agent without a snapshot starts offline, with nothing reported', () => {
    const v = view(stateWith());
    expect(v.status).toBe('offline');
    expect(v.sessionState).toBe('none');
    expect(v.prompt).toBeNull();
    expect(v.currentTool).toBeNull();
    expect(v.plan).toBeNull();
    expect(v.response).toBeNull();
  });

  test('starts from the snapshot and applies only the newer events', () => {
    const snapshot = fixture<Snapshot>('snapshot');
    let state = applyRuntimeFrame(INITIAL_RUNTIME_STATE, snapshot);
    const [agentSnapshot] = snapshot.agents;
    const fromSnapshot = deriveAgentView(state, agentSnapshot.agent);
    expect(fromSnapshot.status).toBe('awaiting_permission');
    expect(fromSnapshot.sessionState).toBe('open');
    expect(fromSnapshot.prompt).toBe(agentSnapshot.prompt);
    // The latest unfinished call is the current one: git status, pending.
    expect(fromSnapshot.currentTool?.toolCallId).toBe('call_9b1c');
    expect(fromSnapshot.pendingPermissions.map((p) => p.permissionId)).toEqual([
      agentSnapshot.pendingPermissions[0].permissionId,
    ]);
    expect(fromSnapshot.plan).toEqual(agentSnapshot.session?.plan);
    // The tail is the snapshot's: its events are not applied again (status would go back to starting).
    expect(fromSnapshot.events.length).toBe(agentSnapshot.recentEvents.length);

    const ev = events(agentSnapshot.lastSeq + 1);
    const answered: AgentEvent[] = [
      ev(
        'permissionResolved',
        {
          permissionId: agentSnapshot.pendingPermissions[0].permissionId,
          outcome: 'selected',
          optionId: 'approved',
        },
        { runId: agentSnapshot.runId, sessionId: agentSnapshot.sessionId },
      ),
      ev(
        'agentStatusChanged',
        { status: 'running', previous: 'awaiting_permission' },
        { runId: agentSnapshot.runId, sessionId: agentSnapshot.sessionId },
      ),
    ];
    for (const event of answered) state = applyRuntimeFrame(state, { type: 'agentEvent', event });
    const after = deriveAgentView(state, agentSnapshot.agent);
    expect(after.status).toBe('running');
    expect(after.pendingPermissions).toEqual([]);
    expect(after.currentTool?.toolCallId).toBe('call_9b1c');
  });

  test('a run: request, merged tool calls, the answer, then the outcome', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const state = stateWith(
      ...started(ev),
      ev('runStarted', { runId: RUN, prompt: 'Read the README' }, run),
      ev('agentStatusChanged', { status: 'running', previous: 'ready' }, run),
      ev(
        'toolCall',
        {
          toolCall: {
            toolCallId: 'c1',
            title: 'Read README.md',
            kind: 'read',
            status: 'pending',
            locations: [],
          },
        },
        run,
      ),
    );
    let v = view(state);
    expect(v.prompt).toBe('Read the README');
    expect(v.currentTool?.title).toBe('Read README.md');
    expect(currentToolText(v)).toEqual({
      title: 'Read README.md',
      behaviour: '읽기',
      status: '시작 전',
    });
    expect(runStatus(v)).toEqual({ text: '실행 중', tone: 'progress' });

    const done = [
      ev(
        'toolCallUpdate',
        {
          toolCall: {
            toolCallId: 'c1',
            title: 'Read README.md',
            kind: 'read',
            status: 'completed',
            locations: [],
          },
        },
        run,
      ),
      ev('agentMessageChunk', { messageId: 'm', contentType: 'text', text: 'The README ' }, run),
      ev('agentMessageChunk', { messageId: 'm', contentType: 'text', text: 'says hello.' }, run),
      ev(
        'runEnded',
        { runId: RUN, outcome: 'completed', stopReason: 'end_turn', unfinishedToolCalls: [] },
        run,
      ),
      ev('agentStatusChanged', { status: 'ready', previous: 'running' }, { sessionId: SESSION }),
    ];
    v = view(done.reduce((s, event) => applyRuntimeFrame(s, { type: 'agentEvent', event }), state));
    // Merged by id: one call, finished, so no current tool (R5 in the panel).
    expect(v.currentTool).toBeNull();
    expect(currentToolText(v)).toBeNull();
    expect(v.runId).toBeNull();
    expect(v.lastRun?.outcome).toBe('completed');
    expect(v.response).toBe('The README says hello.');
    expect(runStatus(v)).toEqual({ text: '완료', tone: 'completed' });
    // The prompt of the last run stays (the snapshot keeps it too).
    expect(v.prompt).toBe('Read the README');
  });

  test('a repeated update of one tool call is still one tool call', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const call = (status: 'in_progress') => ({
      toolCall: {
        toolCallId: 'c1',
        title: 'Run tests',
        kind: 'execute' as const,
        status,
        locations: [],
      },
    });
    const repeats = Array.from({ length: 20 }, () =>
      ev('toolCallUpdate', call('in_progress'), run),
    );
    const v = view(
      stateWith(
        ...started(ev),
        ev('runStarted', { runId: RUN, prompt: 'Test' }, run),
        ev('agentStatusChanged', { status: 'running', previous: 'ready' }, run),
        ev('toolCall', call('in_progress'), run),
        ...repeats,
      ),
    );
    expect(v.toolCalls).toHaveLength(1);
    expect(v.currentTool?.toolCallId).toBe('c1');
  });

  test('a kind or status the adapter did not report reads "미지정" (R6)', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const v = view(
      stateWith(
        ...started(ev),
        ev('runStarted', { runId: RUN, prompt: 'x' }, run),
        ev('agentStatusChanged', { status: 'running', previous: 'ready' }, run),
        ev('toolCall', { toolCall: { toolCallId: 'c1', locations: [] } }, run),
      ),
    );
    expect(currentToolText(v)).toEqual({ title: '미지정', behaviour: '미지정', status: '미지정' });
  });

  test('a run whose adapter died is never "완료" (R7)', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const v = view(
      stateWith(
        ...started(ev),
        ev('runStarted', { runId: RUN, prompt: 'Build' }, run),
        ev('agentStatusChanged', { status: 'running', previous: 'ready' }, run),
        ev(
          'runEnded',
          { runId: RUN, outcome: 'failed', errorCode: 'PROCESS_EXITED', unfinishedToolCalls: [] },
          run,
        ),
        ev('sessionEnded', { reason: 'exited', exitCode: 3 }, { sessionId: SESSION }),
        ev('agentError', { code: 'PROCESS_EXITED', stage: 'prompt', message: 'exit code 3' }),
        ev('agentStatusChanged', { status: 'error', previous: 'running' }),
      ),
    );
    expect(v.status).toBe('error');
    expect(v.lastError).toEqual({
      code: 'PROCESS_EXITED',
      stage: 'prompt',
      message: 'exit code 3',
    });
    expect(v.sessionState).toBe('ended_exited');
    expect(runStatus(v)).toEqual({ text: '에이전트 프로세스가 종료됨', tone: 'error' });
  });

  test('session state follows sessionStarted and sessionEnded only (D23)', () => {
    const ev = events();
    const opened = started(ev);
    expect(view(stateWith(...opened.slice(0, 2))).sessionState).toBe('none');
    expect(view(stateWith(...opened)).sessionState).toBe('open');
    const stopped = [
      ...opened,
      ev('sessionEnded', { reason: 'stopped' }, { sessionId: SESSION }),
      ev('agentStatusChanged', { status: 'offline', previous: 'ready' }),
    ];
    expect(view(stateWith(...stopped)).sessionState).toBe('ended_stopped');
    // A new start that fails before session/new opened nothing: "세션 없음", not the old end.
    const failedRestart = [
      ...stopped,
      ev('agentStatusChanged', { status: 'starting', previous: 'offline' }),
      ev('agentError', { code: 'ACP_INIT_FAILED', stage: 'initialize', message: 'timeout' }),
      ev('agentStatusChanged', { status: 'error', previous: 'starting' }),
    ];
    const failed = view(stateWith(...failedRestart));
    expect(failed.sessionState).toBe('none');
    expect(failed.lastError?.code).toBe('ACP_INIT_FAILED');
    const aborted = [
      ...opened,
      ev('sessionEnded', { reason: 'aborted' }, { sessionId: SESSION }),
      ev('agentError', { code: 'PROTOCOL_ERROR', stage: 'running', message: 'not JSON-RPC' }),
      ev('agentStatusChanged', { status: 'error', previous: 'ready' }),
    ];
    expect(view(stateWith(...aborted)).sessionState).toBe('ended_aborted');
  });

  test('the start stage shows while starting, including the sign-in stage', () => {
    const ev = events();
    const v = view(
      stateWith(
        ev('agentStatusChanged', { status: 'starting', previous: 'offline' }),
        ev('agentStarting', { stage: 'spawn' }),
        ev('agentStarting', { stage: 'initialize' }),
        ev('agentStarting', { stage: 'authenticate' }),
      ),
    );
    expect(v.startingStage).toBe('authenticate');
  });

  test('a pending permission request goes away when the runtime resolves it', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const opened = started(ev);
    const runStarted = ev('runStarted', { runId: RUN, prompt: 'x' }, run);
    const request = ev(
      'permissionRequested',
      {
        permissionId: 'p1',
        toolCall: { toolCallId: 'c1', title: 'Run rm', kind: 'execute', locations: [] },
        options: [{ optionId: 'yes', name: 'Yes', kind: 'allow_once' }],
      },
      run,
    );
    const asked = stateWith(
      ...opened,
      runStarted,
      request,
      ev('agentStatusChanged', { status: 'awaiting_permission', previous: 'ready' }, run),
    );
    expect(view(asked).pendingPermissions.map((p) => p.permissionId)).toEqual(['p1']);
    expect(runStatus(view(asked))).toEqual({ text: '승인 대기', tone: 'waiting' });
    const resolved = applyRuntimeFrame(asked, {
      type: 'agentEvent',
      event: ev(
        'permissionResolved',
        { permissionId: 'p1', outcome: 'selected', optionId: 'yes' },
        run,
      ),
    });
    expect(view(resolved).pendingPermissions).toEqual([]);
  });

  test('deriveAgentViews keeps the runtime list order', () => {
    const second = { ...SUMMARY, agentId: '11111111-2222-4333-8444-555555555555', name: 'Other' };
    const state = applyRuntimeFrame(stateWith(), {
      type: 'agentsUpdated',
      agents: [SUMMARY, second],
    });
    expect(deriveAgentViews(state).map((v) => v.agent.name)).toEqual(['Codex', 'Other']);
  });
});

describe('tool behaviour (ux.md 행동 매핑)', () => {
  test.each([
    ['search', 'search'],
    ['read', 'read'],
    ['fetch', 'read'],
    ['edit', 'edit'],
    ['delete', 'edit'],
    ['move', 'edit'],
    ['execute', 'execute'],
    ['think', 'other'],
    ['switch_mode', 'other'],
    ['other', 'other'],
  ] as const)('%s → %s', (kind, behaviour) => {
    expect(toolBehaviour(kind)).toBe(behaviour);
  });

  test('no kind reported → no behaviour (the panel writes 미지정)', () => {
    expect(toolBehaviour(undefined)).toBeNull();
  });
});

describe('event rows', () => {
  test('one row per event in seq order, consecutive chunks of one message joined', () => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const rows = eventRows([
      ev('runStarted', { runId: RUN, prompt: 'Go' }, run),
      ev('agentThoughtChunk', { contentType: 'text', text: 'thinking' }, run),
      ev(
        'toolCall',
        {
          toolCall: {
            toolCallId: 'c1',
            title: 'Search TODO',
            kind: 'search',
            status: 'in_progress',
            locations: [],
          },
        },
        run,
      ),
      ev('agentMessageChunk', { messageId: 'm1', contentType: 'text', text: 'Hello ' }, run),
      ev('agentMessageChunk', { messageId: 'm1', contentType: 'text', text: 'there.' }, run),
      ev('agentMessageChunk', { messageId: 'm2', contentType: 'text', text: 'Next.' }, run),
      ev(
        'permissionRequested',
        {
          permissionId: 'p1',
          toolCall: { toolCallId: 'c2', title: 'Run rm', locations: [] },
          options: [{ optionId: 'nope', name: 'No', kind: 'reject_once' }],
        },
        run,
      ),
      ev('permissionResolved', { permissionId: 'p1', outcome: 'selected', optionId: 'nope' }, run),
      ev('runEnded', { runId: RUN, outcome: 'completed', unfinishedToolCalls: [] }, run),
    ]);
    expect(rows.map((row) => [row.seq, row.label, row.text])).toEqual([
      [1, '작업 요청', 'Go'],
      [2, '에이전트가 보고한 생각', 'thinking'],
      [3, 'Tool 호출', 'Search TODO · 검색 · 진행 중'],
      [4, '에이전트 메시지', 'Hello there.'],
      [6, '에이전트 메시지', 'Next.'],
      [7, '권한 요청', 'Run rm'],
      [8, '권한 응답', '거부'],
      [9, '실행 결과', '완료'],
    ]);
    expect(rows[1].collapsed).toBe(true);
    expect(rows[3].lastSeq).toBe(5);
  });

  test('a failed run of a dead adapter reads as such', () => {
    const ev = events();
    const [row] = eventRows([
      ev('runEnded', {
        runId: RUN,
        outcome: 'failed',
        errorCode: 'PROCESS_EXITED',
        unfinishedToolCalls: [],
      }),
    ]);
    expect(row.text).toBe('에이전트 프로세스가 종료됨');
    expect(row.tone).toBe('error');
  });
});

describe('agent controls', () => {
  const at = (status: AgentStatus, readiness: Readiness = SUMMARY.readiness) => {
    const ev = events();
    const state = applyRuntimeFrame(stateWith(), {
      type: 'agentsUpdated',
      agents: [{ ...SUMMARY, readiness }],
    });
    const statusEvent = ev('agentStatusChanged', { status, previous: 'offline' });
    return view(applyRuntimeFrame(state, { type: 'agentEvent', event: statusEvent }));
  };

  test('"에이전트 시작" only without a process and with a readiness that runs', () => {
    expect(canStart(at('offline'))).toBe(true);
    expect(canStart(at('error'))).toBe(true);
    expect(canStart(at('offline', 'auth_required'))).toBe(true);
    expect(canStart(at('offline', 'executable_missing'))).toBe(false);
    expect(canStart(at('offline', 'unsupported'))).toBe(false);
    expect(canStart(at('ready'))).toBe(false);
  });

  test('"에이전트 종료" whenever there is a process', () => {
    expect(canStop(at('offline'))).toBe(false);
    expect(canStop(at('error'))).toBe(false);
    const live: AgentStatus[] = [
      'starting',
      'ready',
      'running',
      'awaiting_permission',
      'cancelling',
    ];
    for (const status of live) {
      expect(canStop(at(status))).toBe(true);
    }
  });

  test('the harness badge is the adapter-reported title, else the harness id', () => {
    expect(harnessBadge(view(stateWith()))).toBe('codex');
    const ev = events();
    const v = view(
      stateWith(
        ev(
          'sessionStarted',
          { sessionId: SESSION, agentInfo: { name: 'codex-acp', version: '2', title: 'Codex' } },
          { sessionId: SESSION },
        ),
      ),
    );
    expect(harnessBadge(v)).toBe('Codex');
  });
});

describe('agent identity (ux.md 에이전트 이름 식별)', () => {
  /** This agent saved as `name` (and `harnessId`), after `agentEvents`. */
  const named = (name: string, harnessId = SUMMARY.harnessId, ...agentEvents: AgentEvent[]) =>
    view(
      applyRuntimeFrame(stateWith(...agentEvents), {
        type: 'agentsUpdated',
        agents: [{ ...SUMMARY, name, harnessId }],
      }),
    );
  /** The adapter's agentInfo on `sessionStarted`; `title` is optional in ACP. */
  const reported = (title?: string, name = 'codex-acp') => {
    const ev = events();
    return ev(
      'sessionStarted',
      {
        sessionId: SESSION,
        agentInfo: { name, version: '2', ...(title === undefined ? {} : { title }) },
      },
      { sessionId: SESSION },
    );
  };

  test('a name that is the harness reads once, the harness in its tooltip', () => {
    // Before any report the badge is the harness id: "Codex" is "codex" without case.
    expect(agentIdentity(named('Codex'))).toEqual({
      name: 'Codex',
      badge: null,
      nameTitle: '실행기: codex',
    });
    // The adapter's title, once reported (the seeded agent's "Codex Codex").
    expect(agentIdentity(named('Codex', 'codex', reported('Codex')))).toEqual({
      name: 'Codex',
      badge: null,
      nameTitle: '실행기: Codex',
    });
  });

  test('blanks around either side and case do not tell them apart', () => {
    expect(agentIdentity(named('  CODEX ')).badge).toBeNull();
    expect(agentIdentity(named('codex', 'codex', reported(' Codex  '))).badge).toBeNull();
    // The name is shown as saved.
    expect(agentIdentity(named('  CODEX ')).name).toBe('  CODEX ');
  });

  test('a name that differs from its harness keeps the badge, with no name tooltip', () => {
    expect(agentIdentity(named('Gemini', 'gemini-cli'))).toEqual({
      name: 'Gemini',
      badge: 'gemini-cli',
      nameTitle: null,
    });
    // Containing the harness name is not being it.
    expect(agentIdentity(named('Codex 2', 'codex', reported('Codex'))).badge).toBe('Codex');
    expect(agentIdentity(named('Reviewer', 'codex', reported('Codex'))).badge).toBe('Codex');
    expect(agentIdentity(named('Code x')).badge).toBe('codex');
  });

  test('once reported, the name is compared with the badge text, not the harness id', () => {
    // claude-agent-acp reports the title "Claude Agent" (ADR 0003) for the harness "claude".
    expect(agentIdentity(named('Claude Agent', 'claude', reported('Claude Agent')))).toEqual({
      name: 'Claude Agent',
      badge: null,
      nameTitle: '실행기: Claude Agent',
    });
    // The harness id is no longer the badge, so a name equal to it keeps the badge.
    expect(agentIdentity(named('codex', 'codex', reported('Codex CLI')))).toEqual({
      name: 'codex',
      badge: 'Codex CLI',
      nameTitle: null,
    });
  });

  test('an adapter that reports no title is badged and compared by its agentInfo.name', () => {
    expect(agentIdentity(named('Codex', 'codex', reported(undefined, 'codex-acp')))).toEqual({
      name: 'Codex',
      badge: 'codex-acp',
      nameTitle: null,
    });
    expect(agentIdentity(named('Codex-ACP', 'codex', reported(undefined, 'codex-acp')))).toEqual({
      name: 'Codex-ACP',
      badge: null,
      nameTitle: '실행기: codex-acp',
    });
  });
});

describe('file tree rows', () => {
  test('folders first, children under their folder, names without case', () => {
    const rows = treeRows([
      { relativePath: 'README.md', directory: false },
      { relativePath: 'src', directory: true },
      { relativePath: 'b.txt', directory: false },
      { relativePath: 'src/main.ts', directory: false },
      { relativePath: '.git', directory: true },
      { relativePath: 'src/lib', directory: true },
      { relativePath: 'A.txt', directory: false },
    ]);
    expect(rows.map((row) => `${'  '.repeat(row.depth)}${row.name}`)).toEqual([
      '.git',
      'src',
      '  lib',
      '  main.ts',
      'A.txt',
      'b.txt',
      'README.md',
    ]);
  });
});

describe('run outcome tones (ux.md 실행 결과)', () => {
  const ended = (outcome: RunOutcome, errorCode?: 'PROCESS_EXITED') => {
    const ev = events();
    const run = { runId: RUN, sessionId: SESSION };
    const before = [...started(ev), ev('runStarted', { runId: RUN, prompt: 'x' }, run)];
    const end = ev(
      'runEnded',
      { runId: RUN, outcome, ...(errorCode ? { errorCode } : {}), unfinishedToolCalls: [] },
      run,
    );
    const v = view(stateWith(...before, end));
    return { status: runStatus(v), row: eventRows([end])[0] };
  };

  test.each([
    ['completed', '완료', 'completed', 'success'],
    ['cancelled', '취소됨', 'cancelled', 'muted'],
    ['refused', '거절됨', 'warning', 'warning'],
    ['limit_reached', '한도 도달', 'warning', 'warning'],
    ['failed', '실행 실패', 'error', 'error'],
  ] as const)('%s reads %s, with the same tone in both places', (outcome, text, tone, rowTone) => {
    const { status, row } = ended(outcome);
    expect(status).toEqual({ text, tone });
    expect(row.text).toBe(text);
    expect(row.tone).toBe(rowTone);
  });

  test('an ended run never takes the amber of "승인 대기"', () => {
    for (const outcome of [
      'completed',
      'cancelled',
      'refused',
      'limit_reached',
      'failed',
    ] as const) {
      expect(ended(outcome).status.tone).not.toBe('waiting');
      expect(ended(outcome).row.tone).not.toBe('permission');
    }
  });
});

describe('context usage (R12)', () => {
  const withUsage = (data: {
    used: number;
    size: number;
    cost?: { amount: number; currency: string };
  }) => {
    const ev = events();
    return usageText(view(stateWith(...started(ev), ev('usage', data, { sessionId: SESSION }))));
  };

  test('used / size, and the cost only when reported', () => {
    expect(withUsage({ used: 50, size: 200 })).toBe('50 / 200 (25%)');
    expect(withUsage({ used: 50, size: 200, cost: { amount: 0.42, currency: 'USD' } })).toBe(
      '50 / 200 (25%) · 비용 0.42 USD',
    );
  });
});

describe('command answers (commandFeedback.ts)', () => {
  const rejected = (patch: Partial<CommandRejected>): CommandRejected => ({
    type: 'commandRejected',
    command: 'startAgent',
    reason: 'INVALID_STATE',
    message: 'no',
    agentId: AGENT,
    ...patch,
  });

  function sent(
    state: RuntimeState,
    command: Parameters<typeof markSent>[1],
    marks: SentMarks = {},
  ) {
    const mark = markSent(state, command);
    return mark ? { ...marks, [mark[0]]: mark[1] } : marks;
  }

  test('a rejection newer than the command answers it; the next command clears it', () => {
    const base = stateWith();
    let marks = sent(base, { type: 'sendPrompt', agentId: AGENT, text: 'go' });
    expect(rejectionOf(base, marks, agentSubject(AGENT))).toBeNull();
    const refused = applyRuntimeFrame(base, rejected({ command: 'sendPrompt' }));
    expect(rejectionOf(refused, marks, agentSubject(AGENT))?.command).toBe('sendPrompt');
    marks = sent(refused, { type: 'sendPrompt', agentId: AGENT, text: 'again' });
    expect(rejectionOf(refused, marks, agentSubject(AGENT))).toBeNull();
  });

  test('a rejection for another agent or another request is not this one', () => {
    const base = stateWith();
    const marks = sent(
      base,
      { type: 'respondPermission', permissionId: 'p1', optionId: 'yes' },
      sent(base, { type: 'startAgent', agentId: AGENT }),
    );
    const other = applyRuntimeFrame(base, rejected({ agentId: 'someone-else' }));
    expect(rejectionOf(other, marks, agentSubject(AGENT))).toBeNull();
    const permission = applyRuntimeFrame(
      base,
      rejected({
        command: 'respondPermission',
        reason: 'UNKNOWN_PERMISSION',
        agentId: undefined,
        permissionId: 'p1',
      }),
    );
    expect(rejectionOf(permission, marks, permissionSubject('p1'))?.reason).toBe(
      'UNKNOWN_PERMISSION',
    );
    expect(rejectionOf(permission, marks, permissionSubject('p2'))).toBeNull();
    expect(rejectionOf(permission, marks, agentSubject(AGENT))).toBeNull();
  });

  test('"연결 확인" runs until its result or its rejection arrives', () => {
    const base = stateWith();
    const marks = sent(base, { type: 'checkConnection', agentId: AGENT });
    expect(checkOf(base, marks, AGENT)).toEqual({ pending: true });
    const result: ConnectionChecked = {
      type: 'connectionChecked',
      agentId: AGENT,
      readiness: 'configured',
      authMethods: [],
    };
    expect(checkOf(applyRuntimeFrame(base, result), marks, AGENT)).toEqual({
      pending: false,
      result,
    });
    const refused = applyRuntimeFrame(base, rejected({ command: 'checkConnection' }));
    expect(checkOf(refused, marks, AGENT)).toEqual({ pending: false, result: null });
    expect(checkOf(base, {}, AGENT)).toBeNull();
  });

  test('"연결 확인" only where the runtime takes it: no process', () => {
    const ev = events();
    expect(canCheck(view(stateWith()))).toBe(true);
    expect(canCheck(view(stateWith(...started(ev))))).toBe(false);
  });
});
