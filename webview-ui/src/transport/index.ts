import type { RuntimeClientMessage, ServerMessage } from '../../../core/src/messages.js';
import { getDesktopBridge } from '../desktopBridge.js';
import type { LocalAssetMessage } from '../localAssets.js';
import { isBrowserRuntime } from '../runtime.js';
import type { RuntimeStateStore } from '../runtimeState.js';
import { createDesktopTransports } from './desktopTransport.js';
import { PostMessageTransport } from './postMessageTransport.js';
import type { MessageTransport } from './types.js';
import { WebSocketTransport } from './webSocketTransport.js';

function createTransport(): MessageTransport {
  if (!isBrowserRuntime) {
    return new PostMessageTransport();
  }
  // Standalone browser: connect via WebSocket to the same host serving the SPA.
  // The server token rides the handshake query when this page was opened from
  // the tokened URL the CLI printed — that is what makes the session privileged
  // enough to approve a hook install (server/src/httpServer.ts). Without it the
  // socket still connects and the office still renders; only the hooks toggle
  // is refused. WebSocketTransport captures the url once, so the token survives
  // reconnects even if the address bar is later cleared.
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = new URLSearchParams(window.location.search).get('token');
  const wsUrl = `${protocol}//${window.location.host}/ws${
    token ? `?token=${encodeURIComponent(token)}` : ''
  }`;
  const ws = new WebSocketTransport(wsUrl);
  ws.connect();
  // Vite dev only: there is no server to connect to, so `browserMock` injects
  // ServerMessages as `window` 'message' events. Bridge them into the transport
  // (the WebSocket never opens against the dev server). Guarded by DEV so it's
  // tree-shaken out of the production standalone build.
  if (import.meta.env.DEV) {
    window.addEventListener('message', (e: MessageEvent) => {
      const data = e.data as unknown;
      if (
        data &&
        typeof data === 'object' &&
        typeof (data as { type?: unknown }).type === 'string'
      ) {
        ws.deliver(data as ServerMessage);
      }
    });
  }
  return ws;
}

// The desktop branch comes first: the desktop page is not served by the
// runtime, so the browser branch's `location.host` URL would be wrong
// (app://bundle). It is the only branch with two transports: the office's,
// and the runtime socket behind it (desktopTransport.ts).
const desktopBridge = getDesktopBridge();
const desktop = desktopBridge ? createDesktopTransports(desktopBridge) : null;

/** Singleton transport instance. Import this everywhere instead of vscodeApi. */
export const transport: MessageTransport = desktop?.office ?? createTransport();

/**
 * Desktop shell only (`null` elsewhere): the latest snapshot, project, agent
 * list and command results the local runtime sent (runtimeState.ts).
 */
export const runtimeStateStore: RuntimeStateStore | null = desktop?.runtimeState ?? null;

/**
 * Desktop shell only: sends a command (ADR 0005 decision 2) to the local
 * runtime over the runtime socket. Returns `false`, sending nothing, in the
 * other surfaces. The answer comes back as frames in `runtimeStateStore`.
 */
export function sendRuntimeCommand(command: RuntimeClientMessage): boolean {
  if (!desktop) return false;
  desktop.sendRuntimeCommand(command);
  return true;
}

/**
 * Deliver a frame that originates in the renderer itself (the desktop
 * renderer's bundled assets, localAssets.ts) to the office's subscribers.
 * Held until the first subscriber, like a socket frame. No-op outside the
 * desktop shell, where the extension or the server sends every asset.
 * The office reads these frames as the upstream variants of the same names,
 * so a `LocalAssetMessage` that drifts from them does not compile here.
 */
export function deliverLocal(message: LocalAssetMessage): void {
  desktop?.office.deliver(message);
}

export type { MessageTransport } from './types.js';
