/**
 * The pixel world's side of the desktop shell (SCRUM-27, D20): the agentId ↔
 * character id map, the status/kind → behaviour mapping, and the world sync
 * that drives the real OfficeState only on transitions, so repeated events
 * never add a character or restart an animation.
 *
 * Like greeter.test.ts, this tests the OfficeState domain model, whose
 * invariants (how many engine calls, what stays pinned) e2e cannot observe.
 */
import { describe, expect, test, vi } from 'vitest';

import type { AgentSummary, ToolCallState, ToolKind } from '../../core/src/messages.ts';
import { AgentHandles } from '../src/desktopUi/agentHandles.ts';
import type { AgentView } from '../src/desktopUi/agentView.ts';
import { characterBehaviour, kindActivity } from '../src/desktopUi/characterBehaviour.ts';
import { RuntimeWorld } from '../src/desktopUi/runtimeWorld.ts';
import { OfficeState } from '../src/office/engine/officeState.ts';
import type { OfficeLayout } from '../src/office/types.ts';
import { CharacterState, MATRIX_EFFECT_DURATION, TileType } from '../src/office/types.ts';

const AGENT = '0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d';
const OTHER = '11111111-2222-4333-8444-555555555555';

function summary(agentId = AGENT, palette = 0): AgentSummary {
  return {
    agentId,
    projectId: 'p',
    name: agentId === AGENT ? 'Codex' : 'Other',
    harnessId: 'codex',
    providerId: 'openai',
    modelId: null,
    authMethodId: 'chat-gpt',
    readiness: 'configured',
    palette,
    hueShift: 0,
    reportsToolDetails: true,
    reportsSubagentSessions: false,
  };
}

function agentView(patch: Partial<AgentView> = {}, agent = summary()): AgentView {
  return {
    agent,
    status: 'offline',
    sessionId: null,
    sessionState: 'none',
    startingStage: null,
    runId: null,
    prompt: null,
    toolCalls: [],
    currentTool: null,
    lastRun: null,
    lastRunToolCalls: [],
    response: null,
    responsePartial: false,
    pendingPermissions: [],
    answeredOptions: {},
    lastError: null,
    authStatus: null,
    agentInfo: null,
    reportedAgentInfo: null,
    plan: null,
    usage: null,
    sessionTitle: null,
    currentModeId: null,
    modes: null,
    configOptions: null,
    events: [],
    lastSeq: 0,
    ...patch,
  };
}

function tool(
  kind: ToolKind | undefined,
  status: ToolCallState['status'] = 'in_progress',
): ToolCallState {
  return { toolCallId: `call-${kind ?? 'none'}`, title: 'A tool', kind, status, locations: [] };
}

function running(kind?: ToolKind): AgentView {
  const currentTool = kind === undefined ? null : tool(kind);
  return agentView({
    status: 'running',
    runId: 'run-1',
    sessionId: 's',
    sessionState: 'open',
    toolCalls: currentTool ? [currentTool] : [],
    currentTool,
  });
}

/** A 6x6 office of floor only: no chairs, so characters sit and type where they stand. */
function office(): OfficeState {
  const cols = 6;
  const rows = 6;
  const layout: OfficeLayout = {
    version: 1,
    cols,
    rows,
    tiles: new Array<TileType>(cols * rows).fill(TileType.FLOOR_1),
    furniture: [],
  };
  return new OfficeState(layout);
}

function settle(os: OfficeState): void {
  // Past the spawn effect, then a few frames of the FSM.
  for (let i = 0; i < 20; i++) os.update(MATRIX_EFFECT_DURATION);
}

describe('agent handles', () => {
  test('one positive, stable handle per agent, never reused', () => {
    const handles = new AgentHandles();
    const a = handles.handleFor(AGENT);
    const b = handles.handleFor(OTHER);
    expect(a).toBeGreaterThan(0);
    expect(b).not.toBe(a);
    expect(handles.handleFor(AGENT)).toBe(a);
    expect(handles.agentFor(a)).toBe(AGENT);
    expect(handles.agentFor(null)).toBeNull();
    expect(handles.agentFor(-1)).toBeNull();
  });
});

