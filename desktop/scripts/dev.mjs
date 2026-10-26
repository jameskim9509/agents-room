/**
 * `npm run desktop:dev`: the Vite dev server (HMR) plus Electron, with the
 * shell rebuilt on change.
 *
 * Electron loads http://localhost:5173 instead of app://bundle. The shell
 * accepts that origin only unpackaged and only when AGENTS_ROOM_RENDERER_URL
 * is set, and passes it to the runtime's Origin allowlist. The strict CSP
 * header belongs to the app:// handler, so it does not apply here (Vite needs
 * its inline preamble and its HMR socket). Restart this script after editing
 * main or preload; the renderer reloads by itself.
 */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildShell } from '../esbuild.mjs';

const DEV_PORT = 5173;
const DEV_RENDERER_URL = `http://localhost:${DEV_PORT}/`;

const desktopDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webviewDir = path.resolve(desktopDir, '..', 'webview-ui');
const require = createRequire(path.join(webviewDir, 'package.json'));

const vitePackage = require.resolve('vite/package.json');
const viteBin = path.join(path.dirname(vitePackage), require(vitePackage).bin.vite);
/** The `electron` package resolves to the path of its binary (downloaded on first use). */
const electronBinary = createRequire(path.join(desktopDir, 'package.json'))('electron');

const shellBuild = await buildShell({ watch: true });

const vite = spawn(process.execPath, [viteBin, '--port', String(DEV_PORT), '--strictPort'], {
  cwd: webviewDir,
  stdio: 'inherit',
});

const electron = spawn(electronBinary, [desktopDir], {
  stdio: 'inherit',
  env: { ...process.env, AGENTS_ROOM_RENDERER_URL: DEV_RENDERER_URL },
});

async function shutdown(code) {
  vite.kill();
  if (!electron.killed && electron.exitCode === null) electron.kill();
  await shellBuild?.dispose();
  process.exit(code);
}

electron.on('exit', (code) => void shutdown(code ?? 0));
vite.on('exit', (code) => {
  if (code !== 0 && code !== null) {
    console.error(`[desktop:dev] Vite exited with code ${code}`);
    void shutdown(code);
  }
});
process.on('SIGINT', () => void shutdown(0));
