import { type ChildProcess, execFile } from 'child_process';
import * as path from 'path';

/** `%SystemRoot%\System32\taskkill.exe`, by absolute path so PATH can't substitute another one. */
function taskkillPath(): string {
  const systemRoot = process.env['SystemRoot'] ?? process.env['SYSTEMROOT'] ?? 'C:\\Windows';
  return path.join(systemRoot, 'System32', 'taskkill.exe');
}

/**
 * Forcibly ends a child process and every process it started.
 *
 * - Windows: `taskkill /pid N /T /F`. `child.kill()` would end only the child,
 *   and an orphaned grandchild (the runtime's adapters) would keep running.
 *   The runtime is spawned `detached`, so no job object ends it for us either.
 *   taskkill prints in the console code page, so its output is not parsed.
 * - Elsewhere: the child was spawned `detached`, so it leads its own process
 *   group, and `kill(-pid)` signals the whole group.
 *
 * Resolves once the kill was issued; the caller waits for the exit event.
 */
export function killProcessTree(child: ChildProcess): Promise<void> {
  const pid = child.pid;
  if (pid === undefined || child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve();
  }
  if (process.platform === 'win32') {
    return new Promise((resolve) => {
      execFile(taskkillPath(), ['/pid', String(pid), '/T', '/F'], { windowsHide: true }, () =>
        resolve(),
      );
    });
  }
  try {
    process.kill(-pid, 'SIGKILL');
  } catch {
    child.kill('SIGKILL');
  }
  return Promise.resolve();
}
