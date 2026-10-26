/**
 * desktopTransport: the desktop renderer's two transports. The runtime socket
 * carries only agents-room frames and feeds the runtime state store; the
 * office gets only the renderer's own asset frames, and nothing it sends (the
 * upstream `webviewReady` first) reaches the runtime. A fake WebSocket and a
 * fake preload bridge stand in for the browser and the shell.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import type { AgentsRoomBridge } from '../../core/src/desktopBridge.ts';
import type { ServerMessage, Snapshot } from '../../core/src/messages.ts';
import { createDesktopTransports } from '../src/transport/desktopTransport.ts';
import type { MessageTransport } from '../src/transport/types.ts';

const SNAPSHOT = JSON.parse(
  fs.readFileSync(
    path.resolve(
      path.dirname(fileURLToPath(import.meta.url)),
      '..',
      '..',
      'core',
      'fixtures',
      'valid',
      'server',
      'snapshot.json',
    ),
    'utf8',
  ),
) as Snapshot;
const TOKEN = 'a'.repeat(43);
const BRIDGE: AgentsRoomBridge = {
  getRuntimeEndpoint: async () => ({ url: 'ws://127.0.0.1:4567/ws', token: TOKEN }),
  pickProjectFolder: async () => null,
};

class FakeWebSocket {
  static readonly OPEN = 1;
  static instances: FakeWebSocket[] = [];

  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  readonly url: string;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.readyState = 3;
    this.onclose?.();
  }

  open(): void {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  receive(message: unknown): void {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
}

const flushMicrotasks = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** Creates the transports as the desktop renderer does, and opens the runtime socket. */
async function connect() {
  const transports = createDesktopTransports(BRIDGE);
  await flushMicrotasks(); // the endpoint resolves, then the socket is created
  const socket = FakeWebSocket.instances[0];
  socket.open();
  return { ...transports, socket };
}

beforeEach(() => {
  FakeWebSocket.instances = [];
  vi.stubGlobal('WebSocket', FakeWebSocket);
  vi.spyOn(console, 'log').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test('connects to the endpoint the preload hands over, with the token in the query', async () => {
  const { office, socket } = await connect();

  expect(socket.url).toBe(`ws://127.0.0.1:4567/ws?token=${TOKEN}`);
  office.dispose();
});

test('the runtime snapshot reaches the runtime state store, not the office', async () => {
  const { office, runtimeState, socket } = await connect();
  const received: ServerMessage[] = [];
  office.onMessage((message) => received.push(message));

  socket.receive(SNAPSHOT);
  await flushMicrotasks();

  expect(runtimeState.getState().snapshots).toBe(1);
  expect(runtimeState.getState().currentProjectId).toBe(SNAPSHOT.currentProjectId);
  expect(received).toEqual([]);
  office.dispose();
});

test('a snapshot sent before anything renders still reaches the store', async () => {
  const { office, runtimeState, socket } = await connect();

  socket.receive(SNAPSHOT);
  await flushMicrotasks();

  expect(runtimeState.getState().snapshots).toBe(1);
  office.dispose();
});

test('nothing the office sends reaches the runtime, webviewReady included', async () => {
  const { office, socket } = await connect();
  // The office UI sends through the MessageTransport interface (transport/index.ts).
  const asOffice: MessageTransport = office;

  asOffice.send({ type: 'webviewReady' });
  asOffice.send({ type: 'setSoundEnabled', enabled: false });

  expect(socket.sent).toEqual([]);
  office.dispose();
});

test('a runtime command goes out on the runtime socket as the contract frame', async () => {
  const { office, sendRuntimeCommand, socket } = await connect();

  sendRuntimeCommand({ type: 'openProject', path: 'C:\\work\\demo' });

  expect(socket.sent.map((data) => JSON.parse(data) as unknown)).toEqual([
    { type: 'openProject', path: 'C:\\work\\demo' },
  ]);
  office.dispose();
});

test('local frames reach the office subscriber that stays, after StrictMode churn', async () => {
  const { office } = await connect();
  office.deliver({ type: 'layoutLoaded', layout: null });

  const first: ServerMessage[] = [];
  office.onMessage((message) => first.push(message))();
  const second: ServerMessage[] = [];
  office.onMessage((message) => second.push(message));
  await flushMicrotasks();

  expect(first).toEqual([]);
  expect(second).toEqual([{ type: 'layoutLoaded', layout: null }]);
  office.dispose();
});

test('the office transport reports the runtime socket state', async () => {
  const { office, socket } = await connect();
  const states: string[] = [];
  office.onStateChange((state) => states.push(state));
  expect(office.state).toBe('connected');

  vi.useFakeTimers();
  socket.close();

  expect(office.state).toBe('reconnecting');
  expect(states).toEqual(['reconnecting']);
  office.dispose();
});
