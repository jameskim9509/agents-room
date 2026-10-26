import {
  TRANSPORT_STATE_CONNECTED,
  TRANSPORT_STATE_CONNECTING,
  TRANSPORT_STATE_DISCONNECTED,
  TRANSPORT_STATE_RECONNECTING,
} from './constants.js';
import type { ClientMessage, ServerMessage } from './messages.js';

/** Connection lifecycle states reported by a MessageTransport. */
export type TransportState =
  | typeof TRANSPORT_STATE_CONNECTING
  | typeof TRANSPORT_STATE_CONNECTED
  | typeof TRANSPORT_STATE_RECONNECTING
  | typeof TRANSPORT_STATE_DISCONNECTED;

/**
 * Transport-agnostic message layer between webview and extension/server.
 *
 * `In` is what arrives, `Out` what may be sent. They default to the whole
 * protocol (`ServerMessage`, `ClientMessage`); the agents-room desktop
 * renderer types its local-runtime socket with the agents-room halves
 * (`RuntimeServerMessage`, `RuntimeClientMessage`).
 *
 * Implementations:
 * - PostMessageTransport: VS Code webview (acquireVsCodeApi); permanently `connected`.
 * - WebSocketTransport: standalone browser and the desktop shell; reports
 *   connecting/reconnecting/disconnected as the socket comes and goes.
 */
export interface MessageTransport<In = ServerMessage, Out = ClientMessage> {
  /** Send a message to the extension/server. */
  send(message: Out): void;
  /** Subscribe to messages from the extension/server. Returns unsubscribe function. */
  onMessage(handler: (message: In) => void): () => void;
  /** Resolves once the transport has connected for the first time. */
  readonly ready: Promise<void>;
  /** Current connection state (valid from construction). */
  readonly state: TransportState;
  /** Subscribe to connection-state changes. Returns unsubscribe function. */
  onStateChange(handler: (state: TransportState) => void): () => void;
  /** Clean up resources (WebSocket close, etc.). */
  dispose(): void;
}
