import { defineConfig } from '@playwright/test';
import path from 'path';

import { namespaceE2EPath } from './run-config';

process.env['ALLURE_LABEL_epic'] ??= 'e2e';

const artifactsDir = namespaceE2EPath(path.join(__dirname, '../test-results/e2e-desktop'));
const allureResultsDir = namespaceE2EPath(path.join(__dirname, '../allure-results/e2e'));
const htmlReportDir = namespaceE2EPath(path.join(__dirname, '../playwright-report/e2e-desktop'));

/**
 * Desktop shell suite: the built Electron shell (desktop/dist) serving the
 * built renderer (dist/webview) at app://bundle, against a fake runtime.
 * Separate from the legacy config on purpose: its testDir never sees these
 * specs, and this config has no VS Code download in a global setup.
 * Run `npm run desktop:build` first.
 */
export default defineConfig({
  testDir: path.join(__dirname, 'desktop'),
  globalSetup: path.join(__dirname, 'desktop', 'global-setup.ts'),
  timeout: 60_000,
  reporter: [
    ['list'],
    ['html', { outputFolder: htmlReportDir, open: 'never' }],
    ['allure-playwright', { resultsDir: allureResultsDir }],
  ],
  outputDir: artifactsDir,
  // One Electron app at a time: each test owns a whole app plus its runtime.
  workers: 1,
  fullyParallel: true,
  retries: 1,
});