describe('character behaviour (ux.md 상태 어휘, 행동 매핑)', () => {
  test.each([
    ['search', 'search', 'search'],
    ['read', 'read', null],
    ['fetch', 'fetch', null],
    ['edit', 'edit', null],
    ['delete', 'delete', null],
    ['move', 'move', null],
    ['execute', 'execute', 'terminal'],
    ['think', 'other', 'generic'],
    ['switch_mode', 'other', 'generic'],
    ['other', 'other', 'generic'],
  ] as const)('%s works as tool %s with icon %s', (kind, engineTool, icon) => {
    expect(kindActivity(kind)).toEqual({ tool: engineTool, icon });
  });

  test('a kind this renderer does not know, or none, is the generic work display', () => {
    expect(kindActivity('frobnicate' as ToolKind)).toEqual({ tool: 'other', icon: 'generic' });
    expect(kindActivity(undefined)).toEqual({ tool: 'other', icon: 'generic' });
  });

  test('statuses map to poses and lasting bubbles', () => {
    expect(characterBehaviour(agentView())).toEqual({
      pose: 'offline',
      tool: null,
      icon: null,
      bubble: null,
    });
    expect(characterBehaviour(agentView({ status: 'starting' })).pose).toBe('seated');
    expect(characterBehaviour(agentView({ status: 'ready' })).pose).toBe('roaming');
    expect(characterBehaviour(running('read'))).toEqual({
      pose: 'working',
      tool: 'read',
      icon: null,
      bubble: null,
    });
    // Running with no unfinished tool call: generic work, never an invented tool (R5).
    expect(characterBehaviour(running())).toEqual({
      pose: 'working',
      tool: 'other',
      icon: 'generic',
      bubble: null,
    });
    expect(characterBehaviour({ ...running('execute'), status: 'awaiting_permission' })).toEqual({
      pose: 'working',
      tool: 'execute',
      icon: 'terminal',
      bubble: 'permission',
    });
    expect(characterBehaviour({ ...running('execute'), status: 'cancelling' }).pose).toBe('frozen');
    expect(characterBehaviour(agentView({ status: 'error' }))).toEqual({
      pose: 'seated',
      tool: null,
      icon: null,
      bubble: 'error',
    });
  });

  test('a finished tool call is not the current one: the generic display, not its kind', () => {
    const done = tool('search', 'completed');
    const v = { ...running(), toolCalls: [done], currentTool: null };
    expect(characterBehaviour(v).icon).toBe('generic');
  });
});

