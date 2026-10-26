/**
 * agents-room desktop shell: the Electron main process (ADR 0001).
 *
 * It owns the window, the OS folder dialog and the app lifecycle, serves the
 * built renderer at `app://bundle`, and supervises the local runtime. It never
 * relays agent events: the renderer talks to the runtime over its own
 * WebSocket (decision 4), and the preload hands over only the endpoint.
 */
import { app, type BrowserWindow, dialog, session } from 'electron';
import * as fs from 'fs';
import * as path from 'path';

import { handleAppProtocol, registerAppScheme } from './appProtocol.js';
import {
  APP_INDEX_FILE,
  APP_ORIGIN,
  CONSOLE_PREFIX,
  DEV_RENDERER_ORIGIN,
  DEV_RUNTIME_DESCRIPTOR,
  ENV_RENDERER_URL,
  ENV_RUNTIME_COMMAND,
  ENV_RUNTIME_READY_TIMEOUT_MS,
  ENV_USER_DATA_DIR,
  LOG_DIR_NAME,
  RENDERER_DIR_FROM_MAIN,
  REPO_ROOT_FROM_MAIN,
  RUNTIME_READY_TIMEOUT_MS,
} from './constants.js';
import { readDevOverrides } from './devOverrides.js';
import { registerIpcHandlers } from './ipc.js';
import { urlOrigin } from './rendererOrigin.js';
import { resolveRuntimeCommand } from './runtimeCommand.js';
import {
  failureDialogOptions,
  RESTART_BUTTON_INDEX,
  type RuntimeFailure,
} from './runtimeFailure.js';
import { RuntimeLog } from './runtimeLog.js';
import { RuntimeSupervisor } from './runtimeSupervisor.js';
import {
  createMainWindow,
  hardenWebContents,
  loadRenderer,
  restrictPermissions,
} from './window.js';

// Test and development knobs: a packaged app honours none of them (ADR 0001 decision 5).
const devOverrides = readDevOverrides(process.env, app.isPackaged);
for (const name of devOverrides.ignored) {
  console.warn(`${CONSOLE_PREFIX} ${name} is ignored in a packaged app`);
}

/** `desktop:dev` only: the Vite dev server URL, honoured unpackaged and on the dev origin only. */
function resolveDevRendererUrl(): string | null {
  const raw = devOverrides.values[ENV_RENDERER_URL];
  if (!raw) return null;
  if (urlOrigin(raw) !== DEV_RENDERER_ORIGIN) {
    console.warn(`${CONSOLE_PREFIX} ${ENV_RENDERER_URL} must be on ${DEV_RENDERER_ORIGIN}`);
    return null;
  }
  return raw;
}

function resolveReadyTimeoutMs(): number {
  const raw = Number(devOverrides.values[ENV_RUNTIME_READY_TIMEOUT_MS]);
  return Number.isInteger(raw) && raw > 0 ? raw : RUNTIME_READY_TIMEOUT_MS;
}

const devRendererUrl = resolveDevRendererUrl();
/** Origins the renderer may run on. Also the runtime's `/ws` Origin allowlist. */
const rendererOrigins: readonly string[] = devRendererUrl
  ? [APP_ORIGIN, DEV_RENDERER_ORIGIN]
  : [APP_ORIGIN];
const rendererDir = path.resolve(__dirname, ...RENDERER_DIR_FROM_MAIN);

let mainWindow: BrowserWindow | null = null;
let supervisor: RuntimeSupervisor | null = null;
let runtimeLog: RuntimeLog | null = null;
let shutdown: 'running' | 'stopping' | 'done' = 'running';

// Tests isolate the profile. Must precede the single-instance lock, which is per userData dir.
const userDataOverride = devOverrides.values[ENV_USER_DATA_DIR];
if (userDataOverride) app.setPath('userData', path.resolve(userDataOverride));

// Must run before `ready`.
registerAppScheme();

async function onRuntimeFailure(failure: RuntimeFailure): Promise<void> {
  if (shutdown !== 'running' || !runtimeLog) return;
  const options = failureDialogOptions(failure, runtimeLog.path);
  const owner = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
  // Called through the module object, so e2e can stub dialog.showMessageBox.
  const { response } = owner
    ? await dialog.showMessageBox(owner, options)
    : await dialog.showMessageBox(options);
  if (shutdown !== 'running') return;
  if (response === RESTART_BUTTON_INDEX) supervisor?.start();
  else app.quit();
}

async function onReady(): Promise<void> {
  restrictPermissions(session.defaultSession, rendererOrigins);
  handleAppProtocol(rendererDir);
  if (!devRendererUrl && !fs.existsSync(path.join(rendererDir, APP_INDEX_FILE))) {
    console.error(`${CONSOLE_PREFIX} ${rendererDir} has no build. Run npm run build:webview.`);
  }

  const userData = app.getPath('userData');
  const log = new RuntimeLog(path.join(userData, LOG_DIR_NAME));
  runtimeLog = log;
  const descriptorPath = app.isPackaged
    ? null
    : path.resolve(__dirname, ...REPO_ROOT_FROM_MAIN, ...DEV_RUNTIME_DESCRIPTOR);
  const runtime = new RuntimeSupervisor({
    log,
    dataDir: userData,
    allowedOrigins: rendererOrigins,
    readyTimeoutMs: resolveReadyTimeoutMs(),
    resolveCommand: () =>
      resolveRuntimeCommand(devOverrides.values[ENV_RUNTIME_COMMAND], descriptorPath),
    baseEnv: process.env,
    onFailure: (failure) => void onRuntimeFailure(failure),
  });
  supervisor = runtime;
  registerIpcHandlers({
    supervisor: runtime,
    trustedOrigins: rendererOrigins,
    getMainWindow: () => mainWindow,
  });

  // The window exists before the runtime starts: a start that fails at once
  // (no launch command) must still get a dialog that is modal to the window.
  const window = createMainWindow();
  mainWindow = window;
  window.on('closed', () => {
    mainWindow = null;
  });

  // The runtime boots while the window loads; the renderer asks for the
  // endpoint and waits for the ready line on its own.
  runtime.start();
  await loadRenderer(window, devRendererUrl);
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });

  app.on('web-contents-created', (_event, contents) => {
    hardenWebContents(contents, rendererOrigins);
  });

  app.on('window-all-closed', () => app.quit());

  // Stop the runtime before quitting: stdin EOF first, then a tree kill.
  app.on('before-quit', (event) => {
    if (shutdown === 'done' || !supervisor) return;
    event.preventDefault();
    if (shutdown === 'stopping') return;
    shutdown = 'stopping';
    void supervisor
      .stop()
      .catch((err: unknown) => console.error(`${CONSOLE_PREFIX} stopping the runtime failed`, err))
      .then(() => runtimeLog?.close())
      .finally(() => {
        shutdown = 'done';
        app.quit();
      });
  });

  app
    .whenReady()
    .then(onReady)
    .catch((err: unknown) => console.error(`${CONSOLE_PREFIX} startup failed`, err));
}
