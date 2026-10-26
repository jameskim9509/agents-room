import {
  APP_BAD_REQUEST_BODY,
  APP_ERROR_CONTENT_TYPE,
  APP_NOT_FOUND_BODY,
  APP_SECURITY_HEADERS,
} from './constants.js';

/** `base` (a served file's headers, if any) with the security headers set over it. */
function secureHeaders(base?: Headers): Headers {
  const headers = new Headers(base);
  for (const [name, value] of Object.entries(APP_SECURITY_HEADERS)) headers.set(name, value);
  return headers;
}

/** A served bundle file. Its body and status pass through; the security headers are added. */
export function appFileResponse(file: Response): Response {
  return new Response(file.body, { status: file.status, headers: secureHeaders(file.headers) });
}

/** An `app://bundle` error. It carries the same CSP as a page, so it cannot serve as a CSP-free document. */
export function appErrorResponse(status: 400 | 404): Response {
  const body = status === 400 ? APP_BAD_REQUEST_BODY : APP_NOT_FOUND_BODY;
  const headers = secureHeaders();
  headers.set('Content-Type', APP_ERROR_CONTENT_TYPE);
  return new Response(body, { status, headers });
}
