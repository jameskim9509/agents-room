/**
 * Desktop shell constants. Every magic string and number of the Electron main
 * process and the preload lives here (CLAUDE.md constants policy).
 */

// ── Renderer origin (ADR 0001 decision 6) ────────────────────────────────────

/** Custom scheme that serves the built renderer. Registered privileged before app ready. */
export const APP_SCHEME = 'app';
/** The only host the scheme serves. The renderer's origin is `app://bundle`. */
export const APP_HOST = 'bundle';
export const APP_ORIGIN = `${APP_SCHEME}://${APP_HOST}`;
export const APP_ENTRY_URL = `${APP_ORIGIN}/index.html`;
export const APP_INDEX_FILE = 'index.html';

/** The Vite build (`dist/webview`), relative to the bundled main (`desktop/dist/main.js`). */
export const RENDERER_DIR_FROM_MAIN = ['..', '..', 'dist', 'webview'] as const;
/** The repository root, relative to the bundled main. Dev only: finds the runtime descriptor. */
export const REPO_ROOT_FROM_MAIN = ['..', '..'] as const;
/** The bundled preload, next to the bundled main. */
export const PRELOAD_FILE = 'preload.js';

/** Vite dev server origin. Allowed only by `desktop:dev` (unpackaged + env below). */
export const DEV_RENDERER_ORIGIN = 'http://localhost:5173';

/**
 * Strict CSP sent as a real response header by the `app://` handler.
 * - `connect-src` keeps 'self' for the renderer's own `fetch('./assets/…')` (bundled sprites)
 *   and allows only the loopback runtime WebSocket (ADR 0001 decision 6).
 * - React inline `style` props go through CSSOM, which `style-src 'self'` does not block.
 */
export const APP_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "font-src 'self'",
  "img-src 'self' data: blob:",
  "connect-src 'self' ws://127.0.0.1:*",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');

/**
 * Headers on EVERY `app://bundle` response, error responses included. A 404
 * without them would be a same-origin document with no CSP: a page that frames
 * or navigates to a missing path could run inline script and reach the network.
 */
export const APP_SECURITY_HEADERS: Readonly<Record<string, string>> = {
  'Content-Security-Policy': APP_CSP,
  'X-Content-Type-Options': 'nosniff',
};
export const APP_BAD_REQUEST_BODY = 'Bad request';
export const APP_NOT_FOUND_BODY = 'Not found';
export const APP_ERROR_CONTENT_TYPE = 'text/plain; charset=utf-8';

// ── Environment (shared handshake with the runtime) ──────────────────────────

/** Shell → runtime: per-launch WebSocket token (32 random bytes, base64url). */
export const ENV_RUNTIME_TOKEN = 'AGENTS_ROOM_TOKEN';
/** Shell → runtime: absolute data dir; the runtime keeps its files under `<dir>/runtime/`. */
export const ENV_RUNTIME_DATA_DIR = 'AGENTS_ROOM_DATA_DIR';
/** Shell → runtime: comma-separated Origin allowlist for `/ws`. */
export const ENV_RUNTIME_ALLOWED_ORIGINS = 'AGENTS_ROOM_ALLOWED_ORIGINS';
/** Override (tests, debugging): JSON array argv used to launch the runtime. Wins over the dev descriptor. */
export const ENV_RUNTIME_COMMAND = 'AGENTS_ROOM_RUNTIME_COMMAND';
/** Override (tests): ready-line timeout in ms. */
export const ENV_RUNTIME_READY_TIMEOUT_MS = 'AGENTS_ROOM_READY_TIMEOUT_MS';
/** Override (tests): Electron userData directory. Set before the single-instance lock. */
export const ENV_USER_DATA_DIR = 'AGENTS_ROOM_USER_DATA_DIR';
/** `desktop:dev`: load the renderer from this Vite dev server URL instead of `app://bundle`. */
export const ENV_RENDERER_URL = 'AGENTS_ROOM_RENDERER_URL';
/**
 * The shell's test and development knobs. Read only when the app is NOT
 * packaged: in an installed build, whoever can set the user's environment
 * could otherwise make the shell spawn any argv and hand it the token. The
 * runtime never sees them either.
 */
export const DEV_ONLY_ENV = [
  ENV_RUNTIME_COMMAND,
  ENV_RUNTIME_READY_TIMEOUT_MS,
  ENV_USER_DATA_DIR,
  ENV_RENDERER_URL,
] as const;

// ── Runtime launch and supervision (ADR 0001 decisions 3 and 5) ──────────────

