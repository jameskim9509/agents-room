/**
 * The desktop shell's preload API (ADR 0001 decision 6).
 *
 * The Electron preload exposes exactly this object as `window.agentsRoom`
 * through `contextBridge`. The renderer detects the desktop runtime by its
 * presence. Agent events never pass through it: the renderer talks to the
 * local runtime over its own WebSocket (ADR 0001 decision 4, SCRUM-22).
 */

/** Where the local runtime's WebSocket listens. The token is not part of `url`. */
export interface RuntimeEndpoint {
  /** `ws://127.0.0.1:<port>/ws` */
  url: string;
  /** Per-launch token. The renderer sends it as the `?token=` query on connect. */
  token: string;
}

export interface AgentsRoomBridge {
  /**
   * Resolves once the runtime has printed its ready line. A restarted runtime
   * has a new port and token, so callers ask again before every (re)connect.
   */
  getRuntimeEndpoint(): Promise<RuntimeEndpoint>;
  /** Opens the OS folder picker (SCRUM-18). `null` when the user cancels. */
  pickProjectFolder(): Promise<string | null>;
}
