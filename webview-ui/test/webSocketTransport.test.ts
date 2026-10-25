/**
 * WebSocketTransport: the inbound buffer that holds frames until the first
 * subscriber (desktop connect-time frames, renderer-local asset frames), and
 * the endpoint function the desktop shell resolves before every connect.
 * A fake WebSocket stands in for the browser's.
 */
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

import type { ServerMessage } from '../../core/src/messages.ts';
import { WebSocketTransport } from '../src/transport/webSocketTransport.ts';

class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.CONNECTING;
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

  // Test controls
  open(): void {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  receive(message: unknown): void {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
}

const frame = (n: number) => ({ type: 'layoutLoaded', layout: { n } }) as unknown as ServerMessage;
const flushMicrotasks = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  FakeWebSocket.instances = [];
  vi.stubGlobal('WebSocket', FakeWebSocket);
  vi.spyOn(console, 'log').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test('frames that arrive before anyone subscribes reach the first subscriber, in order', async () => {
  const transport = new WebSocketTransport('ws://127.0.0.1:1/ws');
  transport.connect();
  const ws = FakeWebSocket.instances[0];
  ws.open();
  ws.receive(frame(1));
  transport.deliver(frame(2));
  ws.receive(frame(3));

  const received: ServerMessage[] = [];
  transport.onMessage((message) => received.push(message));
  await flushMicrotasks();

  expect(received).toEqual([frame(1), frame(2), frame(3)]);
  transport.dispose();
});

test('held frames go to the subscriber that stays after a StrictMode subscribe/unsubscribe/subscribe', async () => {
  const transport = new WebSocketTransport('ws://127.0.0.1:1/ws');
  transport.deliver(frame(1));

  const first: ServerMessage[] = [];
  const unsubscribe = transport.onMessage((message) => first.push(message));
  unsubscribe();
  const second: ServerMessage[] = [];
  transport.onMessage((message) => second.push(message));
  await flushMicrotasks();

  expect(first).toEqual([]);
  expect(second).toEqual([frame(1)]);
  transport.dispose();
});

test('with a subscriber, frames are delivered straight away (standalone behaviour)', () => {
  const transport = new WebSocketTransport('ws://127.0.0.1:1/ws');
  transport.connect();
  const ws = FakeWebSocket.instances[0];
  const received: ServerMessage[] = [];
  transport.onMessage((message) => received.push(message));
  ws.open();

  ws.receive(frame(1));
  ws.onmessage?.({ data: 'not json' });

  expect(received).toEqual([frame(1)]);
  transport.dispose();
});

test('an endpoint function is resolved before every connect, so a reconnect picks up a new URL', async () => {
  vi.useFakeTimers();
  const urls = ['ws://127.0.0.1:1111/ws?token=a', 'ws://127.0.0.1:2222/ws?token=b'];
  const endpoint = vi.fn(async () => urls[endpoint.mock.calls.length - 1]);
  const transport = new WebSocketTransport(endpoint);
  const states: string[] = [];
  transport.onStateChange((state) => states.push(state));

  transport.connect();
  expect(FakeWebSocket.instances).toHaveLength(0);
  await vi.runAllTicks();
  await Promise.resolve();
  expect(FakeWebSocket.instances.map((ws) => ws.url)).toEqual([urls[0]]);

  transport.send({ type: 'webviewReady' });
  FakeWebSocket.instances[0].open();
  expect(FakeWebSocket.instances[0].sent).toEqual([JSON.stringify({ type: 'webviewReady' })]);

  FakeWebSocket.instances[0].close();
  await vi.advanceTimersByTimeAsync(1_000);
  expect(endpoint).toHaveBeenCalledTimes(2);
  expect(FakeWebSocket.instances.map((ws) => ws.url)).toEqual(urls);
  expect(states).toEqual(['connected', 'reconnecting']);
  transport.dispose();
});

test('an endpoint that cannot be resolved is retried with backoff', async () => {
  vi.useFakeTimers();
  let calls = 0;
  const transport = new WebSocketTransport(async () => {
    calls++;
    if (calls === 1) throw new Error('runtime not ready');
    return 'ws://127.0.0.1:3333/ws';
  });

  transport.connect();
  await vi.advanceTimersByTimeAsync(0);
  expect(transport.state).toBe('reconnecting');
  expect(FakeWebSocket.instances).toHaveLength(0);

  await vi.advanceTimersByTimeAsync(1_000);
  expect(FakeWebSocket.instances.map((ws) => ws.url)).toEqual(['ws://127.0.0.1:3333/ws']);
  transport.dispose();
});
