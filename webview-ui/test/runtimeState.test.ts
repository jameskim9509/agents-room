/**
 * runtimeState: what the desktop renderer holds from the local runtime's
 * frames. The frames are the contract fixtures (core/fixtures/valid/server),
 * so every input here is one the runtime may send.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, test, vi } from 'vitest';

import type {
  AgentEventMessage,
  AgentsUpdated,
  CommandRejected,
  ConnectionChecked,
  ProjectOpened,
  ProjectOpenFailed,
  RuntimeServerMessage,
  Snapshot,
} from '../../core/src/messages.ts';
import { RUNTIME_RECENT_EVENTS_MAX } from '../src/constants.ts';
import {
  applyRuntimeFrame,
  createRuntimeStateStore,
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

const snapshot = () => fixture<Snapshot>('snapshot');
/** The first agent of snapshot.json (lastSeq 20) and the second (lastSeq 13). */
const AGENT_A = snapshot().agents[0].agent.agentId;
const AGENT_B = snapshot().agents[1].agent.agentId;

/** agentEvent.runStarted.json for `agentId` with `seq`. */
function event(agentId: string, seq: number): AgentEventMessage {
  const frame = fixture<AgentEventMessage>('agentEvent.runStarted');
  return { ...frame, event: { ...frame.event, agentId, seq } };
}

function apply(...frames: RuntimeServerMessage[]): RuntimeState {
  return frames.reduce(applyRuntimeFrame, INITIAL_RUNTIME_STATE);
}

const seqs = (state: RuntimeState, agentId: string) =>
  (state.recentEvents[agentId] ?? []).map((e) => e.seq);

describe('snapshot', () => {
  test('replaces everything held before it with the runtime view', () => {
    const before = apply(
      fixture<Snapshot>('snapshot.empty'),
      fixture<CommandRejected>('commandRejected'),
      fixture<ProjectOpenFailed>('projectOpenFailed'),
      event('stale-agent', 3),
    );

    const state = applyRuntimeFrame(before, snapshot());

    const frame = snapshot();
    expect(state.snapshots).toBe(2);
    expect(state.currentProjectId).toBe(frame.currentProjectId);
    expect(state.recentProjects).toEqual(frame.recentProjects);
    expect(state.project).toEqual(frame.project);
    expect(state.agents).toEqual(frame.agents.map((a) => a.agent));
    expect(state.agentSnapshots[AGENT_A]).toEqual(frame.agents[0]);
    expect(state.lastSeq).toEqual({ [AGENT_A]: 20, [AGENT_B]: 13 });
    expect(seqs(state, AGENT_A)).toEqual(frame.agents[0].recentEvents.map((e) => e.seq));
    expect(state.recentEvents['stale-agent']).toBeUndefined();
    expect(state.commandRejected).toBeNull();
    expect(state.projectOpenFailed).toBeNull();
  });

  test('a snapshot without a project holds null for it (D3) and no agents', () => {
    const state = apply(fixture<Snapshot>('snapshot.empty'));

    expect(state).toEqual({ ...INITIAL_RUNTIME_STATE, snapshots: 1 });
  });
});

