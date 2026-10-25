#!/usr/bin/env node
/**
 * Fake local runtime for desktop-shell e2e tests ONLY.
 *
 * It speaks the shell side of the shared handshake and nothing else, so the
 * shell (window, preload, supervisor) is tested without a JVM:
 *   - reads AGENTS_ROOM_TOKEN and AGENTS_ROOM_ALLOWED_ORIGINS from the env,
 *   - binds 127.0.0.1 on an ephemeral port and prints exactly one stdout line,
 *     `AGENTS_ROOM_READY {"port":N}\n`,
 *   - gates `/ws`: missing or wrong `?token=` → 401 before the upgrade
 *     (constant-time compare), an Origin outside the allowlist → 403,
 *   - exits on stdin EOF, ending its child process first, after a short
 *     cleanup period (as the real runtime ends its adapters).
 * It sends no protocol messages (the contract is not wired yet), and it is
 * not a model of the Java runtime beyond the handshake.
 *
 * Flags:
 *   --mode=ready|silent|exit   ready (default) | never print the ready line | exit before ready
 *   --exit-code=N              exit code for --mode=exit (default 3)
 *   --gate=FILE                --mode=exit waits until FILE exists, then exits
 *   --reject-all               answer every /ws upgrade with 401 (a runtime the renderer can't join)
 *   --ignore-stdin-eof         keep running after stdin closes (a hung runtime)
 *   --spawn-child              start a long-lived child process (a stand-in for an adapter). Like a
 *                              JVM ProcessBuilder child it is in no Windows job object, so only an
 *                              explicit kill (this fake on EOF, or the shell's tree kill) ends it
 *   --state-dir=DIR            write DIR/fake-runtime-<pid>.json = {pid, childPid}
 *
 * stderr deliberately carries the token, so the tests can check that the
 * shell masks it in runtime.log. A real runtime must never log its token.
 */
'use strict';

const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const GATE_POLL_MS = 100;
/**
 * On stdin EOF, how long the fake waits before ending its child. The real
 * runtime also takes time here (it closes its adapters' stdin and waits before
 * killing them), and a runtime the OS kills together with the shell never gets
 * this far: its child is left running, which is what the specs look for.
 */
const EOF_CLEANUP_MS = 500;
const OPCODE_CLOSE = 0x8;

function parseArgs(argv) {
  const opts = {
    mode: 'ready',
    exitCode: 3,
    gate: null,
    rejectAll: false,
    ignoreStdinEof: false,
    spawnChild: false,
    stateDir: null,
  };
  for (const arg of argv) {
    const [key, value] = arg.split(/=(.*)/s);
    if (key === '--mode') opts.mode = value;
    else if (key === '--exit-code') opts.exitCode = Number(value);
    else if (key === '--gate') opts.gate = value;
    else if (key === '--reject-all') opts.rejectAll = true;
    else if (key === '--ignore-stdin-eof') opts.ignoreStdinEof = true;
    else if (key === '--spawn-child') opts.spawnChild = true;
    else if (key === '--state-dir') opts.stateDir = value;
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const token = process.env.AGENTS_ROOM_TOKEN ?? '';
const allowedOrigins = (process.env.AGENTS_ROOM_ALLOWED_ORIGINS ?? '')
  .split(',')
  .map((origin) => origin.trim())
  .filter((origin) => origin !== '');

function logErr(message) {
  process.stderr.write(`[fake-runtime ${process.pid}] ${message}\n`);
}

function writeState(extra) {
  if (!opts.stateDir) return;
  fs.mkdirSync(opts.stateDir, { recursive: true });
  const file = path.join(opts.stateDir, `fake-runtime-${process.pid}.json`);
  fs.writeFileSync(file, JSON.stringify({ pid: process.pid, ...extra }));
}

function tokenMatches(candidate) {
  if (typeof candidate !== 'string' || token === '') return false;
  const a = Buffer.from(candidate);
  const b = Buffer.from(token);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function reject(socket, status, reason) {
  socket.end(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

/** Server→client close frame (unmasked, empty payload). */
function closeFrame() {
  return Buffer.from([0x80 | OPCODE_CLOSE, 0x00]);
}

function onUpgrade(req, socket) {
  socket.on('error', () => undefined);
  const url = new URL(req.url ?? '/', 'http://127.0.0.1');
  if (url.pathname !== '/ws') return reject(socket, 404, 'Not Found');
  if (opts.rejectAll || !tokenMatches(url.searchParams.get('token'))) {
    logErr('rejected a /ws handshake: bad token');
    return reject(socket, 401, 'Unauthorized');
  }
  const origin = req.headers.origin;
  if (origin !== undefined && !allowedOrigins.includes(origin)) {
    logErr(`rejected a /ws handshake: origin ${origin}`);
    return reject(socket, 403, 'Forbidden');
  }
  const key = req.headers['sec-websocket-key'];
  if (typeof key !== 'string') return reject(socket, 400, 'Bad Request');
  const accept = crypto
    .createHash('sha1')
    .update(key + WS_GUID)
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );
  logErr(`accepted a /ws connection from ${origin ?? '(no origin)'}`);
  // Client frames are read only to answer a close; their payloads are ignored.
  socket.on('data', (data) => {
    if (data.length > 0 && (data[0] & 0x0f) === OPCODE_CLOSE) socket.end(closeFrame());
  });
}

function startServer() {
  const server = http.createServer((_req, res) => {
    res.writeHead(404);
    res.end();
  });
  server.on('upgrade', onUpgrade);
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address();
    logErr(`listening on ws://127.0.0.1:${port}/ws?token=${token}`);
    process.stdout.write(`AGENTS_ROOM_READY ${JSON.stringify({ port })}\n`);
  });
}

function main() {
  // Once the shell is gone, writes to its pipes fail (EPIPE). Like the real
  // runtime (Java's PrintStream swallows them), keep going and clean up.
  process.stdout.on('error', () => undefined);
  process.stderr.on('error', () => undefined);
  logErr(`started with AGENTS_ROOM_TOKEN=${token}`);

  let child = null;
  if (opts.spawnChild) {
    child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 60000)'], {
      stdio: 'ignore',
      windowsHide: true,
      // Windows: outside this Node process's kill-on-close job, as a JVM's child is.
      // POSIX: in this process's group, as a JVM's child is, so a group kill reaches it.
      detached: process.platform === 'win32',
    });
    child.unref();
  }
  writeState({ childPid: child?.pid ?? null });

  process.stdin.on('end', () => {
    if (opts.ignoreStdinEof) {
      logErr('stdin closed; ignoring it (--ignore-stdin-eof)');
      return;
    }
    if (!child) {
      logErr('stdin closed; exiting');
      return process.exit(0);
    }
    logErr(`stdin closed; ending child ${child.pid} in ${EOF_CLEANUP_MS} ms`);
    setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      process.exit(0);
    }, EOF_CLEANUP_MS);
  });
  process.stdin.resume();

  if (opts.mode === 'exit') {
    const exitNow = () => {
      logErr(`exiting before ready with code ${opts.exitCode}`);
      process.exit(opts.exitCode);
    };
    if (!opts.gate) return exitNow();
    const timer = setInterval(() => {
      if (fs.existsSync(opts.gate)) {
        clearInterval(timer);
        exitNow();
      }
    }, GATE_POLL_MS);
    return;
  }
  if (opts.mode === 'silent') {
    logErr('never printing the ready line (--mode=silent)');
    setInterval(() => undefined, 60_000);
    return;
  }
  startServer();
}

main();
