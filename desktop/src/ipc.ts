import {
  BrowserWindow,
  dialog,
  ipcMain,
  type IpcMainInvokeEvent,
  type OpenDialogOptions,
} from 'electron';

import { IPC_GET_RUNTIME_ENDPOINT, IPC_PICK_PROJECT_FOLDER } from './constants.js';
import { isAllowedOrigin } from './rendererOrigin.js';
import type { RuntimeSupervisor } from './runtimeSupervisor.js';

export interface IpcDependencies {
  supervisor: RuntimeSupervisor;
  /** Renderer origins whose top frame may call the bridge. */
  trustedOrigins: readonly string[];
  getMainWindow: () => BrowserWindow | null;
}

/**
 * Only the main window's top frame, on the renderer's own origin, may call
 * the bridge (Electron security checklist: validate the sender of every IPC
 * message). The frame's serialized origin is checked, as Chromium reports it.
 */
function assertTrustedSender(event: IpcMainInvokeEvent, deps: IpcDependencies): void {
  const frame = event.senderFrame;
  const mainWindow = deps.getMainWindow();
  const trusted =
    frame !== null &&
    frame.parent === null &&
    mainWindow !== null &&
    !mainWindow.isDestroyed() &&
    event.sender === mainWindow.webContents &&
    isAllowedOrigin(frame.origin, deps.trustedOrigins);
  if (!trusted) throw new Error('forbidden sender');
}

/**
 * The two channels behind `window.agentsRoom` (preload.ts). Nothing else is
 * reachable over IPC. Agent events never pass through here: the renderer
 * talks to the runtime over its own WebSocket (ADR 0001 decision 4).
 */
export function registerIpcHandlers(deps: IpcDependencies): void {
  ipcMain.handle(IPC_GET_RUNTIME_ENDPOINT, (event) => {
    assertTrustedSender(event, deps);
    return deps.supervisor.waitForEndpoint();
  });

  ipcMain.handle(IPC_PICK_PROJECT_FOLDER, async (event): Promise<string | null> => {
    assertTrustedSender(event, deps);
    const options: OpenDialogOptions = { properties: ['openDirectory'] };
    // Called through the module object, so e2e can stub dialog.showOpenDialog.
    const owner = BrowserWindow.fromWebContents(event.sender);
    const result = owner
      ? await dialog.showOpenDialog(owner, options)
      : await dialog.showOpenDialog(options);
    // Cancel keeps the screen as it was (SCRUM-18): the renderer gets null.
    if (result.canceled) return null;
    return result.filePaths[0] ?? null;
  });
}
