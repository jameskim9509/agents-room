// Runs the runtime's Gradle wrapper from the repo root: `node scripts/gradle.mjs <gradle args...>`.
// Windows cannot spawn gradlew.bat directly, so it goes through cmd.exe. The `.\` prefix keeps it
// working when NoDefaultCurrentDirectoryInExePath is set. Elsewhere `sh gradlew` works even when
// the executable bit is missing.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const runtimeDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'runtime');
const args = process.argv.slice(2);
const [command, commandArgs] =
  process.platform === 'win32'
    ? ['cmd.exe', ['/d', '/c', '.\\gradlew.bat', ...args]]
    : ['sh', ['gradlew', ...args]];

const result = spawnSync(command, commandArgs, { cwd: runtimeDir, stdio: 'inherit' });
if (result.error) {
  throw result.error;
}
process.exit(result.status ?? 1);
