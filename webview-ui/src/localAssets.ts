/**
 * Renderer-local asset loading: sprites, furniture and the default layout from
 * the renderer's own bundle.
 *
 * The desktop renderer (`app://bundle`) loads its assets itself; the local
 * runtime never sends sprites. It reads the pre-decoded JSON the Vite build
 * writes next to the PNGs (`DECODED_ASSET_PATHS`), and decodes the PNGs in
 * the page only when that JSON is missing. The Vite dev server serves the
 * same JSON through its middleware, so dev and production take the same path.
 *
 * The result is the ordered list of the asset frames the office expects
 * (characters → pets → floors → walls → carpets → furniture → layout). They go
 * to the same message handler as wire frames, so `useExtensionMessages`
 * applies them exactly as it applies server-sent assets in the other surfaces.
 */

import { rgbaToHex } from '../../core/src/assets/colorUtils.ts';
import {
  CHAR_FRAME_H,
  CHAR_FRAME_W,
  CHAR_FRAMES_PER_ROW,
  CHARACTER_DIRECTIONS,
  DECODED_ASSET_PATHS,
  FLOOR_TILE_SIZE,
  WALL_BITMASK_COUNT,
  WALL_GRID_COLS,
  WALL_PIECE_HEIGHT,
  WALL_PIECE_WIDTH,
} from '../../core/src/assets/constants.ts';
import type {
  AssetIndex,
  CatalogEntry,
  CharacterDirectionSprites,
  PetSpriteFrames,
} from '../../core/src/assets/types.ts';
import type { ServerMessage } from '../../core/src/messages.ts';

/**
 * The asset frames this loader produces.
 *
 * TODO(contract): these frames never cross the wire in desktop mode. They reuse
 * the upstream ServerMessage variants only because `useExtensionMessages`
 * handles those today. When the renderer↔runtime contract (core/asyncapi.yaml,
 * feat/contract-sprint0) drops the upstream asset variants, give this union its
 * own renderer-local type and keep the frames off the transport.
 */
export type LocalAssetMessage = Extract<
  ServerMessage,
  {
    type:
      | 'characterSpritesLoaded'
      | 'petSpritesLoaded'
      | 'floorTilesLoaded'
      | 'wallTilesLoaded'
      | 'carpetTilesLoaded'
      | 'furnitureAssetsLoaded'
      | 'layoutLoaded';
  }
>;

interface DecodedPets {
  pets: PetSpriteFrames[];
  petNames: string[];
}

// ── PNG decode helpers (fallback when the decoded JSON is missing) ───────────

interface DecodedPng {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}

function getPixel(
  data: Uint8ClampedArray,
  width: number,
  x: number,
  y: number,
): [number, number, number, number] {
  const idx = (y * width + x) * 4;
  return [data[idx], data[idx + 1], data[idx + 2], data[idx + 3]];
}

function readSprite(
  png: DecodedPng,
  width: number,
  height: number,
  offsetX = 0,
  offsetY = 0,
): string[][] {
  const sprite: string[][] = [];
  for (let y = 0; y < height; y++) {
    const row: string[] = [];
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = getPixel(png.data, png.width, offsetX + x, offsetY + y);
      row.push(rgbaToHex(r, g, b, a));
    }
    sprite.push(row);
  }
  return sprite;
}

