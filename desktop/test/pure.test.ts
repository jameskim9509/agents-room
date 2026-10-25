/**
 * Unit tests for the shell's pure helpers: app:// path mapping and response
 * headers, the ready line, log masking, origin checks, the dev-only env gate,
 * launch-command resolution, dialog copy.
 */
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { afterEach, describe, expect, it } from 'vitest';

import { appErrorResponse, appFileResponse } from '../src/appResponse.js';
import { resolveBundlePath } from '../src/bundlePath.js';
import { APP_CSP, DEV_ONLY_ENV } from '../src/constants.js';
import { readDevOverrides } from '../src/devOverrides.js';
import { maskSecrets } from '../src/logMask.js';
import { LineSplitter, parseReadyLine } from '../src/readyLine.js';
import { isAllowedRendererUrl, urlOrigin } from '../src/rendererOrigin.js';
import { resolveRuntimeCommand, RuntimeCommandError } from '../src/runtimeCommand.js';
import { failureDialogOptions } from '../src/runtimeFailure.js';

const ROOT = path.resolve(os.tmpdir(), 'bundle-root');

describe('resolveBundlePath', () => {
  it('maps app://bundle URLs under the root, with index.html for the root', () => {
    expect(resolveBundlePath(ROOT, 'app://bundle/')).toBe(path.join(ROOT, 'index.html'));
    expect(resolveBundlePath(ROOT, 'app://bundle')).toBe(path.join(ROOT, 'index.html'));
    expect(resolveBundlePath(ROOT, 'app://bundle/assets/a%20b.json')).toBe(
      path.join(ROOT, 'assets', 'a b.json'),
    );
  });

  it('refuses other hosts and schemes', () => {
    expect(resolveBundlePath(ROOT, 'app://other/index.html')).toBeNull();
    expect(resolveBundlePath(ROOT, 'file:///index.html')).toBeNull();
    expect(resolveBundlePath(ROOT, 'not a url')).toBeNull();
  });

  it('refuses every way out of the root', () => {
    for (const url of [
      'app://bundle/a/..%2f..%2fsecret',
      'app://bundle/%5c..%5csecret',
      'app://bundle/..%5c..%5csecret',
      'app://bundle/C:%5cWindows%5cwin.ini',
      'app://bundle/index.html:stream',
      'app://bundle/a%00b',
      'app://bundle/%E0%A4%A',
    ]) {
      expect(resolveBundlePath(ROOT, url), url).toBeNull();
    }
  });
});

describe('app:// responses', () => {
  it('put the CSP and nosniff on error responses too', async () => {
    for (const status of [400, 404] as const) {
      const res = appErrorResponse(status);
      expect(res.status).toBe(status);
      expect(res.headers.get('content-security-policy')).toBe(APP_CSP);
      expect(res.headers.get('x-content-type-options')).toBe('nosniff');
      expect(res.headers.get('content-type')).toMatch(/^text\/plain/);
      expect(await res.text()).not.toBe('');
    }
  });

  it('keep a served file and its headers, with the security headers set over them', async () => {
    const file = new Response('body', {
      status: 200,
      headers: { 'Content-Type': 'text/html', 'Content-Security-Policy': 'default-src *' },
    });
    const res = appFileResponse(file);
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toBe('text/html');
    expect(res.headers.get('content-security-policy')).toBe(APP_CSP);
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
    expect(await res.text()).toBe('body');
  });
});

describe('ready line', () => {
  it('reads the port from AGENTS_ROOM_READY {"port":N}, with or without \\r', () => {
    expect(parseReadyLine('AGENTS_ROOM_READY {"port":51234}')).toBe(51234);
    expect(parseReadyLine('AGENTS_ROOM_READY {"port":51234}\r')).toBe(51234);
  });

  it('rejects anything else', () => {
    for (const line of [
      '',
      'AGENTS_ROOM_READY',
      'AGENTS_ROOM_READY {"port":0}',
      'AGENTS_ROOM_READY {"port":65536}',
      'AGENTS_ROOM_READY {"port":1.5}',
      'AGENTS_ROOM_READY {"port":"80"}',
      'AGENTS_ROOM_READY not-json',
      'AGENTS_ROOM_READY null',
      ' AGENTS_ROOM_READY {"port":80}',
      'Started Application in 1.2 s',
    ]) {
      expect(parseReadyLine(line), line).toBeNull();
    }
  });

  it('splits chunks into lines, carrying a partial line and stripping \\r', () => {
    const splitter = new LineSplitter();
    expect(splitter.push('AGENTS_ROOM_')).toEqual([]);
    expect(splitter.push('READY {"port":1}\r\nnext')).toEqual(['AGENTS_ROOM_READY {"port":1}']);
    expect(splitter.push(' line\n')).toEqual(['next line']);
    expect(splitter.push('tail\r')).toEqual([]);
    expect(splitter.flush()).toBe('tail');
    expect(splitter.flush()).toBeNull();
  });
});

describe('maskSecrets', () => {
  it('masks known tokens and any token= value', () => {
    expect(maskSecrets('AGENTS_ROOM_TOKEN=abc123 then abc123', ['abc123'])).toBe(
      'AGENTS_ROOM_TOKEN=*** then ***',
    );
    expect(maskSecrets('GET /ws?token=zzz&x=1 and Token=yyy', [])).toBe(
      'GET /ws?token=***&x=1 and Token=***',
    );
    expect(maskSecrets('nothing secret', ['', 'abc'])).toBe('nothing secret');
  });
});