describe('runtime world', () => {
  test('one character per agent, offline ones seated, frozen and translucent', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([agentView(), agentView({}, summary(OTHER, 1))]);
    expect(os.characters.size).toBe(2);
    const id = world.characterOf(AGENT)!;
    const ch = os.characters.get(id)!;
    expect(ch.isRuntimeAgent).toBe(true);
    expect(ch.agentName).toBe('Codex');
    expect(ch.isActive).toBe(false);
    expect(ch.pinnedToSeat).toBe(true);
    expect(ch.isOffline).toBe(true);
    // Present at the first apply: no spawn effect, they were there before the renderer.
    expect(ch.matrixEffect).toBeNull();
    settle(os);
    expect(ch.state).toBe(CharacterState.TYPE);
    expect(ch.frame).toBe(0);
    expect(os.characters.get(world.characterOf(OTHER)!)?.palette).toBe(1);
    expect(world.agentOf(id)).toBe(AGENT);
  });

  test('repeated views change nothing in the engine', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([running('execute')]);
    const spies = [
      vi.spyOn(os, 'addAgent'),
      vi.spyOn(os, 'setAgentActive'),
      vi.spyOn(os, 'setAgentTool'),
      vi.spyOn(os, 'setAgentPinned'),
      vi.spyOn(os, 'setAgentActivityIcon'),
      vi.spyOn(os, 'showBubble'),
    ];
    // The same tool call reported 20 more times: the view is the same each time.
    for (let i = 0; i < 20; i++) world.apply([running('execute')]);
    for (const spy of spies) expect(spy).not.toHaveBeenCalled();
    expect(os.characters.size).toBe(1);
    expect(world.describe()[0].engineUpdates).toBe(0);
  });

  test('only the parts that change reach the engine', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([agentView({ status: 'ready' })]);
    const id = world.characterOf(AGENT)!;
    const active = vi.spyOn(os, 'setAgentActive');
    const toolSpy = vi.spyOn(os, 'setAgentTool');
    world.apply([running('read')]);
    expect(active).toHaveBeenCalledTimes(1);
    expect(os.characters.get(id)?.currentTool).toBe('read');
    // read → search: the tool and icon change, the character stays active (no second setAgentActive).
    world.apply([running('search')]);
    expect(active).toHaveBeenCalledTimes(1);
    expect(toolSpy).toHaveBeenLastCalledWith(id, 'search');
    expect(os.characters.get(id)?.activityIcon).toBe('search');
    world.apply([running('execute')]);
    expect(os.characters.get(id)?.activityIcon).toBe('terminal');
    expect(world.describe()[0].engineUpdates).toBe(3);
  });

  test('a permission request shows the lasting bubble, which a click does not clear (R9)', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([running('execute')]);
    const id = world.characterOf(AGENT)!;
    const asking = {
      ...running('execute'),
      status: 'awaiting_permission' as const,
      pendingPermissions: [{ permissionId: 'p1', toolCall: tool('execute'), options: [] }],
    };
    world.apply([asking]);
    expect(os.characters.get(id)?.bubbleType).toBe('permission');
    os.dismissBubble(id);
    settle(os);
    expect(os.characters.get(id)?.bubbleType).toBe('permission');
    // Answered: back to running, the bubble goes.
    world.apply([running('execute')]);
    expect(os.characters.get(id)?.bubbleType).toBeNull();
  });

  test('a run that ends while watched shows its outcome once; one ended before does not', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    const ended = agentView({
      status: 'ready',
      lastRun: { runId: 'old', outcome: 'completed', unfinishedToolCalls: [] },
    });
    world.apply([ended]);
    const id = world.characterOf(AGENT)!;
    expect(os.characters.get(id)?.bubbleType).toBeNull();

    world.apply([running('read')]);
    const completed = agentView({
      status: 'ready',
      lastRun: { runId: 'run-1', outcome: 'completed', unfinishedToolCalls: [] },
    });
    world.apply([completed]);
    expect(os.characters.get(id)?.bubbleType).toBe('waiting');
    // The checkmark fades by itself.
    settle(os);
    expect(os.characters.get(id)?.bubbleType).toBeNull();
    world.apply([completed]);
    expect(os.characters.get(id)?.bubbleType).toBeNull();

    world.apply([running('read')]);
    world.apply([
      agentView({
        status: 'ready',
        lastRun: { runId: 'run-2', outcome: 'refused', unfinishedToolCalls: [] },
      }),
    ]);
    expect(os.characters.get(id)?.bubbleType).toBe('warning');
  });

  test('an agent in error keeps its error bubble and sits at its seat', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([running('execute')]);
    const id = world.characterOf(AGENT)!;
    world.apply([
      agentView({
        status: 'error',
        lastRun: {
          runId: 'run-1',
          outcome: 'failed',
          errorCode: 'PROCESS_EXITED',
          unfinishedToolCalls: [],
        },
        lastError: { code: 'PROCESS_EXITED', stage: 'prompt', message: 'exit 3' },
      }),
    ]);
    const ch = os.characters.get(id)!;
    expect(ch.bubbleType).toBe('error');
    expect(ch.pinnedToSeat).toBe(true);
    expect(ch.isActive).toBe(false);
    settle(os);
    os.dismissBubble(id);
    expect(ch.bubbleType).toBe('error');
  });

  test('an agent that leaves despawns, and coming back gives it the same character id', () => {
    const os = office();
    const world = new RuntimeWorld(() => os);
    world.apply([agentView()]);
    const id = world.characterOf(AGENT)!;
    world.apply([]);
    expect(world.characterOf(AGENT)).toBeNull();
    expect(os.characters.get(id)?.matrixEffect).toBe('despawn');
    // Back before the effect ended: one character, not swallowed by the old despawn.
    world.apply([agentView()]);
    expect(world.characterOf(AGENT)).toBe(id);
    settle(os);
    expect(os.characters.get(id)).toBeDefined();
    expect(os.characters.size).toBe(1);
  });
});
