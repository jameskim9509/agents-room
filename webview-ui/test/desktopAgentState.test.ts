/**
 * The agent state the desktop store folds as each event arrives
 * (runtimeAgentState.ts), read through the panels' view (agentView.ts): it
 * must not depend on how many events the store's 200-event lists still hold
 * (ADR 0005 decision 2: the snapshot, then every newer event). A real Codex
 * answer streams one event per chunk, so a run easily passes 200 events.
 * Inputs are the contract fixtures or events built in their shape.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, test } from 'vitest';

import type {
  AgentEvent,
  AgentSummary,
  RuntimeServerMessage,
  Snapshot,
} from '../../core/src/messages.ts';
import { RUNTIME_RECENT_EVENTS_MAX } from '../src/constants.ts';
import { harnessBadge } from '../src/desktopUi/agentControls.ts';
import { deriveAgentView } from '../src/desktopUi/agentView.ts';
import { eventRows } from '../src/desktopUi/eventRows.ts';
import { appendEventLog } from '../src/runtimeAgentState.ts';
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
const SESSION = 'session-1';
const RUN = 'run-1';
const RUN_IDS = { runId: RUN, sessionId: SESSION };
/** Well past the store's 200-event lists. */
const LONG = RUNTIME_RECENT_EVENTS_MAX + 50;

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

/** This agent's events with consecutive `seq` from `from`. */
function events(from = 1, agentId = AGENT) {
  let seq = from - 1;
  return <K extends AgentEvent['kind']>(
    kind: K,
    data: Extract<AgentEvent, { kind: K }>['data'],
    ids: { runId?: string | null; sessionId?: string | null } = {},
  ): AgentEvent =>
    ({
      projectId: PROJECT,
      agentId,
      sessionId: ids.sessionId ?? null,
      runId: ids.runId ?? null,
      seq: ++seq,
      receivedAt: 0,
      kind,
      data,
    }) as AgentEvent;
}

function apply(state: RuntimeState, agentEvents: readonly AgentEvent[]): RuntimeState {
  return agentEvents.reduce(
    (s, event) => applyRuntimeFrame(s, { type: 'agentEvent', event }),
    state,
  );
}

/** A renderer that connected with no project, then got the project (no snapshot of the agent). */
function opened(): RuntimeState {
  return [
    fixture<Snapshot>('snapshot.empty'),
    { type: 'agentsUpdated', agents: [SUMMARY] } as RuntimeServerMessage,
  ].reduce(applyRuntimeFrame, INITIAL_RUNTIME_STATE);
}

const view = (state: RuntimeState) => deriveAgentView(state, state.agents[0]);

/** Start, session, a run and a tool in progress (seq 1..7). */
function runInProgress(ev: ReturnType<typeof events>): AgentEvent[] {
  return [
    ev('agentStatusChanged', { status: 'starting', previous: 'offline' }),
    ev('agentStarting', { stage: 'spawn' }),
    ev(
      'sessionStarted',
      { sessionId: SESSION, agentInfo: { name: 'codex-acp', version: '2', title: 'Codex' } },
      { sessionId: SESSION },
    ),
    ev('agentStatusChanged', { status: 'ready', previous: 'starting' }, { sessionId: SESSION }),
    ev('runStarted', { runId: RUN, prompt: 'Run the long job' }, RUN_IDS),
    ev('agentStatusChanged', { status: 'running', previous: 'ready' }, RUN_IDS),
    ev(
      'toolCall',
      {
        toolCall: {
          toolCallId: 'call-long',
          title: 'Run the long job',
          kind: 'execute',
          status: 'in_progress',
          locations: [],
        },
      },
      RUN_IDS,
    ),
  ];
}

function chunk(ev: ReturnType<typeof events>, text: string, messageId: string) {
  return ev('agentMessageChunk', { messageId, contentType: 'text', text }, RUN_IDS);
}

