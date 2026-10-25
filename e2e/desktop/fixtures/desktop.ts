/**
 * Playwright fixture for the desktop shell suite (e2e/desktop). Each test
 * launches the shell through `desktop.launch(...)`; every launched app is
 * closed, its fake runtimes are killed and its temp dirs removed afterwards.
 * On failure runtime.log is attached.
 */
import { test as base } from '@playwright/test';
import fs from 'fs';

import { applyAllureLabels } from '../../helpers/allure-labels';
import { type DesktopSession, launchDesktop, type LaunchDesktopOptions } from '../helpers/desktop';

export interface DesktopFixture {
  launch: (opts?: LaunchDesktopOptions) => Promise<DesktopSession>;
}

export const test = base.extend<{ desktop: DesktopFixture; _allureLabels: void }>({
  _allureLabels: [
    async ({}, use, testInfo) => {
      await applyAllureLabels(testInfo);
      await use();
    },
    { auto: true },
  ],
  desktop: async ({}, use, testInfo) => {
    const sessions: DesktopSession[] = [];
    await use({
      launch: async (opts) => {
        const session = await launchDesktop(opts);
        sessions.push(session);
        return session;
      },
    });
    for (const session of sessions) {
      if (testInfo.status !== testInfo.expectedStatus && fs.existsSync(session.runtimeLogPath)) {
        await testInfo.attach('runtime.log', {
          body: fs.readFileSync(session.runtimeLogPath),
          contentType: 'text/plain',
        });
      }
      await session.cleanup();
    }
  },
});

export { expect } from '@playwright/test';
