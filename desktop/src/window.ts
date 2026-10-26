import { BrowserWindow, type Session, type WebContents } from 'electron';
import * as path from 'path';

import {
  ALLOWED_PERMISSIONS,
  APP_ENTRY_URL,
  APP_TITLE,
  DEV_SERVER_MAX_RETRIES,
  DEV_SERVER_RETRY_MS,
  PRELOAD_FILE,
  WINDOW_BACKGROUND,
  WINDOW_HEIGHT,
  WINDOW_MIN_HEIGHT,
  WINDOW_MIN_WIDTH,
  WINDOW_WIDTH,
} from './constants.js';
import { isAllowedOrigin, isAllowedRendererUrl } from './rendererOrigin.js';

/**
 * Applied to every WebContents the app creates: no new windows, no webviews,
 * and no navigation or redirect away from the renderer's own origin.
 */
export function hardenWebContents(contents: WebContents, allowedOrigins: readonly string[]): void {
  contents.setWindowOpenHandler(() => ({ action: 'deny' }));
  contents.on('will-navigate', (event) => {
    if (!isAllowedRendererUrl(event.url, allowedOrigins)) event.preventDefault();
  });
  contents.on('will-redirect', (event) => {
    if (!isAllowedRendererUrl(event.url, allowedOrigins)) event.preventDefault();
  });
  contents.on('will-attach-webview', (event) => event.preventDefault());
}

/** Denies every permission request and check, except loopback access for the renderer's origin. */
export function restrictPermissions(ses: Session, allowedOrigins: readonly string[]): void {
  ses.setPermissionRequestHandler((_contents, permission, callback, details) => {
    callback(
      ALLOWED_PERMISSIONS.includes(permission) &&
        isAllowedRendererUrl(details.requestingUrl, allowedOrigins),
    );
  });
  ses.setPermissionCheckHandler(
    (_contents, permission, requestingOrigin) =>
      ALLOWED_PERMISSIONS.includes(permission) && isAllowedOrigin(requestingOrigin, allowedOrigins),
  );
}

/** The single app window. `contextIsolation` and `sandbox` stay on (ADR 0001 decision 6). */
export function createMainWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
    minWidth: WINDOW_MIN_WIDTH,
    minHeight: WINDOW_MIN_HEIGHT,
    title: APP_TITLE,
    backgroundColor: WINDOW_BACKGROUND,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, PRELOAD_FILE),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      spellcheck: false,
    },
  });
  // Keep the app's name in the title bar instead of the page's <title>.
  window.on('page-title-updated', (event) => event.preventDefault());
  return window;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Loads the built renderer from `app://bundle`, or the Vite dev server in
 * `desktop:dev`, retrying until it answers (it may still be starting).
 */
export async function loadRenderer(window: BrowserWindow, devRendererUrl: string | null) {
  if (devRendererUrl === null) {
    await window.loadURL(APP_ENTRY_URL);
    return;
  }
  for (let attempt = 1; ; attempt++) {
    if (window.isDestroyed()) return;
    try {
      await window.loadURL(devRendererUrl);
      return;
    } catch (err) {
      if (attempt >= DEV_SERVER_MAX_RETRIES) throw err;
      await delay(DEV_SERVER_RETRY_MS);
    }
  }
}
