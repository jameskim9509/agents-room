import * as path from 'path';

import { APP_HOST, APP_INDEX_FILE, APP_SCHEME } from './constants.js';

/**
 * Maps an `app://bundle/<path>` URL to a file under `rootDir`, or `null` when
 * the URL must not be served: another host, a malformed escape, or any path
 * that leaves `rootDir` (`..`, another drive, an NTFS stream name).
 */
export function resolveBundlePath(rootDir: string, requestUrl: string): string | null {
  let url: URL;
  try {
    url = new URL(requestUrl);
  } catch {
    return null;
  }
  if (url.protocol !== `${APP_SCHEME}:` || url.hostname !== APP_HOST) return null;

  let relative: string;
  try {
    relative = decodeURIComponent(url.pathname);
  } catch {
    return null;
  }
  relative = relative.replace(/^[/\\]+/, '');
  if (relative === '') relative = APP_INDEX_FILE;
  if (relative.includes('\0')) return null;
  // `:` never appears in a bundled file name. On Windows it would name a drive
  // or an alternate data stream (`index.html:secret`).
  if (relative.includes(':')) return null;

  const root = path.resolve(rootDir);
  const absolute = path.resolve(root, relative);
  const fromRoot = path.relative(root, absolute);
  if (fromRoot === '' || fromRoot.startsWith('..') || path.isAbsolute(fromRoot)) return null;
  return absolute;
}
