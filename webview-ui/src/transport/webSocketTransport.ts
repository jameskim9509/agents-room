import {
  TRANSPORT_STATE_CONNECTED,
  TRANSPORT_STATE_CONNECTING,
  TRANSPORT_STATE_DISCONNECTED,
  TRANSPORT_STATE_RECONNECTING,
} from '../../../core/src/constants.js';
import type { ClientMessage, ServerMessage } from '../../../core/src/messages.js';
import { HeldMessages } from './heldMessages.js';
import type { MessageTransport, TransportState } from './types.js';

/**
 * Where to connect. A string is used as-is for every attempt (standalone: the
 * URL is fixed for the page's lifetime). A function is asked again before
 * every connect and reconnect, so an endpoint that is not known yet (desktop:
 * the runtime has not printed its ready line) or that changed (a restarted
 * runtime has a new port and token) is picked up without reloading the page.
 */
export type WebSocketEndpoint = string | (() => Promise<string>);

/**
 * WebSocket transport for standalone browser mode and the desktop shell.
 * Includes automatic reconnection with exponential backoff and message queuing.
 *
 * `In` and `Out` are the frames it receives and sends: the whole protocol in
 * standalone, the agents-room frames on the desktop runtime socket. Frames are
 * parsed, not validated: the type says what the peer is contracted to send.
 *
 * Inbound frames that arrive while nobody is subscribed are held, in order,
 * and handed to the first subscriber instead of being dropped (HeldMessages),
 * so the desktop runtime's connect-time snapshot survives the gap before
 * React mounts.
 */
export class WebSocketTransport<
  In = ServerMessage,
  Out = ClientMessage,
> implements MessageTransport<In, Out> {
  private ws: WebSocket | null = null;
  private stateHandlers: Array<(state: TransportState) => void> = [];
  private readonly endpoint: WebSocketEndpoint;
  /** Bumped on every connect() so a late endpoint resolution can't open a stale socket. */
  private connectSeq = 0;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;
  private pendingMessages: Out[] = [];
  private readonly inbound = new HeldMessages<In>();
  private _state: TransportState = TRANSPORT_STATE_CONNECTING;
  private readonly _ready: Promise<void>;
  private resolveReady!: () => void;

  constructor(endpoint: WebSocketEndpoint) {
    this.endpoint = endpoint;
    this._ready = new Promise<void>((resolve) => {
      this.resolveReady = resolve;
    });
  }

  get state(): TransportState {
    return this._state;
  }

  get ready(): Promise<void> {
    return this._ready;
  }

  private setState(next: TransportState): void {
    if (this._state === next) return;
    this._state = next;
    for (const handler of this.stateHandlers) handler(next);
  }

  connect(): void {
    if (this.disposed) return;

    // First attempt is "connecting"; any retry after a drop is "reconnecting".
    this.setState(
      this.reconnectAttempts > 0 ? TRANSPORT_STATE_RECONNECTING : TRANSPORT_STATE_CONNECTING,
    );

    const seq = ++this.connectSeq;
    if (typeof this.endpoint === 'string') {
      this.open(this.endpoint);
      return;
    }
    this.endpoint().then(
      (url) => {
        if (this.disposed || seq !== this.connectSeq) return;
        this.open(url);
      },
      (err: unknown) => {
        if (this.disposed || seq !== this.connectSeq) return;
        console.warn('[Transport] Could not resolve the WebSocket endpoint', err);
        this.setState(TRANSPORT_STATE_RECONNECTING);
        this.scheduleReconnect();
      },
    );
  }

  private open(url: string): void {
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectAttempts = 0;
      console.log('[Transport] WebSocket connected');
      this.setState(TRANSPORT_STATE_CONNECTED);
      this.resolveReady();
      // Flush any messages queued while connecting
      for (const msg of this.pendingMessages) {
        this.ws!.send(JSON.stringify(msg));
      }
      this.pendingMessages = [];
    };

    this.ws.onmessage = (e: MessageEvent) => {
      let msg: In;
      try {
        msg = JSON.parse(e.data as string) as In;
      } catch {
        return; // Malformed JSON, ignore
      }
      this.inbound.dispatch(msg);
    };

    this.ws.onclose = () => {
      if (!this.disposed) {
        this.setState(TRANSPORT_STATE_RECONNECTING);
        this.scheduleReconnect();
      }
    };

    this.ws.onerror = () => {
      // onclose will fire after onerror, triggering reconnect
    };
  }

  send(message: Out): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message));
    } else {
      // Queue messages while connecting (flushed in onopen)
      this.pendingMessages.push(message);
    }
  }

  onMessage(handler: (message: In) => void): () => void {
    return this.inbound.subscribe(handler);
  }

  /**
   * Deliver an externally-sourced message to all handlers, as if it had arrived
   * over the socket. Used in Vite dev mode to bridge `browserMock`'s injected
   * `window` messages. Held like a socket frame while nobody subscribes.
   */
  deliver(message: In): void {
    this.inbound.dispatch(message);
  }

  onStateChange(handler: (state: TransportState) => void): () => void {
    this.stateHandlers.push(handler);
    return () => {
      this.stateHandlers = this.stateHandlers.filter((h) => h !== handler);
    };
  }

  dispose(): void {
    this.disposed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.inbound.clear();
    this.pendingMessages = [];
    this.setState(TRANSPORT_STATE_DISCONNECTED);
    this.stateHandlers = [];
  }

  private scheduleReconnect(): void {
    // Exponential backoff: 1s, 2s, 4s, 8s, max 30s
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 30000);
    this.reconnectAttempts++;
    console.log(
      `[Transport] WebSocket reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`,
    );
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }
}
