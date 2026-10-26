/**
 * RuntimeLog: rotation of the previous session's log, and the fallbacks when
 * the rotation or the log itself is blocked. Directories stand in for the
 * Windows case (a viewer holding runtime.log open without delete sharing),
 * because they make the same calls fail on every platform.
 */
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { afterEach, describe, expect, it } from 'vitest';

import { RuntimeLog } from '../src/runtimeLog.js';

const dirs: string[] = [];
afterEach(() => {
  for (const dir of dirs.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
});

function logDir(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-runtime-log-'));
  dirs.push(dir);
  return path.join(dir, 'logs');
}

async function writeAndClose(log: RuntimeLog, line: string): Promise<void> {
  log.write(line);
  await log.close();
}

describe('RuntimeLog', () => {
  it('keeps the previous session once as runtime.previous.log', async () => {
    const dir = logDir();
    await writeAndClose(new RuntimeLog(dir), 'session 1');
    await writeAndClose(new RuntimeLog(dir), 'session 2');
    const log = new RuntimeLog(dir);
    await writeAndClose(log, 'session 3');

    expect(log.path).toBe(path.join(dir, 'runtime.log'));
    expect(fs.readFileSync(path.join(dir, 'runtime.log'), 'utf8')).toBe('session 3\n');
    expect(fs.readFileSync(path.join(dir, 'runtime.previous.log'), 'utf8')).toBe('session 2\n');
  });

  it('appends to runtime.log when the rotation fails', async () => {
    const dir = logDir();
    await writeAndClose(new RuntimeLog(dir), 'session 1');
    // A non-empty directory where the rotation would rename to: the rename throws.
    fs.mkdirSync(path.join(dir, 'runtime.previous.log', 'blocker'), { recursive: true });

    const log = new RuntimeLog(dir);
    await writeAndClose(log, 'session 2');

    expect(log.path).toBe(path.join(dir, 'runtime.log'));
    expect(fs.readFileSync(log.path, 'utf8')).toBe('session 1\nsession 2\n');
  });

  it('writes a session log of its own, and names it, when runtime.log cannot be opened', async () => {
    const dir = logDir();
    // runtime.log is a directory that can be neither rotated nor opened for appending.
    fs.mkdirSync(path.join(dir, 'runtime.log', 'blocker'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'runtime.previous.log', 'blocker'), { recursive: true });

    const log = new RuntimeLog(dir);
    await writeAndClose(log, 'session 2');

    expect(log.path).toBe(path.join(dir, `runtime-${process.pid}.log`));
    expect(fs.readFileSync(log.path, 'utf8')).toBe('session 2\n');
  });
});
