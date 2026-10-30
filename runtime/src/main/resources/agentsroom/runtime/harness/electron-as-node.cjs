'use strict';
/*
 * agents-room runtime: loaded with `--require` into an ACP adapter that runs on the desktop shell's
 * Electron executable as Node (ADR 0003 decision 12). The runtime writes this file into its data
 * directory and starts the adapter as `<electron> --require <this file> <adapter> ...` with
 * ELECTRON_RUN_AS_NODE=1.
 *
 * ELECTRON_RUN_AS_NODE=1 is meant for this one process. Inherited, it would make every Electron app a
 * command of the agent starts (VS Code, this repo's shell, ...) run as plain Node instead. So:
 *   - the variable is removed from this process's environment before the adapter's code runs;
 *   - a child this process starts on its own executable (process.execPath, as codex-acp starts Codex
 *     and Node's fork does) is still Node: it gets the variable back and this file again, which then
 *     removes the variable there too.
 * Every other child, and everything those start, sees no ELECTRON_RUN_AS_NODE. The wrapped functions
 * behave as before otherwise, util.promisify(execFile) included.
 */
const VARIABLE = 'ELECTRON_RUN_AS_NODE';

if (process.env[VARIABLE] !== undefined) {
  delete process.env[VARIABLE];

  const childProcess = require('node:child_process');
  const path = require('node:path');
  const { syncBuiltinESMExports } = require('node:module');

  const preload = __filename;
  const windows = process.platform === 'win32';
  const normalized = (file) => {
    const resolved = path.resolve(file);
    return windows ? resolved.toLowerCase() : resolved;
  };
  const self = normalized(process.execPath);
  const isSelf = (file) => typeof file === 'string' && normalized(file) === self;
  const asNode = (options) => ({
    ...options,
    env: { ...((options && options.env) || process.env), [VARIABLE]: '1' },
  });

  /** The (args?, options?, ...callback) after the file of spawn, spawnSync, execFile, execFileSync, fork. */
  const parse = (rest) => {
    let at = 0;
    let args = [];
    if (Array.isArray(rest[0])) {
      args = rest[0];
      at = 1;
    } else if (rest[0] === undefined || rest[0] === null) {
      at = rest.length > 0 ? 1 : 0;
    }
    let options;
    if (rest[at] !== null && typeof rest[at] === 'object') {
      options = rest[at];
      at++;
    } else if (rest[at] === undefined || rest[at] === null) {
      if (at < rest.length && typeof rest[at] !== 'function') at++;
    }
    return { args, options, tail: rest.slice(at) };
  };

  const onSelf = (name) => {
    const original = childProcess[name];
    childProcess[name] = function (file, ...rest) {
      if (!isSelf(file)) return original.call(this, file, ...rest);
      const { args, options, tail } = parse(rest);
      return original.call(this, file, ['--require', preload, ...args], asNode(options), ...tail);
    };
  };
  ['spawn', 'spawnSync', 'execFile', 'execFileSync'].forEach(onSelf);

  // util.promisify(execFile) resolves {stdout, stderr} through execFile's own promisify.custom, which
  // a plain wrapper lacks: give the wrapper one in the same shape (Node's lib/child_process.js).
  const { promisify } = require('node:util');
  const wrappedExecFile = childProcess.execFile;
  Object.defineProperty(wrappedExecFile, promisify.custom, {
    enumerable: false,
    value: (...args) => {
      let child;
      const promise = new Promise((resolve, reject) => {
        child = wrappedExecFile(...args, (error, stdout, stderr) => {
          if (error) {
            error.stdout = stdout;
            error.stderr = stderr;
            reject(error);
          } else {
            resolve({ stdout, stderr });
          }
        });
      });
      promise.child = child;
      return promise;
    },
  });

  const { fork } = childProcess;
  childProcess.fork = function (modulePath, ...rest) {
    const { args, options, tail } = parse(rest);
    if (options && options.execPath !== undefined && !isSelf(options.execPath)) {
      return fork.call(this, modulePath, ...rest);
    }
    const execArgv = options && options.execArgv ? ['--require', preload, ...options.execArgv] : undefined;
    const forked = asNode(options);
    if (execArgv) forked.execArgv = execArgv;
    return fork.call(this, modulePath, args, forked, ...tail);
  };

  // `import { spawn } from 'node:child_process'` in an ES module sees the wrapped functions too.
  syncBuiltinESMExports();
}
