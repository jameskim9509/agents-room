/**
 * Runtime detection, provider-agnostic
 *
 * Single source of truth for determining whether the webview is running
 * inside an IDE extension (VS Code, Cursor, Windsurf, etc.), inside the
 * agents-room desktop shell (Electron, `app://bundle`), or standalone in a
 * browser.
 */

import { getDesktopBridge } from './desktopBridge.js';

declare function acquireVsCodeApi(): unknown;

type Runtime = 'vscode' | 'desktop' | 'browser';
// Future: 'cursor' | 'windsurf' | etc.

const runtime: Runtime =
  typeof acquireVsCodeApi !== 'undefined'
    ? 'vscode'
    : getDesktopBridge() !== null
      ? 'desktop'
      : 'browser';

export const isVsCodeRuntime = runtime === 'vscode';
/** The desktop shell's preload exposed `window.agentsRoom` (ADR 0001 decision 6). */
export const isDesktopRuntime = runtime === 'desktop';
export const isBrowserRuntime = runtime === 'browser';

/**
 * True only under the Playwright e2e harness, which sets `__PIXEL_AGENTS_E2E`
 * via `addInitScript` before any app code runs (so it's set in every frame,
 * including the VS Code webview iframe). Gates test-only diagnostics
 * (window.__pixelAgentsTestHooks message/sound logs, the addAgent wrapper) so
 * they never run, and never grow unbounded, in a real user's session.
 */
export const isE2E: boolean =
  typeof window !== 'undefined' &&
  (window as unknown as { __PIXEL_AGENTS_E2E?: boolean }).__PIXEL_AGENTS_E2E === true;
