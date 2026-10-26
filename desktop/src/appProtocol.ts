import { net, protocol } from 'electron';
import * as fs from 'fs';
import { pathToFileURL } from 'url';

import { appErrorResponse, appFileResponse } from './appResponse.js';
import { resolveBundlePath } from './bundlePath.js';
import { APP_SCHEME } from './constants.js';

/**
 * Must run before the app's `ready` event, exactly once. `standard` gives the
 * page a real origin (`app://bundle`) so relative URLs and web storage work,
 * `secure` makes it a secure context, `supportFetchAPI` lets the renderer
 * `fetch()` its own bundled assets.
 */
export function registerAppScheme(): void {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: APP_SCHEME,
      privileges: { standard: true, secure: true, supportFetchAPI: true },
    },
  ]);
}

async function isRegularFile(filePath: string): Promise<boolean> {
  try {
    return (await fs.promises.stat(filePath)).isFile();
  } catch {
    return false;
  }
}

/**
 * Serves the built renderer at `app://bundle/` with the strict CSP as a real
 * response header, on every response including the 400 and 404 ones. Only
 * regular files under `rootDir` are served: a directory would otherwise come
 * back as a file-URL listing.
 */
export function handleAppProtocol(rootDir: string): void {
  protocol.handle(APP_SCHEME, async (request) => {
    const filePath = resolveBundlePath(rootDir, request.url);
    if (!filePath) return appErrorResponse(400);
    if (!(await isRegularFile(filePath))) return appErrorResponse(404);
    try {
      return appFileResponse(await net.fetch(pathToFileURL(filePath).toString()));
    } catch {
      return appErrorResponse(404);
    }
  });
}
