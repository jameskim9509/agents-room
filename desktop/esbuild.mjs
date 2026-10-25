/**
 * Bundles the Electron main process and the preload into desktop/dist (CJS).
 *
 * The preload must be one self-contained CommonJS file: a sandboxed preload
 * can `require` only `electron` (Electron 45), so everything else is bundled.
 * Main is CJS too, so `protocol.registerSchemesAsPrivileged` runs before the
 * app's `ready` event without ESM import-order surprises.
 *
 * Usage: node esbuild.mjs [--watch]
 */
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import * as esbuild from 'esbuild';

const desktopDir = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('esbuild').BuildOptions} */
const options = {
  entryPoints: {
    main: path.join(desktopDir, 'src', 'main.ts'),
    preload: path.join(desktopDir, 'src', 'preload.ts'),
  },
  outdir: path.join(desktopDir, 'dist'),
  bundle: true,
  format: 'cjs',
  platform: 'node',
  // Electron 45.0.0 bundles Node 24.22.0.
  target: 'node24',
  external: ['electron'],
  sourcemap: true,
  logLevel: 'info',
};

/** Builds once, or keeps rebuilding on change when `watch` is set. */
export async function buildShell({ watch = false } = {}) {
  if (!watch) {
    await esbuild.build(options);
    return null;
  }
  const context = await esbuild.context(options);
  await context.watch();
  return context;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  await buildShell({ watch: process.argv.includes('--watch') });
}
