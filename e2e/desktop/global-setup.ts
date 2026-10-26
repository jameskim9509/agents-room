import fs from 'fs';
import path from 'path';

const REPO_ROOT = path.resolve(__dirname, '..', '..');

/** The desktop suite runs the built shell and renderer. Fail fast with the fix when either is missing. */
export default function desktopGlobalSetup(): void {
  const required = [
    path.join(REPO_ROOT, 'desktop', 'dist', 'main.js'),
    path.join(REPO_ROOT, 'desktop', 'dist', 'preload.js'),
    path.join(REPO_ROOT, 'dist', 'webview', 'index.html'),
  ];
  const missing = required.filter((file) => !fs.existsSync(file));
  if (missing.length > 0) {
    throw new Error(
      `Desktop e2e needs a build. Missing:\n  ${missing.join('\n  ')}\nRun: npm run desktop:build`,
    );
  }
}