describe('renderer origin', () => {
  it('assembles origins for custom and http schemes', () => {
    expect(urlOrigin('app://bundle/index.html')).toBe('app://bundle');
    expect(urlOrigin('http://localhost:5173/src/main.tsx')).toBe('http://localhost:5173');
    expect(urlOrigin('file:///C:/index.html')).toBeNull();
    expect(urlOrigin('about:blank')).toBeNull();
    expect(urlOrigin('garbage')).toBeNull();
  });

  it('allows only the listed origins', () => {
    const allowed = ['app://bundle'];
    expect(isAllowedRendererUrl('app://bundle/index.html', allowed)).toBe(true);
    expect(isAllowedRendererUrl('app://bundle.evil/index.html', allowed)).toBe(false);
    expect(isAllowedRendererUrl('http://localhost:5173/', allowed)).toBe(false);
    expect(isAllowedRendererUrl('http://127.0.0.1:9/', allowed)).toBe(false);
  });
});

describe('readDevOverrides', () => {
  const env: NodeJS.ProcessEnv = {
    AGENTS_ROOM_RUNTIME_COMMAND: '["evil.exe"]',
    AGENTS_ROOM_READY_TIMEOUT_MS: '1000',
    AGENTS_ROOM_USER_DATA_DIR: 'C:\\elsewhere',
    AGENTS_ROOM_RENDERER_URL: 'http://localhost:5173/',
    AGENTS_ROOM_TOKEN: 'not-a-dev-knob',
    PATH: 'C:\\bin',
  };

  it('honours every dev-only knob when unpackaged, and nothing else', () => {
    const { values, ignored } = readDevOverrides(env, false);
    expect(values).toEqual({
      AGENTS_ROOM_RUNTIME_COMMAND: '["evil.exe"]',
      AGENTS_ROOM_READY_TIMEOUT_MS: '1000',
      AGENTS_ROOM_USER_DATA_DIR: 'C:\\elsewhere',
      AGENTS_ROOM_RENDERER_URL: 'http://localhost:5173/',
    });
    expect(ignored).toEqual([]);
  });

  it('honours none of them when packaged, and names the ones it ignored', () => {
    const { values, ignored } = readDevOverrides(env, true);
    expect(values).toEqual({});
    expect([...ignored].sort()).toEqual([...DEV_ONLY_ENV].sort());
  });

  it('treats an empty value as unset', () => {
    expect(readDevOverrides({ AGENTS_ROOM_RUNTIME_COMMAND: '' }, true)).toEqual({
      values: {},
      ignored: [],
    });
  });
});

describe('resolveRuntimeCommand', () => {
  const tmpDirs: string[] = [];
  function writeDescriptor(content: string): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-descriptor-'));
    tmpDirs.push(dir);
    const file = path.join(dir, 'dev-runtime.json');
    fs.writeFileSync(file, content);
    return file;
  }
  afterEach(() => {
    for (const dir of tmpDirs.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
  });

  it('uses the AGENTS_ROOM_RUNTIME_COMMAND override verbatim when set', () => {
    const override = '["node","fake.cjs","--mode=ready"]';
    expect(resolveRuntimeCommand(override, null)).toEqual(['node', 'fake.cjs', '--mode=ready']);
  });

  it('rejects an override that is not a JSON array of strings', () => {
    for (const raw of ['node fake.cjs', '[]', '["node", 1]', '{"argv":["node"]}', '[""]']) {
      expect(() => resolveRuntimeCommand(raw, null), raw).toThrow(
        expect.objectContaining({ problem: 'invalid' }),
      );
    }
  });

  it('treats a blank override as unset', () => {
    expect(() => resolveRuntimeCommand('  ', null)).toThrow(
      expect.objectContaining({ problem: 'missing' }),
    );
  });

  it('launches the descriptor jar with the UTF-8 JVM flags', () => {
    const java = path.resolve(os.tmpdir(), 'jdk', 'bin', 'java.exe');
    const jar = path.resolve(os.tmpdir(), 'runtime.jar');
    const file = writeDescriptor(JSON.stringify({ java, jar }));
    expect(resolveRuntimeCommand(undefined, file)).toEqual([
      java,
      '-Dstdout.encoding=UTF-8',
      '-Dstderr.encoding=UTF-8',
      '-Dfile.encoding=UTF-8',
      '-jar',
      jar,
    ]);
  });

  it('reports a missing or broken descriptor', () => {
    const missing = path.join(os.tmpdir(), 'no-such-dir', 'dev-runtime.json');
    expect(() => resolveRuntimeCommand(undefined, missing)).toThrow(
      expect.objectContaining({ problem: 'missing' }),
    );
    expect(() => resolveRuntimeCommand(undefined, null)).toThrow(RuntimeCommandError);
    expect(() => resolveRuntimeCommand(undefined, writeDescriptor('{'))).toThrow(
      expect.objectContaining({ problem: 'invalid' }),
    );
    expect(() =>
      resolveRuntimeCommand(undefined, writeDescriptor('{"java":"java","jar":"runtime.jar"}')),
    ).toThrow(expect.objectContaining({ problem: 'invalid' }));
  });
});

describe('failureDialogOptions', () => {
  it('says what failed, where the log is, and offers 다시 시작 / 종료', () => {
    const options = failureDialogOptions(
      { kind: 'exit-before-ready', exitCode: 3, signal: null },
      'C:\\logs\\runtime.log',
    );
    expect(options.message).toBe('로컬 런타임을 시작하지 못했습니다');
    expect(options.buttons).toEqual(['다시 시작', '종료']);
    expect(options.detail).toBe(
      '런타임 프로세스가 준비 전에 종료되었습니다. (종료 코드 3)\n로그: C:\\logs\\runtime.log',
    );
  });
});
