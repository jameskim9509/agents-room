/**
 * The preload: exposes `window.agentsRoom` and nothing else (ADR 0001
 * decision 6). No raw `ipcRenderer`, no Node API. Sandboxed, so it is bundled
 * into one CommonJS file that only requires `electron`.
 */
import { contextBridge, ipcRenderer } from 'electron';

import type { AgentsRoomBridge, RuntimeEndpoint } from '../../core/src/desktopBridge.js';
import { BRIDGE_KEY, IPC_GET_RUNTIME_ENDPOINT, IPC_PICK_PROJECT_FOLDER } from './constants.js';

const bridge: AgentsRoomBridge = {
  getRuntimeEndpoint: () =>
    ipcRenderer.invoke(IPC_GET_RUNTIME_ENDPOINT) as Promise<RuntimeEndpoint>,
  pickProjectFolder: () => ipcRenderer.invoke(IPC_PICK_PROJECT_FOLDER) as Promise<string | null>,
};

contextBridge.exposeInMainWorld(BRIDGE_KEY, bridge);
