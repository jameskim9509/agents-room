/**
 * The desktop shell's preload API, as the renderer sees it.
 *
 * The Electron preload (`desktop/src/preload.ts`) exposes `window.agentsRoom`
 * through `contextBridge` before any page script runs. Its presence is what
 * makes the runtime `'desktop'` (runtime.ts). Nothing else from the shell is
 * reachable from the page.
 */

import type { AgentsRoomBridge } from '../../core/src/desktopBridge.js';

declare global {
  interface Window {
    agentsRoom?: AgentsRoomBridge;
  }
}

/** The preload API, or `null` outside the desktop shell. */
export function getDesktopBridge(): AgentsRoomBridge | null {
  return typeof window !== 'undefined' && window.agentsRoom ? window.agentsRoom : null;
}

export type { AgentsRoomBridge } from '../../core/src/desktopBridge.js';