describe('a long run (more events than the store lists hold)', () => {
  test('after agentsUpdated: still running, with its session, request, tool and whole answer', () => {
    const ev = events();
    const start = runInProgress(ev);
    const begin = chunk(ev, 'BEGIN ', 'm-long');
    const words = Array.from({ length: LONG }, (_, i) => chunk(ev, `w${i} `, 'm-long'));
    const state = apply(opened(), [...start, begin, ...words]);
    expect(state.recentEvents[AGENT]).toHaveLength(RUNTIME_RECENT_EVENTS_MAX);

    const v = view(state);
    expect(v.status).toBe('running');
    expect(v.sessionState).toBe('open');
    expect(v.prompt).toBe('Run the long job');
    expect(v.runId).toBe(RUN);
    expect(v.currentTool?.title).toBe('Run the long job');
    expect(v.response?.startsWith('BEGIN w0 w1 ')).toBe(true);
    expect(v.response?.endsWith(`w${LONG - 1} `)).toBe(true);
    expect(v.responsePartial).toBe(false);
    expect(harnessBadge(v)).toBe('Codex');
    // One message streaming is one entry of the event list, its first chunk's seq kept.
    const answer = v.events.filter((e) => e.kind === 'agentMessageChunk');
    expect(answer).toHaveLength(1);
    expect(answer[0].seq).toBe(8);
    expect(v.lastSeq).toBe(8 + LONG);
    expect(eventRows(v.events, v.answeredOptions).at(-1)?.label).toBe('에이전트 메시지');
  });

  test('a burst of separate messages: the list keeps 200 rows, the run keeps every message', () => {
    const ev = events();
    const start = runInProgress(ev);
    const burst = Array.from({ length: 300 }, (_, i) =>
      chunk(ev, `update ${i + 1}`, `msg-burst-${i + 1}`),
    );
    const state = apply(opened(), [
      ...start,
      ...burst,
      ev(
        'runEnded',
        { runId: RUN, outcome: 'completed', stopReason: 'end_turn', unfinishedToolCalls: [] },
        RUN_IDS,
      ),
      ev('agentStatusChanged', { status: 'ready', previous: 'running' }, { sessionId: SESSION }),
    ]);
    const v = view(state);
    expect(v.status).toBe('ready');
    expect(v.sessionState).toBe('open');
    expect(v.sessionId).toBe(SESSION);
    expect(v.prompt).toBe('Run the long job');
    expect(v.lastRun?.outcome).toBe('completed');
    // Every message of the run, in order, one per line (ux.md 현재 실행: 최종 응답).
    expect(v.response?.split('\n')).toEqual(burst.map((_, i) => `update ${i + 1}`));
    expect(v.events).toHaveLength(RUNTIME_RECENT_EVENTS_MAX);
    expect(eventRows(v.events)).toHaveLength(RUNTIME_RECENT_EVENTS_MAX);
    // The run's start left the list, not the panel.
    expect(v.events.some((e) => e.kind === 'runStarted')).toBe(false);
  });

  test('after a snapshot: an answered permission stays answered, the run stays running', () => {
    const snapshot = fixture<Snapshot>('snapshot');
    const [agentSnapshot] = snapshot.agents;
    const [request] = agentSnapshot.pendingPermissions;
    const ids = { runId: agentSnapshot.runId, sessionId: agentSnapshot.sessionId };
    const ev = events(agentSnapshot.lastSeq + 1, agentSnapshot.agent.agentId);
    const state = apply(applyRuntimeFrame(INITIAL_RUNTIME_STATE, snapshot), [
      ev(
        'permissionResolved',
        { permissionId: request.permissionId, outcome: 'selected', optionId: 'approved' },
        ids,
      ),
      ev('agentStatusChanged', { status: 'running', previous: 'awaiting_permission' }, ids),
      ...Array.from({ length: LONG }, (_, i) =>
        ev('agentMessageChunk', { messageId: `m${i}`, contentType: 'text', text: `${i}` }, ids),
      ),
    ]);
    const v = deriveAgentView(state, agentSnapshot.agent);
    expect(v.status).toBe('running');
    expect(v.pendingPermissions).toEqual([]);
    expect(v.sessionState).toBe('open');
    expect(v.prompt).toBe(agentSnapshot.prompt);
    expect(v.currentTool?.toolCallId).toBe('call_9b1c');
    // The request left the list; its answer's row still names the option by kind.
    expect(v.events.some((e) => e.kind === 'permissionRequested')).toBe(false);
    const resolved = eventRows(
      [
        ev(
          'permissionResolved',
          { permissionId: request.permissionId, outcome: 'selected', optionId: 'approved' },
          ids,
        ),
      ],
      v.answeredOptions,
    );
    expect(resolved[0].text).toBe('이번만 허용');
  });
});

describe('answer text', () => {
  test('a snapshot tail that still holds the run start rebuilds the whole answer', () => {
    const snapshot = fixture<Snapshot>('snapshot');
    const v = deriveAgentView(
      applyRuntimeFrame(INITIAL_RUNTIME_STATE, snapshot),
      snapshot.agents[0].agent,
    );
    const tailText = snapshot.agents[0].recentEvents
      .filter((e) => e.kind === 'agentMessageChunk')
      .map((e) => (e.kind === 'agentMessageChunk' ? e.data.text : ''))
      .join('');
    expect(v.response).toBe(tailText);
    expect(v.responsePartial).toBe(false);
  });

  test('a snapshot tail without the run start marks the answer as possibly cut', () => {
    const snapshot = fixture<Snapshot>('snapshot');
    const agent = snapshot.agents[0];
    const cut: Snapshot = {
      ...snapshot,
      agents: [
        { ...agent, recentEvents: agent.recentEvents.filter((e) => e.kind !== 'runStarted') },
      ],
    };
    const v = deriveAgentView(applyRuntimeFrame(INITIAL_RUNTIME_STATE, cut), agent.agent);
    expect(v.responsePartial).toBe(true);
  });

  test('a late chunk (no run) and a thought belong to no answer', () => {
    const ev = events();
    const v = view(
      apply(opened(), [
        ...runInProgress(ev),
        chunk(ev, 'Done.', 'm1'),
        ev('agentThoughtChunk', { contentType: 'text', text: 'hmm' }, RUN_IDS),
        ev('runEnded', { runId: RUN, outcome: 'completed', unfinishedToolCalls: [] }, RUN_IDS),
        ev('agentMessageChunk', { contentType: 'text', text: ' late' }, { sessionId: SESSION }),
      ]),
    );
    expect(v.response).toBe('Done.');
  });
});

