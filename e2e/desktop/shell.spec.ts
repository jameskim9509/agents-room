/**
 * Desktop shell: window, app://bundle, preload bridge, folder picker.
 * The runtime is the fake one (e2e/desktop/fixtures/fake-runtime.cjs); see
 * e2e/README.md "Mocking model & rules".
 */
import fs from 'fs';

import type { AgentsRoomBridge, RuntimeEndpoint } from '../../core/src/desktopBridge';
import { expect, test } from './fixtures/desktop';
import { expectOfficeConnected, officeCanvasColorCount } from './helpers/desktop';

type BridgeWindow = Window & { agentsRoom: AgentsRoomBridge; __cspViolations: string[] };

const PAGE_URL = 'app://bundle/index.html';
const MISSING_URL = 'app://bundle/does-not-exist';
/** `..%2f` decodes to `../`: refused by the path-traversal guard with a 400. */
const ESCAPING_URL = 'app://bundle/..%2fx';

test.describe('Desktop shell', () => {
  test('opens the office from app://bundle and joins the runtime with its token @area:desktop-shell', async ({
    desktop,
  }) => {
    const { window: page, runtimeLogPath } = await desktop.launch();

    // Record CSP violations from the first script on: install the listener, then reload.
    await page.addInitScript(() => {
      const violations: string[] = [];
      (window as unknown as BridgeWindow).__cspViolations = violations;
      document.addEventListener('securitypolicyviolation', (event) => {
        violations.push(`${event.violatedDirective} ${event.blockedURI}`);
      });
    });
    await page.reload();
    await expect(page).toHaveURL('app://bundle/index.html');

    // The office renders from the renderer's own assets: no runtime message carries sprites.
    await expect(page.getByText('Loading...')).toHaveCount(0);
    await expect(page.locator('canvas').first()).toBeVisible();
    await expect.poll(() => officeCanvasColorCount(page)).toBeGreaterThan(8);

    // The connection badge renders nothing once the WebSocket is open, and the
    // runtime opens it only for its own token and an allowed Origin.
    await expectOfficeConnected(page);

    // Upstream entry points that have no desktop meaning are hidden.
    await expect(page.getByRole('button', { name: '+ Agent' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Layout' })).toHaveCount(0);

    // The page sees exactly the two bridge functions and no Node API.
    expect(
      await page.evaluate(() => Object.keys((window as unknown as BridgeWindow).agentsRoom).sort()),
    ).toEqual(['getRuntimeEndpoint', 'pickProjectFolder']);
    expect(await page.evaluate(() => typeof (globalThis as { require?: unknown }).require)).toBe(
      'undefined',
    );
    expect(await page.evaluate(() => typeof (globalThis as { process?: unknown }).process)).toBe(
      'undefined',
    );

    const endpoint: RuntimeEndpoint = await page.evaluate(() =>
      (window as unknown as BridgeWindow).agentsRoom.getRuntimeEndpoint(),
    );
    expect(endpoint.url).toMatch(/^ws:\/\/127\.0\.0\.1:\d+\/ws$/);
    expect(Buffer.from(endpoint.token, 'base64url')).toHaveLength(32);

    expect(await page.evaluate(() => (window as unknown as BridgeWindow).__cspViolations)).toEqual(
      [],
    );

    // runtime.log keeps the runtime's stderr, with the token masked.
    await expect
      .poll(() => (fs.existsSync(runtimeLogPath) ? fs.readFileSync(runtimeLogPath, 'utf8') : ''))
      .toContain('token=***');
    expect(fs.readFileSync(runtimeLogPath, 'utf8')).not.toContain(endpoint.token);

    // Still connected after all of the above.
    await expect(page.getByText('Reconnecting…')).toHaveCount(0);
  });

  test('every app://bundle response carries the CSP, and a missing path cannot be framed @area:desktop-shell', async ({
    desktop,
  }) => {
    const { window: page } = await desktop.launch();
    await expectOfficeConnected(page);

    const responses = await page.evaluate(
      async (urls) => {
        const out: Record<string, { status: number; csp: string | null; nosniff: string | null }> =
          {};
        for (const url of urls) {
          const res = await fetch(url);
          out[url] = {
            status: res.status,
            csp: res.headers.get('content-security-policy'),
            nosniff: res.headers.get('x-content-type-options'),
          };
        }
        return out;
      },
      [PAGE_URL, MISSING_URL, ESCAPING_URL],
    );
    const pageCsp = responses[PAGE_URL].csp;
    expect(pageCsp).toContain("script-src 'self'");
    expect(pageCsp).toContain("frame-ancestors 'none'");
    // An error response is a document on the renderer's origin too: same policy.
    expect(responses[MISSING_URL]).toEqual({ status: 404, csp: pageCsp, nosniff: 'nosniff' });
    expect(responses[ESCAPING_URL]).toEqual({ status: 400, csp: pageCsp, nosniff: 'nosniff' });

    // A same-origin frame on a missing path would be a policy-free document to
    // script. frame-ancestors refuses it: the frame holds an opaque error page.
    const framed = await page.evaluate(async (url) => {
      const frame = document.createElement('iframe');
      const loaded = new Promise((resolve) => frame.addEventListener('load', resolve));
      frame.src = url;
      document.body.appendChild(frame);
      await loaded;
      try {
        return frame.contentWindow?.location.href ?? null;
      } catch {
        return 'cross-origin';
      }
    }, MISSING_URL);
    expect(framed).toBe('cross-origin');
  });

  test('the Settings modal lists only what works in the desktop shell @area:desktop-shell', async ({
    desktop,
  }) => {
    const { window: page } = await desktop.launch();
    await expectOfficeConnected(page);

    await page.getByRole('button', { name: 'Settings', exact: true }).click();

    // A checkbox's accessible name also holds its "x" mark, so labels are matched as text.
    for (const label of ['Sound Notifications', 'Always Show Labels', 'Debug View']) {
      await expect(page.getByText(label, { exact: true })).toBeVisible();
    }
    // Entries only the VS Code host or the upstream server answers.
    for (const label of [
      'Open Sessions Folder',
      'Export Layout',
      'Import Layout',
      'Add Asset Directory',
      'Watch All Sessions',
      'Instant Detection (Hooks)',
      'Display Headless as Ghosts',
    ]) {
      await expect(page.getByText(label, { exact: true })).toHaveCount(0);
    }
    await expect(page.getByPlaceholder('Absolute asset directory path')).toHaveCount(0);
  });

  test('a runtime that refuses the handshake leaves the office up and the renderer reconnecting @area:desktop-shell', async ({
    desktop,
  }) => {
    const { window: page } = await desktop.launch({ runtime: { rejectAll: true } });

    await expect(page.locator('canvas').first()).toBeVisible();
    await expect(page.getByText('Reconnecting…')).toBeVisible();
  });

  test('cancelling the folder picker returns null and leaves the screen as it was @area:desktop-shell', async ({
    desktop,
  }) => {
    const { app, window: page, tmpBase } = await desktop.launch();
    await expectOfficeConnected(page);

    await app.evaluate(({ dialog }) => {
      const calls: unknown[] = [];
      (globalThis as unknown as { __openDialogCalls: unknown[] }).__openDialogCalls = calls;
      dialog.showOpenDialog = (async (...args: unknown[]) => {
        calls.push(args[args.length - 1]);
        return { canceled: true, filePaths: [] };
      }) as typeof dialog.showOpenDialog;
    });
    const textBefore = await page.locator('#root').innerText();

    const picked = await page.evaluate(() =>
      (window as unknown as BridgeWindow).agentsRoom.pickProjectFolder(),
    );

    expect(picked).toBeNull();
    const calls = await app.evaluate(
      () => (globalThis as unknown as { __openDialogCalls: unknown[] }).__openDialogCalls,
    );
    expect(calls).toEqual([{ properties: ['openDirectory'] }]);
    await expect(page).toHaveURL('app://bundle/index.html');
    expect(await page.locator('#root').innerText()).toBe(textBefore);
    await expectOfficeConnected(page);

    // A chosen folder comes back as its path.
    await app.evaluate(({ dialog }, folder) => {
      dialog.showOpenDialog = (async () => ({
        canceled: false,
        filePaths: [folder],
      })) as typeof dialog.showOpenDialog;
    }, tmpBase);
    expect(
      await page.evaluate(() => (window as unknown as BridgeWindow).agentsRoom.pickProjectFolder()),
    ).toBe(tmpBase);
  });

  test('the renderer cannot open windows or navigate away from app://bundle @area:desktop-shell', async ({
    desktop,
  }) => {
    const { app, window: page } = await desktop.launch();
    await expectOfficeConnected(page);

    expect(await page.evaluate(() => window.open('http://127.0.0.1:9/') === null)).toBe(true);
    await page.evaluate(() => {
      window.location.href = 'http://127.0.0.1:9/';
    });
    // Negative check: give a navigation the time it would need, then look.
    // Locators are not used from here on: after a blocked navigation Playwright
    // keeps waiting for a commit that never comes. The page and the main
    // process report the location themselves.
    await page.waitForTimeout(1_000);

    expect(await page.evaluate(() => window.location.href)).toBe('app://bundle/index.html');
    expect(await page.evaluate(() => document.querySelector('canvas') !== null)).toBe(true);
    expect(
      await app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows().map((win) => win.webContents.getURL()),
      ),
    ).toEqual(['app://bundle/index.html']);
  });
});
