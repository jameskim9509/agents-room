import * as fs from 'fs';
import * as path from 'path';

import {
  CONSOLE_RUNTIME_PREFIX,
  LOG_SHELL_PREFIX,
  RUNTIME_LOG_FILE,
  RUNTIME_LOG_PREVIOUS_FILE,
  runtimeSessionLogFile,
} from './constants.js';
import { maskSecrets } from './logMask.js';

/** Rotates `runtime.log` to `runtime.previous.log`. A failed rotation is logged and skipped. */
function rotate(current: string, previous: string): void {
  if (!fs.existsSync(current)) return;
  try {
    fs.renameSync(current, previous);
  } catch (err) {
    // E.g. a log viewer holds the file open without delete sharing (Windows).
    console.error(`${CONSOLE_RUNTIME_PREFIX} cannot rotate ${current}; appending to it`, err);
  }
}

/**
 * Opens `filePath` for appending, synchronously, so a failure is known here.
 * Only a regular file will do: Windows opens a directory for appending too.
 */
function openForAppend(filePath: string): fs.WriteStream | null {
  let fd: number | null = null;
  try {
    fd = fs.openSync(filePath, 'a');
    if (!fs.fstatSync(fd).isFile()) throw new Error(`${filePath} is not a regular file`);
    return fs.createWriteStream(filePath, { fd, encoding: 'utf8' });
  } catch (err) {
    if (fd !== null) fs.closeSync(fd);
    console.error(`${CONSOLE_RUNTIME_PREFIX} cannot open ${filePath}`, err);
    return null;
  }
}

/**
 * `<userData>/logs/runtime.log`: the runtime's stderr and stray stdout, plus
 * the shell's own supervision lines, with every token masked. The previous
 * app session's log is kept once as `runtime.previous.log`. Restarts within a
 * session append to the same file.
 *
 * When the rotation fails, this session appends to `runtime.log`. When even
 * that cannot be opened, it writes `runtime-<pid>.log` instead, and `path`
 * (the one the failure dialog shows) names that file. A log that cannot be
 * opened at all degrades to console output only; it never stops the runtime
 * from starting.
 */
export class RuntimeLog {
  readonly path: string;
  private stream: fs.WriteStream | null = null;
  private readonly secrets = new Set<string>();

  constructor(logDir: string) {
    const main = path.join(logDir, RUNTIME_LOG_FILE);
    let stream: fs.WriteStream | null = null;
    let logPath = main;
    try {
      fs.mkdirSync(logDir, { recursive: true });
      rotate(main, path.join(logDir, RUNTIME_LOG_PREVIOUS_FILE));
      stream = openForAppend(main);
      if (!stream) {
        logPath = path.join(logDir, runtimeSessionLogFile(process.pid));
        stream = openForAppend(logPath);
      }
    } catch (err) {
      console.error(`${CONSOLE_RUNTIME_PREFIX} cannot create ${logDir}`, err);
    }
    this.path = logPath;
    this.stream = stream;
    this.stream?.on('error', (err) => {
      console.error(`${CONSOLE_RUNTIME_PREFIX} ${logPath} write failed`, err);
      this.stream = null;
    });
  }

  /** Every token handed to a runtime this session is masked in every later line. */
  addSecret(secret: string): void {
    this.secrets.add(secret);
  }

  /** One line from the runtime, written as-is apart from masking. */
  write(line: string): void {
    const masked = maskSecrets(line, this.secrets);
    this.stream?.write(`${masked}\n`);
    console.error(`${CONSOLE_RUNTIME_PREFIX} ${masked}`);
  }

  /** One line from the shell itself, timestamped. */
  shell(message: string): void {
    this.write(`${new Date().toISOString()} ${LOG_SHELL_PREFIX} ${message}`);
  }

  close(): Promise<void> {
    const stream = this.stream;
    this.stream = null;
    if (!stream) return Promise.resolve();
    return new Promise((resolve) => stream.end(() => resolve()));
  }
}
