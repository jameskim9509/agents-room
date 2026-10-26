/**
 * Server-side asset decoders — shared between Vite plugin, extension host,
 * and future standalone backends.
 *
 * Reads PNG files from an assets directory and decodes them into SpriteData
 * format using the shared pngDecoder module.
 */

import * as fs from 'fs';
import * as path from 'path';

import { MAX_PET_PNG_SIZE } from './constants.js';
import {
  decodeCharacterPng,
  decodeFloorPng,
  decodePetPng,
  parseCarpetPng,
  parseWallPng,
  pngToSpriteData,
} from './pngDecoder.js';
import type {
  CatalogEntry,
  CharacterDirectionSprites,
  PetManifest,
  PetSpriteFrames,
} from './types.js';

// ── Helpers ──────────────────────────────────────────────────────────────────

function listSortedPngs(dir: string, pattern: RegExp): { index: number; filename: string }[] {
  if (!fs.existsSync(dir)) return [];
  const files: { index: number; filename: string }[] = [];
  for (const entry of fs.readdirSync(dir)) {
    const match = pattern.exec(entry);
    if (match) {
      files.push({ index: parseInt(match[1], 10), filename: entry });
    }
  }
  return files.sort((a, b) => a.index - b.index);
}

// ── Decoders ─────────────────────────────────────────────────────────────────

export function decodeAllCharacters(assetsDir: string): CharacterDirectionSprites[] {
  const charDir = path.join(assetsDir, 'characters');
  const files = listSortedPngs(charDir, /^char_(\d+)\.png$/i);
  return files.map(({ filename }) => {
    const pngBuffer = fs.readFileSync(path.join(charDir, filename));
    return decodeCharacterPng(pngBuffer);
  });
}

export function decodeAllFloors(assetsDir: string): string[][][] {
  const floorsDir = path.join(assetsDir, 'floors');
  const files = listSortedPngs(floorsDir, /^floor_(\d+)\.png$/i);
  return files.map(({ filename }) => {
    const pngBuffer = fs.readFileSync(path.join(floorsDir, filename));
    return decodeFloorPng(pngBuffer);
  });
}

export function decodeAllWalls(assetsDir: string): string[][][][] {
  const wallsDir = path.join(assetsDir, 'walls');
  const files = listSortedPngs(wallsDir, /^wall_(\d+)\.png$/i);
  return files.map(({ filename }) => {
    const pngBuffer = fs.readFileSync(path.join(wallsDir, filename));
    return parseWallPng(pngBuffer);
  });
}

export function decodeAllCarpets(assetsDir: string): string[][][][] {
  const carpetsDir = path.join(assetsDir, 'carpets');
  const files = listSortedPngs(carpetsDir, /^carpet_(\d+)\.png$/i);
  return files.map(({ filename }) => {
    const pngBuffer = fs.readFileSync(path.join(carpetsDir, filename));
    return parseCarpetPng(pngBuffer);
  });
}

export function decodeAllFurniture(
  assetsDir: string,
  catalog: CatalogEntry[],
): Record<string, string[][]> {
  const sprites: Record<string, string[][]> = {};
  for (const entry of catalog) {
    try {
      const filePath = path.join(assetsDir, entry.furniturePath);
      if (!fs.existsSync(filePath)) continue;
      const pngBuffer = fs.readFileSync(filePath);
      sprites[entry.id] = pngToSpriteData(pngBuffer, entry.width, entry.height);
    } catch (err) {
      console.warn(`[decodeAssets] Failed to decode ${entry.id}:`, err);
    }
  }
  return sprites;
}

/** Decoded bundled pets, parallel to their display names (the `petSpritesLoaded` payload). */
export interface DecodedPets {
  pets: PetSpriteFrames[];
  petNames: string[];
}

/**
 * Decode `<assetsDir>/pets/<dir>/{manifest.json,pet.png}` in directory-name
 * order, with the rules of the server loader (`loadPetSprites` in
 * `server/src/assetLoader.ts`): both files present, manifest `id` and `name`
 * set, PNG at most `MAX_PET_PNG_SIZE`. Other pets are skipped.
 */
export function decodeAllPets(assetsDir: string): DecodedPets {
  const petsDir = path.join(assetsDir, 'pets');
  const result: DecodedPets = { pets: [], petNames: [] };
  if (!fs.existsSync(petsDir)) return result;
  const dirNames = fs
    .readdirSync(petsDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
  for (const dirName of dirNames) {
    const manifestPath = path.join(petsDir, dirName, 'manifest.json');
    const pngPath = path.join(petsDir, dirName, 'pet.png');
    try {
      if (!fs.existsSync(manifestPath) || !fs.existsSync(pngPath)) continue;
      const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8')) as Partial<PetManifest>;
      if (!manifest.id || !manifest.name) continue;
      if (fs.statSync(pngPath).size > MAX_PET_PNG_SIZE) continue;
      result.pets.push(decodePetPng(fs.readFileSync(pngPath)));
      result.petNames.push(manifest.name);
    } catch (err) {
      console.warn(`[decodeAssets] Failed to decode pet ${dirName}:`, err);
    }
  }
  return result;
}
