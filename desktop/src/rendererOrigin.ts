/**
 * Origin checks for the renderer. Navigation, permissions and IPC all accept
 * only the renderer's own origin: `app://bundle`, plus the Vite dev server in
 * `desktop:dev`.
 */

/**
 * `scheme://host[:port]` of a URL, or `null` when it does not parse. WHATWG
 * `URL.origin` is the string `"null"` for non-special schemes such as `app:`,
 * so the origin is assembled from its parts instead.
 */
export function urlOrigin(rawUrl: string): string | null {
  try {
    const url = new URL(rawUrl);
    return url.host === '' ? null : `${url.protocol}//${url.host}`;
  } catch {
    return null;
  }
}

export function isAllowedRendererUrl(rawUrl: string, allowedOrigins: readonly string[]): boolean {
  const origin = urlOrigin(rawUrl);
  return origin !== null && allowedOrigins.includes(origin);
}

/** For an origin string as Chromium serializes it (`WebFrameMain.origin`, permission checks). */
export function isAllowedOrigin(origin: string, allowedOrigins: readonly string[]): boolean {
  return allowedOrigins.includes(origin);
}