async function decodePng(url: string): Promise<DecodedPng> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Failed to fetch PNG: ${url} (${res.status.toString()})`);
  }
  const blob = await res.blob();
  const bitmap = await createImageBitmap(blob);
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    bitmap.close();
    throw new Error('Failed to create 2d canvas context for PNG decode');
  }
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
  return { width: canvas.width, height: canvas.height, data: imageData.data };
}

async function fetchJsonOptional<T>(url: string): Promise<T | null> {
  try {
    const res = await fetch(url);
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

function getIndexedAssetPath(kind: 'characters' | 'floors' | 'walls', relPath: string): string {
  return relPath.startsWith(`${kind}/`) ? relPath : `${kind}/${relPath}`;
}

async function decodeCharactersFromPng(
  base: string,
  index: AssetIndex,
): Promise<CharacterDirectionSprites[]> {
  const sprites: CharacterDirectionSprites[] = [];
  for (const relPath of index.characters) {
    const png = await decodePng(`${base}assets/${getIndexedAssetPath('characters', relPath)}`);
    const byDir: CharacterDirectionSprites = { down: [], up: [], right: [] };

    for (let dirIdx = 0; dirIdx < CHARACTER_DIRECTIONS.length; dirIdx++) {
      const dir = CHARACTER_DIRECTIONS[dirIdx];
      const rowOffsetY = dirIdx * CHAR_FRAME_H;
      const frames: string[][][] = [];
      for (let frame = 0; frame < CHAR_FRAMES_PER_ROW; frame++) {
        frames.push(readSprite(png, CHAR_FRAME_W, CHAR_FRAME_H, frame * CHAR_FRAME_W, rowOffsetY));
      }
      byDir[dir] = frames;
    }

    sprites.push(byDir);
  }
  return sprites;
}

async function decodeFloorsFromPng(base: string, index: AssetIndex): Promise<string[][][]> {
  const floors: string[][][] = [];
  for (const relPath of index.floors) {
    const png = await decodePng(`${base}assets/${getIndexedAssetPath('floors', relPath)}`);
    floors.push(readSprite(png, FLOOR_TILE_SIZE, FLOOR_TILE_SIZE));
  }
  return floors;
}

async function decodeWallsFromPng(base: string, index: AssetIndex): Promise<string[][][][]> {
  const wallSets: string[][][][] = [];
  for (const relPath of index.walls) {
    const png = await decodePng(`${base}assets/${getIndexedAssetPath('walls', relPath)}`);
    const set: string[][][] = [];
    for (let mask = 0; mask < WALL_BITMASK_COUNT; mask++) {
      const ox = (mask % WALL_GRID_COLS) * WALL_PIECE_WIDTH;
      const oy = Math.floor(mask / WALL_GRID_COLS) * WALL_PIECE_HEIGHT;
      set.push(readSprite(png, WALL_PIECE_WIDTH, WALL_PIECE_HEIGHT, ox, oy));
    }
    wallSets.push(set);
  }
  return wallSets;
}

async function decodeFurnitureFromPng(
  base: string,
  catalog: CatalogEntry[],
): Promise<Record<string, string[][]>> {
  const sprites: Record<string, string[][]> = {};
  for (const entry of catalog) {
    const png = await decodePng(`${base}assets/${entry.furniturePath}`);
    sprites[entry.id] = readSprite(png, entry.width, entry.height);
  }
  return sprites;
}

// ── Public API ─────────────────────────────────────────────────────────────────

/**
 * Load every bundled asset and return the frames in the order the office
 * applies them. `base` is the page's asset base (`import.meta.env.BASE_URL`:
 * `'/'` in dev, `'./'` in the production build).
 */
export async function loadLocalAssets(
  base: string = import.meta.env.BASE_URL,
): Promise<LocalAssetMessage[]> {
  console.log('[LocalAssets] Loading assets...');

  const decodedUrl = (kind: keyof typeof DECODED_ASSET_PATHS): string =>
    `${base}assets/${DECODED_ASSET_PATHS[kind]}`;

  const [assetIndex, catalog] = await Promise.all([
    fetch(`${base}assets/asset-index.json`).then((r) => r.json()) as Promise<AssetIndex>,
    fetch(`${base}assets/furniture-catalog.json`).then((r) => r.json()) as Promise<CatalogEntry[]>,
  ]);

  const [decodedCharacters, decodedFloors, decodedWalls, decodedFurniture] = await Promise.all([
    fetchJsonOptional<CharacterDirectionSprites[]>(decodedUrl('characters')),
    fetchJsonOptional<string[][][]>(decodedUrl('floors')),
    fetchJsonOptional<string[][][][]>(decodedUrl('walls')),
    fetchJsonOptional<Record<string, string[][]>>(decodedUrl('furniture')),
  ]);

  const hasDecoded = !!(decodedCharacters && decodedFloors && decodedWalls && decodedFurniture);
  if (!hasDecoded) {
    console.log('[LocalAssets] Decoded JSON not found, decoding PNG assets in the page...');
  }

  const [characters, floorSprites, wallSets, furnitureSprites] = hasDecoded
    ? [decodedCharacters!, decodedFloors!, decodedWalls!, decodedFurniture!]
    : await Promise.all([
        decodeCharactersFromPng(base, assetIndex),
        decodeFloorsFromPng(base, assetIndex),
        decodeWallsFromPng(base, assetIndex),
        decodeFurnitureFromPng(base, catalog),
      ]);

  const layout = assetIndex.defaultLayout
    ? ((await fetch(`${base}assets/${assetIndex.defaultLayout}`).then((r) => r.json())) as Record<
        string,
        unknown
      >)
    : null;

  // Carpets and pets have no in-page PNG fallback (no asset-index entry). Without
  // their JSON the carpet tab shows no variants and pets stay hidden.
  const carpetSets = (await fetchJsonOptional<string[][][][]>(decodedUrl('carpets'))) ?? [];
  const pets = await fetchJsonOptional<DecodedPets>(decodedUrl('pets'));

  console.log(
    `[LocalAssets] Ready (${hasDecoded ? 'decoded-json' : 'png-decode'}) — ${characters.length} chars, ${pets?.pets.length ?? 0} pets, ${floorSprites.length} floors, ${wallSets.length} wall sets, ${carpetSets.length} carpets, ${catalog.length} furniture items`,
  );

  // Must match the load order the office expects (CLAUDE.md "Load order"):
  // characters → floors → walls → carpets → furniture → layout. Pets follow
  // characters, as in the server's webviewReady handshake.
  const messages: LocalAssetMessage[] = [{ type: 'characterSpritesLoaded', characters }];
  if (pets && pets.pets.length > 0) {
    messages.push({ type: 'petSpritesLoaded', pets: pets.pets, petNames: pets.petNames });
  }
  messages.push(
    { type: 'floorTilesLoaded', sprites: floorSprites },
    { type: 'wallTilesLoaded', sets: wallSets },
    { type: 'carpetTilesLoaded', sets: carpetSets },
    { type: 'furnitureAssetsLoaded', catalog, sprites: furnitureSprites },
    { type: 'layoutLoaded', layout },
  );
  return messages;
}
