import type { AgentsRoomBridge } from '../../../core/src/desktopBridge.js';
import type {
  RuntimeClientMessage,
  RuntimeServerMessage,
  ServerMessage,
} from '../../../core/src/messages.js';
import { createRuntimeStateStore, type RuntimeStateStore } from '../runtimeState.js';
import { HeldMessages } from './heldMessages.js';
import type { MessageTransport, TransportState } from './types.js';
import { WebSocketTransport } from './webSocketTransport.js';

/** The desktop renderer's socket to the local runtime: agents-room frames only (ADR 0005). */
type RuntimeTransport = MessageTransport<RuntimeServerMessage, RuntimeClientMessage>;

/**
 * What the inherited office UI (`useExtensionMessages`, Settings, the canvas)
 * talks to in the desktop shell. That UI speaks the upstream protocol, which
 * the runtime does not take, so:
 * - inbound: only the renderer's own asset frames (`deliver`), held until the
 *   office subscribes. Runtime frames go to the runtime state store instead.
 * - outbound: nothing. Every message the office sends is an upstream command
 *   the runtime would reject as `INVALID_MESSAGE`. That includes
 *   `webviewReady`: the runtime sends its snapshot on every connect unasked.
 * - state: the runtime socket's, so the connection badge reports it.
 */
class DesktopOfficeTransport implements MessageTransport {
  private readonly local = new HeldMessages<ServerMessage>();
  private readonly runtime: RuntimeTransport;

  constructor(runtime: RuntimeTransport) {
    this.runtime = runtime;
  }

  get state(): TransportState {
    return this.runtime.state;
  }

  get ready(): Promise<void> {
    return this.runtime.ready;
  }

  send(): void {
    // Upstream commands have no runtime counterpart; see the class comment.
  }

  onMessage(handler: (message: ServerMessage) => void): () => void {
    return this.local.subscribe(handler);
  }

  onStateChange(handler: (state: TransportState) => void): () => void {
    return this.runtime.onStateChange(handler);
  }

  /** A frame the renderer made itself, handed to the office like a received one. */
  deliver(message: ServerMessage): void {
    this.local.dispatch(message);
  }

  dispose(): void {
    this.local.clear();
    this.runtime.dispose();
  }
}

export interface DesktopTransports {
  office: DesktopOfficeTransport;
  runtimeState: RuntimeStateStore;
  /** Sends a command to the runtime; queued while the socket is not open. */
  sendRuntimeCommand: (command: RuntimeClientMessage) => void;
}

/**
 * Desktop shell (ADR 0001 decision 4): the renderer talks to the local runtime
 * directly over its own WebSocket, so agent events never pass through the
 * Electron main process (SCRUM-22). The preload only hands over the endpoint,
 * and only after the runtime's ready line, so it is resolved asynchronously,
 * and again before every reconnect: a restarted runtime has a new port and
 * token. The token rides the `?token=` query, as the runtime's `/ws` gate
 * requires it on the handshake itself.
 *
 * The runtime state store subscribes before the socket opens, so it receives
 * the connect-time snapshot first.
 */
export function createDesktopTransports(bridge: AgentsRoomBridge): DesktopTransports {
  const runtime = new WebSocketTransport<RuntimeServerMessage, RuntimeClientMessage>(async () => {
    const { url, token } = await bridge.getRuntimeEndpoint();
    const wsUrl = new URL(url);
    wsUrl.searchParams.set('token', token);
    return wsUrl.toString();
  });
  const runtimeState = createRuntimeStateStore(runtime);
  runtime.connect();
  return {
    office: new DesktopOfficeTransport(runtime),
    runtimeState,
    sendRuntimeCommand: (command) => runtime.send(command),
  };
}
