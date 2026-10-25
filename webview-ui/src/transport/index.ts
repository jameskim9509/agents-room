import type { ServerMessage } from '../../../core/src/messages.js';
import { type AgentsRoomBridge, getDesktopBridge } from '../desktopBridge.js';
import { isBrowserRuntime } from '../runtime.js';
import { PostMessageTransport } from './postMessageTransport.js';
import type { MessageTransport } from './types.js';
import { WebSocketTransport } from './webSocketTransport.js';

/** Desktop only: hands renderer-local frames to the same handlers as wire frames. */
let localDelivery: ((message: ServerMessage) => void) | null = null;

/**
 * Desktop shell (ADR 0001 decision 4): the renderer talks to the local runtime
 * directly over its own WebSocket, so agent events never pass through the
 * Electron main process (SCRUM-22). The preload only hands over the endpoint,
 * and only after the runtime's ready line, so it is resolved asynchronously,
 * and again before every reconnect: a restarted runtime has a new port and
 * token. The token rides the `?token=` query, as the runtime's `/ws` gate
 * requires it on the handshake itself.
 *
 * TODO(contract): frames on this socket are still typed as the upstream
 * ServerMessage/ClientMessage unions, and the renderer still sends the
 * upstream `webviewReady` on connect. When the renderer↔runtime contract
 * (core/asyncapi.yaml, feat/contract-sprint0) lands, type this transport with
 * the generated frames; the runtime sends its snapshot on every connect
 * (critique D4), which the inbound buffer already holds for the first subscriber.
 */
function createDesktopTransport(bridge: AgentsRoomBridge): WebSocketTransport {
  const ws = new WebSocketTransport(async () => {
    const { url, token } = await bridge.getRuntimeEndpoint();
    const wsUrl = new URL(url);
    wsUrl.searchParams.set('token', token);
    return wsUrl.toString();
  });
  ws.connect();
  return ws;
}

function createTransport(): MessageTransport {
  // Before the browser fallback: the desktop page is not served by the runtime,
  // so the browser branch's `location.host` URL would be wrong (app://bundle).
  const bridge = getDesktopBridge();
  if (bridge) {
    const ws = createDesktopTransport(bridge);
    localDelivery = (message) => ws.deliver(message);
    return ws;
  }
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

/** Singleton transport instance. Import this everywhere instead of vscodeApi. */
export const transport: MessageTransport = createTransport();

/**
 * Deliver a frame that originates in the renderer itself (the desktop
 * renderer's bundled assets, localAssets.ts) to the transport's subscribers.
 * Held until the first subscriber, like a socket frame. No-op outside the
 * desktop shell, where the extension or the server sends every asset.
 */
export function deliverLocal(message: ServerMessage): void {
  localDelivery?.(message);
}

export type { MessageTransport } from './types.js';