describe('agentEvent (D4)', () => {
  test('an event the snapshot already covers is dropped, a newer one is held', () => {
    const synced = apply(snapshot());

    expect(applyRuntimeFrame(synced, event(AGENT_A, 20))).toBe(synced);
    expect(applyRuntimeFrame(synced, event(AGENT_A, 7))).toBe(synced);

    const state = applyRuntimeFrame(synced, event(AGENT_A, 21));
    expect(state.lastSeq[AGENT_A]).toBe(21);
    expect(seqs(state, AGENT_A).at(-1)).toBe(21);
    expect(state.lastSeq[AGENT_B]).toBe(13);
    // A repeat of the event just held changes nothing either.
    expect(applyRuntimeFrame(state, event(AGENT_A, 21))).toBe(state);
  });

  test('after a reconnect, the new snapshot sets where events resume', () => {
    const before = apply(snapshot(), event(AGENT_A, 21), event(AGENT_A, 22));
    const resumed = snapshot();
    resumed.agents[0] = { ...resumed.agents[0], lastSeq: 25 };

    const state = applyRuntimeFrame(before, resumed);

    expect(applyRuntimeFrame(state, event(AGENT_A, 23))).toBe(state);
    expect(applyRuntimeFrame(state, event(AGENT_A, 25))).toBe(state);
    expect(applyRuntimeFrame(state, event(AGENT_A, 26)).lastSeq[AGENT_A]).toBe(26);
  });

  test('an agent the snapshot did not list starts at seq 1', () => {
    const state = apply(fixture<Snapshot>('snapshot.empty'), event('new-agent', 1));

    expect(state.lastSeq['new-agent']).toBe(1);
    expect(seqs(state, 'new-agent')).toEqual([1]);
  });

  test(`keeps the newest ${RUNTIME_RECENT_EVENTS_MAX} events per agent`, () => {
    let state = apply(fixture<Snapshot>('snapshot.empty'));
    const total = RUNTIME_RECENT_EVENTS_MAX + 5;
    for (let seq = 1; seq <= total; seq++) state = applyRuntimeFrame(state, event(AGENT_A, seq));

    expect(seqs(state, AGENT_A)).toHaveLength(RUNTIME_RECENT_EVENTS_MAX);
    expect(seqs(state, AGENT_A)[0]).toBe(6);
    expect(state.lastSeq[AGENT_A]).toBe(total);
  });
});

describe('result frames', () => {
  test('projectOpened sets the current project and clears an earlier failure', () => {
    const failed = apply(
      fixture<Snapshot>('snapshot.empty'),
      fixture<ProjectOpenFailed>('projectOpenFailed'),
    );
    expect(failed.projectOpenFailed).toEqual(fixture('projectOpenFailed'));

    const opened = fixture<ProjectOpened>('projectOpened');
    const state = applyRuntimeFrame(failed, opened);

    expect(state.project).toEqual(opened.project);
    expect(state.currentProjectId).toBe(opened.project.projectId);
    expect(state.projectOpenFailed).toBeNull();
  });

  test('agentsUpdated replaces the list and drops the records of agents that left it', () => {
    const updated = fixture<AgentsUpdated>('agentsUpdated');
    const listed = new Set(updated.agents.map((agent) => agent.agentId));
    expect(listed.has(AGENT_A)).toBe(true);
    expect(listed.has(AGENT_B)).toBe(false);

    const state = apply(snapshot(), updated);

    expect(state.agents).toEqual(updated.agents);
    expect(Object.keys(state.agentSnapshots)).toEqual([AGENT_A]);
    expect(Object.keys(state.lastSeq)).toEqual([AGENT_A]);
    expect(Object.keys(state.recentEvents)).toEqual([AGENT_A]);
  });

  test('connectionChecked is held per agent, commandRejected as the latest one', () => {
    const checked = fixture<ConnectionChecked>('connectionChecked');
    const rejected = fixture<CommandRejected>('commandRejected');
    const later = fixture<CommandRejected>('commandRejected.invalidMessage');

    const state = apply(snapshot(), checked, rejected, later);

    expect(state.connectionChecks).toEqual({ [checked.agentId]: checked });
    expect(state.commandRejected).toEqual(later);
  });
});

test('the store applies every frame the transport delivers and notifies only on a change', () => {
  let deliver: (frame: RuntimeServerMessage) => void = () => undefined;
  const store = createRuntimeStateStore({
    onMessage: (handler) => {
      deliver = handler;
      return () => undefined;
    },
  });
  const listener = vi.fn();
  store.subscribe(listener);

  deliver(snapshot());
  deliver(event(AGENT_A, 20));
  deliver(event(AGENT_A, 21));

  expect(listener).toHaveBeenCalledTimes(2);
  expect(store.getState().lastSeq[AGENT_A]).toBe(21);
});
