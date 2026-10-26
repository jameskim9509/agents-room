import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import * as fs from 'fs';
import * as path from 'path';
import type { Plugin } from 'vite';
import { defineConfig } from 'vite';

import { buildAssetIndex, buildFurnitureCatalog } from '../core/src/assets/build.ts';
import { DECODED_ASSET_PATHS } from '../core/src/assets/constants.ts';
import {
  decodeAllCarpets,
  decodeAllCharacters,
  decodeAllFloors,
  decodeAllFurniture,
  decodeAllPets,
  decodeAllWalls,
} from '../core/src/assets/loader.ts';

// ── Decoded asset cache (invalidated on file change) ─────────────────────────

type DecodedKind = keyof typeof DECODED_ASSET_PATHS;
type DecodedCache = Partial<Record<DecodedKind, unknown>>;

/** Decode one sprite group from the PNG sources. Shared by dev middleware and build output. */
function decodeKind(kind: DecodedKind, assetsDir: string): unknown {
  switch (kind) {
    case 'characters':
      return decodeAllCharacters(assetsDir);
    case 'floors':
      return decodeAllFloors(assetsDir);
    case 'walls':
      return decodeAllWalls(assetsDir);
    case 'carpets':
      return decodeAllCarpets(assetsDir);
    case 'furniture':
      return decodeAllFurniture(assetsDir, buildFurnitureCatalog(assetsDir));
    case 'pets':
      return decodeAllPets(assetsDir);
  }
}

const DECODED_KINDS = Object.keys(DECODED_ASSET_PATHS) as DecodedKind[];

// ── Vite plugin ───────────────────────────────────────────────────────────────

function browserMockAssetsPlugin(): Plugin {
  const assetsDir = path.resolve(__dirname, 'public/assets');
  // Default for production builds; configResolved below rewrites this to
  // track the actual build.outDir, so vite build calls that override outDir
  // (e.g. the build-subpath integration test) still receive the JSON sidecars.
  let distAssetsDir = path.resolve(__dirname, '../dist/webview/assets');

  let cache: DecodedCache = {};

  function clearCache(): void {
    cache = {};
  }

  return {
    name: 'browser-mock-assets',
    configResolved(config) {
      distAssetsDir = path.resolve(config.root, config.build.outDir, 'assets');
    },
    configureServer(server) {
      // Strip trailing slash: '/' → '', '/sub/' → '/sub'
      const base = server.config.base.replace(/\/$/, '');

      // Catalog & index (existing)
      server.middlewares.use(`${base}/assets/furniture-catalog.json`, (_req, res) => {
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify(buildFurnitureCatalog(assetsDir)));
      });
      server.middlewares.use(`${base}/assets/asset-index.json`, (_req, res) => {
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify(buildAssetIndex(assetsDir)));
      });

      // Pre-decoded sprites (eliminates browser-side PNG decoding)
      for (const kind of DECODED_KINDS) {
        server.middlewares.use(`${base}/assets/${DECODED_ASSET_PATHS[kind]}`, (_req, res) => {
          cache[kind] ??= decodeKind(kind, assetsDir);
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(cache[kind]));
        });
      }

      // Hot-reload on asset file changes (PNGs, manifests, layouts)
      server.watcher.add(assetsDir);
      server.watcher.on('change', (file) => {
        if (file.startsWith(assetsDir)) {
          console.log(`[browser-mock-assets] Asset changed: ${path.relative(assetsDir, file)}`);
          clearCache();
          server.ws.send({ type: 'full-reload' });
        }
      });
    },
    // Build output: the asset metadata plus the same pre-decoded sprite JSON the
    // dev middleware serves. The desktop renderer loads its own assets from these
    // files under app://bundle (no runtime message carries sprites).
    closeBundle() {
      fs.mkdirSync(distAssetsDir, { recursive: true });

      const catalog = buildFurnitureCatalog(assetsDir);
      fs.writeFileSync(path.join(distAssetsDir, 'furniture-catalog.json'), JSON.stringify(catalog));
      fs.writeFileSync(
        path.join(distAssetsDir, 'asset-index.json'),
        JSON.stringify(buildAssetIndex(assetsDir)),
      );
      for (const kind of DECODED_KINDS) {
        const outFile = path.join(distAssetsDir, DECODED_ASSET_PATHS[kind]);
        fs.mkdirSync(path.dirname(outFile), { recursive: true });
        fs.writeFileSync(outFile, JSON.stringify(decodeKind(kind, assetsDir)));
      }
    },
  };
}

export default defineConfig({
  plugins: [tailwindcss(), react(), browserMockAssetsPlugin()],
  build: {
    outDir: '../dist/webview',
    emptyOutDir: true,
  },
  base: './',
});