describe('restart (R8)', () => {
  test("a new start takes the previous session's run out of the current run", () => {
    const ev = events();
    const crashed = [
      ...runInProgress(ev),
      chunk(ev, 'Building', 'm1'),
      ev(
        'runEnded',
        {
          runId: RUN,
          outcome: 'failed',
          errorCode: 'PROCESS_EXITED',
          unfinishedToolCalls: [{ toolCallId: 'call-long', derivedStatus: 'incomplete' }],
        },
        RUN_IDS,
      ),
      ev('sessionEnded', { reason: 'exited', exitCode: 3 }, { sessionId: SESSION }),
      ev('agentError', { code: 'PROCESS_EXITED', stage: 'prompt', message: 'exit code 3' }),
      ev('agentStatusChanged', { status: 'error', previous: 'running' }),
    ];
    const afterCrash = view(apply(opened(), crashed));
    // Until the restart, the crashed run is the one to read (R7).
    expect(afterCrash.prompt).toBe('Run the long job');
    expect(afterCrash.lastRun?.errorCode).toBe('PROCESS_EXITED');
    expect(afterCrash.lastRunToolCalls.map((c) => c.title)).toEqual(['Run the long job']);
    // The badge keeps the adapter's name after its session ended.
    expect(harnessBadge(afterCrash)).toBe('Codex');

    const restarted = apply(opened(), [
      ...crashed,
      ev('agentStatusChanged', { status: 'starting', previous: 'error' }),
      ev('sessionStarted', { sessionId: 'session-2' }, { sessionId: 'session-2' }),
      ev(
        'agentStatusChanged',
        { status: 'ready', previous: 'starting' },
        { sessionId: 'session-2' },
      ),
    ]);
    const v = view(restarted);
    expect(v.sessionState).toBe('open');
    expect(v.prompt).toBeNull();
    expect(v.lastRun).toBeNull();
    expect(v.response).toBeNull();
    expect(harnessBadge(v)).toBe('Codex');
    // The list still has it: the panel only stops calling it current.
    expect(v.events.some((e) => e.kind === 'runEnded')).toBe(true);
  });

  test('a snapshot after a restart does not bring the old run back', () => {
    const snapshot = fixture<Snapshot>('snapshot');
    const errored = snapshot.agents[1];
    const ev = events(errored.lastSeq + 1, errored.agent.agentId);
    const restartedTail = [
      ...errored.recentEvents,
      ev('agentStatusChanged', { status: 'starting', previous: 'error' }),
      ev('sessionStarted', { sessionId: 'session-2' }, { sessionId: 'session-2' }),
      ev(
        'agentStatusChanged',
        { status: 'ready', previous: 'starting' },
        { sessionId: 'session-2' },
      ),
    ];
    const resumed: Snapshot = {
      ...snapshot,
      agents: [
        {
          ...errored,
          status: 'ready',
          sessionId: 'session-2',
          lastSeq: errored.lastSeq + 3,
          recentEvents: restartedTail,
          lastError: undefined,
        },
      ],
    };
    const v = deriveAgentView(applyRuntimeFrame(INITIAL_RUNTIME_STATE, resumed), errored.agent);
    expect(v.sessionState).toBe('open');
    expect(v.prompt).toBeNull();
    expect(v.lastRun).toBeNull();
  });
});

describe('event list (D24)', () => {
  test('joins a message as it streams, caps entries, and keeps thoughts live', () => {
    const ev = events();
    let log: AgentEvent[] = [];
    for (const event of [
      chunk(ev, 'a', 'm1'),
      chunk(ev, 'b', 'm1'),
      ev('agentThoughtChunk', { contentType: 'text', text: 'x' }, RUN_IDS),
      ev('agentThoughtChunk', { contentType: 'text', text: 'y' }, RUN_IDS),
      chunk(ev, 'c', 'm2'),
    ]) {
      log = appendEventLog(log, event);
    }
    expect(log.map((e) => [e.seq, e.kind, 'text' in e.data ? e.data.text : null])).toEqual([
      [1, 'agentMessageChunk', 'ab'],
      [3, 'agentThoughtChunk', 'xy'],
      [5, 'agentMessageChunk', 'c'],
    ]);
    const capped = Array.from({ length: 5 }, (_, i) => chunk(ev, `${i}`, `n${i}`)).reduce(
      (list, event) => appendEventLog(list, event, 3),
      [] as AgentEvent[],
    );
    expect(capped.map((e) => ('text' in e.data ? e.data.text : null))).toEqual(['2', '3', '4']);
  });
});