/** JVM flags the shell always passes: UTF-8 pipes on Korean Windows (MS949 default). */
export const RUNTIME_JVM_ARGS = [
  '-Dstdout.encoding=UTF-8',
  '-Dstderr.encoding=UTF-8',
  '-Dfile.encoding=UTF-8',
] as const;
/** Dev launch descriptor written by the runtime's Gradle build, relative to the repo root. */
export const DEV_RUNTIME_DESCRIPTOR = ['runtime', 'build', 'dev-runtime.json'] as const;
/** The one stdout line the runtime prints when ready: `AGENTS_ROOM_READY {"port":<int>}`. */
export const READY_LINE_PREFIX = 'AGENTS_ROOM_READY ';
export const RUNTIME_WS_HOST = '127.0.0.1';
export const RUNTIME_WS_PATH = '/ws';
export const RUNTIME_TOKEN_BYTES = 32;
export const RUNTIME_READY_TIMEOUT_MS = 30_000;
/** On quit: how long the runtime gets to exit on stdin EOF before its tree is killed. */
export const RUNTIME_QUIT_GRACE_MS = 3_000;
/** After a tree kill: how long to wait for the exit event before giving up on it. */
export const RUNTIME_KILL_WAIT_MS = 2_000;
export const LOG_DIR_NAME = 'logs';
export const RUNTIME_LOG_FILE = 'runtime.log';
/** The previous session's log is kept once under this name. */
export const RUNTIME_LOG_PREVIOUS_FILE = 'runtime.previous.log';
/** Fallback for a session whose `runtime.log` cannot be opened (held open elsewhere). */
export const runtimeSessionLogFile = (pid: number): string => `runtime-${pid}.log`;
export const TOKEN_MASK = '***';
/** Prefix of the shell's own lines in runtime.log (the runtime's lines are written as-is). */
export const LOG_SHELL_PREFIX = '[shell]';
export const LOG_STDOUT_PREFIX = '[stdout]';
/** Main-process console prefix (CLAUDE.md logging convention). */
export const CONSOLE_PREFIX = '[Desktop]';
export const CONSOLE_RUNTIME_PREFIX = '[runtime]';

/**
 * Permissions the renderer may get, and only for its own origin: none of the
 * page's features needs a permission. The loopback entries are a precaution in
 * case a later Chromium gates `app://` → `ws://127.0.0.1` behind Local Network
 * Access (Electron 45 does not ask).
 */
export const ALLOWED_PERMISSIONS: readonly string[] = [
  'loopback-network',
  'local-network-access',
  'local-network',
];

// ── IPC (preload ↔ main). Only these two channels exist. ─────────────────────

export const IPC_GET_RUNTIME_ENDPOINT = 'agents-room:get-runtime-endpoint';
export const IPC_PICK_PROJECT_FOLDER = 'agents-room:pick-project-folder';
/** `contextBridge` key. The renderer detects the desktop runtime by its presence. */
export const BRIDGE_KEY = 'agentsRoom';

// ── Window ───────────────────────────────────────────────────────────────────

export const APP_TITLE = 'agents-room';
export const WINDOW_WIDTH = 1280;
export const WINDOW_HEIGHT = 800;
export const WINDOW_MIN_WIDTH = 800;
export const WINDOW_MIN_HEIGHT = 500;
/** Matches the renderer's `--color-bg`, so the window never flashes white before the first paint. */
export const WINDOW_BACKGROUND = '#1e1e2e';
/** `desktop:dev`: wait for the Vite dev server before giving up. */
export const DEV_SERVER_RETRY_MS = 500;
export const DEV_SERVER_MAX_RETRIES = 60;

// ── User-facing copy (docs/ux.md 문구 목록) ──────────────────────────────────

export const COPY_RUNTIME_FAILED = '로컬 런타임을 시작하지 못했습니다';
export const COPY_RESTART = '다시 시작';
export const COPY_QUIT = '종료';
export const COPY_LOG_PATH = '로그';
export const COPY_CAUSE_NO_COMMAND =
  '런타임 실행 정보가 없습니다. runtime/build/dev-runtime.json을 만들려면 런타임을 먼저 빌드하세요.';
export const COPY_CAUSE_BAD_COMMAND = '런타임 실행 정보가 올바르지 않습니다.';
export const COPY_CAUSE_SPAWN = '런타임 프로세스를 실행하지 못했습니다.';
export const COPY_CAUSE_TIMEOUT = '준비 신호가 제한 시간 안에 오지 않았습니다.';
export const COPY_CAUSE_EXIT_BEFORE_READY = '런타임 프로세스가 준비 전에 종료되었습니다.';
export const COPY_CAUSE_EXIT = '런타임 프로세스가 종료되었습니다.';
export const COPY_EXIT_CODE = '종료 코드';
