/**
 * Browser runtime mock — injects the same messages the extension or the
 * standalone server would send, for Vite dev mode only (UI prototyping without
 * a server).
 *
 * The assets come from `localAssets.ts` (pre-decoded JSON from the Vite dev
 * middleware, PNG decode in the page as a fallback). The desktop renderer uses
 * that loader directly; this module only adds the window-message delivery and
 * a fake settings frame.
 *
 * Only imported in browser runtime; tree-shaken from VS Code webview runtime.
 */

import { loadLocalAssets, type LocalAssetMessage } from './localAssets.js';

// ── Module-level state ─────────────────────────────────────────────────────────

let mockMessages: LocalAssetMessage[] | null = null;

// ── Public API ─────────────────────────────────────────────────────────────────

/**
 * Call before createRoot() in main.tsx.
 * Fetches all pre-decoded assets from the Vite dev server and stores them
 * for dispatchMockMessages().
 */
export async function initBrowserMock(): Promise<void> {
  console.log('[BrowserMock] Loading assets...');
  mockMessages = await loadLocalAssets();
}

/**
 * Call inside a useEffect in App.tsx -- after the window message listener
 * in useExtensionMessages has been registered.
 *
 * Only used in Vite dev mode (npm run dev). In standalone server mode and
 * VS Code mode, the server/extension sends all state over the transport.
 */
export function dispatchMockMessages(): void {
  if (!mockMessages) return;

  function dispatch(data: unknown): void {
    window.dispatchEvent(new MessageEvent('message', { data }));
  }

  // localAssets returns the frames in the office's load order.
  for (const message of mockMessages) dispatch(message);
  dispatch({
    type: 'settingsLoaded',
    soundEnabled: false,
    extensionVersion: '1.3.0',
    lastSeenVersion: '1.2',
  });

  console.log('[BrowserMock] Messages dispatched');
}
