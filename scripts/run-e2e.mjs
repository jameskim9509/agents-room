import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ATTACH_VIDEOS_ON_SUCCESS_FLAG = '--attach-videos-on-success';
const ATTACH_VIDEOS_ON_SUCCESS_ENV = 'PIXEL_AGENTS_E2E_ATTACH_VIDEOS_ON_SUCCESS';
const RUN_ID_FLAG = '--run-id';
const RUN_ID_ENV = 'PIXEL_AGENTS_E2E_RUN_ID';
const SUITE_FLAG = '--suite';
/** Playwright config per suite: `desktop` is the Electron shell (e2e/desktop), `legacy` the default. */
const SUITE_CONFIGS = {
  legacy: 'e2e/playwright.config.ts',
  desktop: 'e2e/playwright.desktop.config.ts',
};

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const playwrightCli = path.join(repoRoot, 'node_modules', 'playwright', 'cli.js');

const forwardedArgs = [];
let attachVideosOnSuccess = false;
let runId = process.env[RUN_ID_ENV];
let suite = 'legacy';

const argv = process.argv.slice(2);
for (let index = 0; index < argv.length; index += 1) {
  const arg = argv[index];
  if (arg === ATTACH_VIDEOS_ON_SUCCESS_FLAG) {
    attachVideosOnSuccess = true;
    continue;
  }

  if (arg === RUN_ID_FLAG) {
    runId = argv[index + 1] ?? '';
    index += 1;
    continue;
  }

  if (arg.startsWith(`${RUN_ID_FLAG}=`)) {
    runId = arg.slice(`${RUN_ID_FLAG}=`.length);
    continue;
  }

  if (arg.startsWith(`${SUITE_FLAG}=`)) {
    suite = arg.slice(`${SUITE_FLAG}=`.length);
    continue;
  }

  forwardedArgs.push(arg);
}

const config = Object.hasOwn(SUITE_CONFIGS, suite) ? SUITE_CONFIGS[suite] : undefined;
if (!config) {
  console.error(
    `Unknown ${SUITE_FLAG}=${suite}. Use one of: ${Object.keys(SUITE_CONFIGS).join(', ')}`,
  );
  process.exit(1);
}

const result = spawnSync(
  process.execPath,
  [playwrightCli, 'test', '--config', config, ...forwardedArgs],
  {
    cwd: repoRoot,
    env: {
      ...process.env,
      ...(attachVideosOnSuccess ? { [ATTACH_VIDEOS_ON_SUCCESS_ENV]: '1' } : {}),
      ...(runId ? { [RUN_ID_ENV]: runId } : {}),
    },
    stdio: 'inherit',
  },
);

if (result.error) {
  throw result.error;
}

process.exit(result.status ?? 1);
