#!/usr/bin/env node
/**
 * Mock ACP agent for the runtime's integration tests and the desktop e2e ONLY.
 *
 * It stands in for codex-acp at the process boundary (e2e/README.md, "Mocking
 * model & rules"): the runtime starts it as the Codex adapter through
 * AGENTS_ROOM_HARNESS_CODEX_COMMAND and talks to it exactly as to the real one,
 * ACP v1 JSON-RPC 2.0 over stdio, one JSON message per line. It has no
 * dependencies, never opens a browser and never reaches the network.
 *
 * What it does is a scenario: a built-in one by name, or a JSON file in the same
 * shape (keys below), which may start from a built-in with "extends".
 *   node mock-acp-agent.cjs [--scenario <name>] [--scenario-file <file.json>]
 * Without a flag it uses $MOCK_ACP_SCENARIO, else "happy". The runtime passes
 * adapters only an allowlisted environment, so tests pick the scenario with the
 * flags (they are part of the command line).
 *
 * Scenario keys (all optional; "happy" fills the rest):
 *   exitAtStart: N             exit with code N before reading anything
 *   stdoutAtStart: "text"      write this line on stdout first (not ACP)
 *   stderrAtStart: ["line"]    write these lines on stderr first
 *   initialize: "answer" | "never" | {error: {code, message}}
 *   protocolVersion: N         the version initialize answers with (1)
 *   agentInfo, authMethods     what initialize reports (codex-acp-like)
 *   advertiseAuthStatus: bool  whether initialize advertises
 *                              agentCapabilities._meta.authStatus (true, as
 *                              codex-acp always does)
 *   authStatus: {kind, label, account?} | null
 *                              pushed as _auth/status_update right after the
 *                              initialize answer; null: no push (codex-acp
 *                              pushes nothing when it cannot read the account)
 *   afterInitialize: [step]    steps right after the initialize answer and the
 *                              status push, before any other request (e.g. a
 *                              crash while a connection check waits)
 *   loggedIn: bool             false: session/new answers -32000 until a
 *                              successful authenticate
 *   authenticate: "succeed" | "fail" | "never" | "succeed-without-login"
 *                              succeed logs in and pushes an "account" status;
 *                              the last answers success but stays logged out
 *   browserStandIn: [executable, ...args]
 *                              every authenticate (however it then ends) first
 *                              "opens the login page" like codex-acp: a launcher
 *                              starts this long-lived command (on Windows
 *                              PowerShell's Start-Process, as codex-acp's `Start`;
 *                              elsewhere a detached spawn), and the sign-in goes
 *                              on once the launcher exited, as codex-acp's `open`
 *                              waits for PowerShell. The command is the browser
 *                              stand-in (the runtime knows a browser by its
 *                              executable's name); the mock never ends it
 *   browserLauncher: "exits" | "stays"
 *                              "stays": the launcher runs on after the start (the
 *                              mock never ends it), so the stand-in stays a
 *                              descendant of the adapter; the sign-in goes on once
 *                              the stand-in started ("exits")
 *   browserLauncherDelayMs: N  the launcher waits N ms before it starts the
 *                              stand-in: a slow or cold PowerShell (0)
 *   pidFile: "file"            appends "<role> <pid>" lines: "adapter" (the mock),
 *                              "startup-child", "browser-launcher", "browser" (as
 *                              soon as the launcher reports it started it) and
 *                              "child" (spawnChild)
 *   childAtStart: true         starts a long-lived child before reading stdin, as
 *                              codex-acp starts Codex's app-server
 *   sessionNew: "answer" | "never" | {error: {code, message}}
 *   updatesBeforeSessionNewAnswer: [update]
 *                              session/updates sent just before the session/new
 *                              answer (an adapter may send its commands that
 *                              early)
 *   prompts: [{steps: [step], stopReason, error}]
 *                              turn i plays prompts[min(i, last)]; "error"
 *                              answers session/prompt with that JSON-RPC error
 *                              after the steps instead of a stop reason
 *   exitOnEofMs: N             delay between stdin EOF and the exit (0)
 *
 * Steps of a turn, in order:
 *   {update: {...}}            a session/update; "${cwd}" in strings becomes the
 *                              session cwd
 *   {delayMs: N}
 *   {permission: {toolCall, options, withdrawAfterMs?}}
 *                              session/request_permission; waits for the answer,
 *                              then reports it as an agent message. With
 *                              withdrawAfterMs the mock withdraws the request
 *                              ($/cancel_request) after that delay, as codex-acp
 *                              does when its turn or app-server goes away
 *   {echo: "cwd" | "env" | "runtime"}
 *                              an agent message with the session cwd and the
 *                              process cwd, the sorted names (never values) of
 *                              the environment variables the mock got, or the
 *                              Node and Electron versions it runs on ("electron
 *                              none" under a plain node)
 *   {spawnChild: true}         starts a long-lived child process and reports its
 *                              pid in an agent message. The mock never ends it,
 *                              like codex.exe leaving git.exe behind: only the
 *                              runtime's process-tree kill does
 *   {probeRunAsNode: true}     reports, as a completed "execute" tool call's
 *                              rawOutput, who sees ELECTRON_RUN_AS_NODE: the mock
 *                              itself, a command it runs, and a child it starts on
 *                              its own executable with its own environment (as
 *                              codex-acp starts Codex: "codex"), with the command
 *                              that child runs; the same child once more through
 *                              util.promisify(execFile), the way codex-acp runs
 *                              its helper commands ("execFileChild"). Each part
 *                              says whether it runs on Electron
 *                              ("electron": the version or null) and was started
 *                              with a --require preload. A child that never
 *                              answers (an Electron app instead of Node) is
 *                              reported as {answered: false}
 *   {waitForCancel: true}      waits for session/cancel; the turn then answers
 *                              "cancelled"
 *   {stderr: "line"}           a line on stderr
 *   {malformed: "text"}        a line on stdout that is not JSON-RPC
 *   {crash: N}                 exits with code N at once
 *   {burst: N}                 N agent messages at once, each its own messageId
 * A session/cancel during a turn ends it at the next step with "cancelled".
 *
 * The mock never decides pass or fail: the tests assert on what the runtime
 * makes of these messages.
 */
'use strict';

const { execFile, spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const readline = require('node:readline');
const { promisify } = require('node:util');

/** The argument that makes this script the probe child of {probeRunAsNode}. */
const PROBE_CHILD = '--probe-run-as-node-child';
const RUN_AS_NODE = 'ELECTRON_RUN_AS_NODE';
/** How long {probeRunAsNode} waits for its child; an Electron app would never answer. */
const PROBE_CHILD_WAIT_MS = 10_000;

const AUTH_REQUIRED = -32000;
const METHOD_NOT_FOUND = -32601;
const INVALID_PARAMS = -32602;

// Fake secrets are built at run time, so that secret scanners do not flag this file.
const FAKE_API_KEY = ['sk', 'mockmockmockmock1234'].join('-');

function textUpdate(sessionUpdate, text, messageId) {
  const update = { sessionUpdate, content: { type: 'text', text } };
  if (messageId) update.messageId = messageId;
  return { update };
}

/**
 * A command that shows a terminal, as codex-acp reports it to a client that declares
 * `_meta.terminal_output`: the tool call names the terminal (its id is the tool call's), each piece of
 * output is a tool_call_update of its own with `_meta.terminal_output: {data, terminal_id}`, and the end
 * carries `terminal_exit`.
 */
function terminalCommand(toolCallId, command) {
  return {
    update: {
      sessionUpdate: 'tool_call',
      toolCallId,
      title: `Run ${command.join(' ')}`,
      kind: 'execute',
      status: 'in_progress',
      rawInput: { command },
      content: [{ type: 'terminal', terminalId: toolCallId }],
      _meta: { terminal_info: { cwd: '${cwd}', terminal_id: toolCallId } },
    },
  };
}

function terminalOutput(toolCallId, data, fields = {}) {
  return {
    update: {
      sessionUpdate: 'tool_call_update',
      toolCallId,
      ...fields,
      _meta: { ...fields._meta, terminal_output: { data, terminal_id: toolCallId } },
    },
  };
}

function terminalExit(toolCallId, exitCode) {
  return {
    update: {
      sessionUpdate: 'tool_call_update',
      toolCallId,
      status: exitCode === 0 ? 'completed' : 'failed',
      _meta: { terminal_exit: { exit_code: exitCode, signal: null, terminal_id: toolCallId } },
    },
  };
}

const HAPPY = {
  initialize: 'answer',
  protocolVersion: 1,
  agentInfo: { name: '@agentclientprotocol/codex-acp', title: 'Codex', version: '2.2.2-mock' },
  authMethods: [
    {
      id: 'api-key',
      name: 'API Key',
      description: 'Use an API key to authenticate',
      _meta: { 'api-key': { provider: 'openai' } },
    },
    { id: 'chat-gpt', name: 'ChatGPT', description: 'Use ChatGPT to authenticate' },
  ],
  advertiseAuthStatus: true,
  authStatus: {
    kind: 'account',
    label: 'ChatGPT Plus',
    account: { email: 'mock-user@example.com', plan: 'plus' },
  },
  afterInitialize: [],
  loggedIn: true,
  authenticate: 'succeed',
  sessionNew: 'answer',
  updatesBeforeSessionNewAnswer: [
    {
      sessionUpdate: 'available_commands_update',
      availableCommands: [{ name: 'review', description: 'Review the working tree' }],
    },
  ],
  stderrAtStart: ['mock-acp-agent: starting', `mock-acp-agent: OPENAI_API_KEY=${FAKE_API_KEY}`],
  prompts: [
    {
      stopReason: 'end_turn',
      steps: [
        { echo: 'cwd' },
        textUpdate('agent_thought_chunk', 'Looking at the README first.'),
        {
          update: {
            sessionUpdate: 'plan',
            entries: [
              { content: 'Read the README', priority: 'medium', status: 'in_progress' },
              { content: 'Run git status', priority: 'medium', status: 'pending' },
              { content: 'Report the result', priority: 'medium', status: 'pending' },
            ],
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-read',
            title: "Read file 'README.md'",
            kind: 'read',
            status: 'pending',
            locations: [{ path: '${cwd}/README.md', line: 1 }],
            rawInput: { path: '${cwd}/README.md' },
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-read',
            status: 'in_progress',
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-read',
            status: 'completed',
            rawOutput: { content: '# demo', token: 'not-a-real-token-value' },
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-search',
            title: "Search 'TODO'",
            kind: 'search',
            status: 'in_progress',
            rawInput: { query: 'TODO', note: `not ${FAKE_API_KEY}` },
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-search',
            status: 'completed',
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-edit',
            title: 'Edit files',
            kind: 'edit',
            status: 'in_progress',
            content: [
              { type: 'diff', path: '${cwd}/notes.txt', oldText: null, newText: 'hello\n' },
            ],
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-edit',
            status: 'completed',
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-exec',
            title: 'Run git status',
            kind: 'execute',
            status: 'in_progress',
            rawInput: { command: ['git', 'status'] },
            _meta: { terminal_info: { cwd: '${cwd}' } },
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'call-exec',
            status: 'completed',
            rawOutput: { exit_code: 0, stdout: 'nothing to commit, working tree clean' },
            _meta: { terminal_exit: { exit_code: 0 } },
          },
        },
        {
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'call-other',
            title: 'Frobnicate',
            kind: 'frobnicate',
          },
        },
        textUpdate('agent_message_chunk', 'The README is read, ', 'msg-1'),
        textUpdate('agent_message_chunk', 'git status is clean, ', 'msg-1'),
        textUpdate('agent_message_chunk', `and the key ${FAKE_API_KEY.slice(0, 6)}`, 'msg-1'),
        textUpdate('agent_message_chunk', `${FAKE_API_KEY.slice(6)} stays hidden.`, 'msg-1'),
        textUpdate('agent_message_chunk', `A whole key: ${FAKE_API_KEY}.`, 'msg-key'),
        { update: { sessionUpdate: 'usage_update', used: 1200, size: 200000 } },
        { update: { sessionUpdate: 'session_info_update', title: 'README and git status' } },
        {
          update: {
            sessionUpdate: 'config_option_update',
            configOptions: [modelOption('gpt-mock')],
          },
        },
        { update: { sessionUpdate: 'subagent_spawned', subagentId: 'sub-1' } },
      ],
    },
  ],
  exitOnEofMs: 0,
};

function modelOption(currentValue) {
  return {
    type: 'select',
    id: 'model',
    name: 'Model',
    category: 'model',
    currentValue,
    options: [{ value: 'gpt-mock', name: 'GPT mock' }],
  };
}

/** Built-in scenarios: each is HAPPY with these keys replaced. */
const SCENARIOS = {
  happy: {},
  // D8: "not logged in" is pushed, so the runtime signs in before session/new.
  'auth-required': { authStatus: { kind: 'none', label: 'Not logged in' }, loggedIn: false },
  // Advertised, but no status push (codex-acp when its account read fails): the runtime waits for
  // the status in vain, session/new answers -32000, the runtime signs in and retries once.
  'auth-no-push': { authStatus: null, loggedIn: false },
  // No status capability at all: the runtime does not wait and goes to session/new at once.
  'no-auth-capability': { advertiseAuthStatus: false, authStatus: null, loggedIn: false },
  'auth-fails': {
    authStatus: { kind: 'none', label: 'Not logged in' },
    loggedIn: false,
    authenticate: 'fail',
  },
  'auth-hangs': {
    authStatus: { kind: 'none', label: 'Not logged in' },
    loggedIn: false,
    authenticate: 'never',
  },
  // Login "works" but session/new keeps asking for authentication.
  'auth-still-required': {
    authStatus: null,
    loggedIn: false,
    authenticate: 'succeed-without-login',
  },
  'no-chatgpt': { authMethods: [HAPPY.authMethods[0]] },
  'api-key-login': { authStatus: { kind: 'api_key', label: 'OpenAI API key' } },
  // Answers initialize, then dies or breaks stdout while a connection check waits for the status.
  'crash-after-initialize': { authStatus: null, afterInitialize: [{ delayMs: 100 }, { crash: 5 }] },
  'malformed-after-initialize': {
    authStatus: null,
    afterInitialize: [{ delayMs: 100 }, { malformed: 'Codex says hello (not JSON-RPC)' }],
  },
  permission: {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          {
            update: {
              sessionUpdate: 'tool_call',
              toolCallId: 'call-rm',
              title: 'Run rm -rf build',
              kind: 'execute',
              status: 'pending',
              rawInput: { command: ['rm', '-rf', 'build'] },
            },
          },
          {
            permission: {
              toolCall: {
                toolCallId: 'call-rm',
                title: 'Run rm -rf build',
                kind: 'execute',
                status: 'pending',
              },
              options: [
                { optionId: 'allow_once', name: 'Yes, proceed', kind: 'allow_once' },
                { optionId: 'allow_for_session', name: 'Yes, always', kind: 'allow_always' },
                {
                  optionId: 'decline',
                  name: 'No, continue without running it',
                  kind: 'reject_once',
                },
              ],
            },
          },
          {
            update: {
              sessionUpdate: 'tool_call_update',
              toolCallId: 'call-rm',
              status: 'completed',
            },
          },
          textUpdate('agent_message_chunk', 'Done.', 'msg-perm'),
        ],
      },
    ],
  },
  // Streams a little, then waits until the run is cancelled (stop during a run).
  'long-run': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          {
            update: {
              sessionUpdate: 'tool_call',
              toolCallId: 'call-long',
              title: 'Run the test suite',
              kind: 'execute',
              status: 'in_progress',
            },
          },
          textUpdate('agent_message_chunk', 'Running the tests...', 'msg-long'),
          { waitForCancel: true },
        ],
      },
    ],
  },
  // The adapter withdraws its request before anyone answers; the turn then goes on.
  'permission-withdrawn': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          {
            permission: {
              toolCall: { toolCallId: 'call-push', title: 'Run git push', kind: 'execute' },
              options: [{ optionId: 'allow_once', name: 'Yes', kind: 'allow_once' }],
              withdrawAfterMs: 500,
            },
          },
          textUpdate('agent_message_chunk', 'Went on without it.', 'msg-after'),
        ],
      },
    ],
  },
  // A permission request the user never answers; a stop must cancel it.
  'permission-then-wait': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          {
            permission: {
              toolCall: { toolCallId: 'call-wait', title: 'Write notes.txt', kind: 'edit' },
              options: [{ optionId: 'allow_once', name: 'Yes', kind: 'allow_once' }],
            },
          },
          { waitForCancel: true },
        ],
      },
    ],
  },
  'spawn-child': {
    prompts: [{ stopReason: 'end_turn', steps: [{ spawnChild: true }, { waitForCancel: true }] }],
  },
  'crash-mid-run': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          {
            update: {
              sessionUpdate: 'tool_call',
              toolCallId: 'call-crash',
              title: 'Run the build',
              kind: 'execute',
              status: 'in_progress',
            },
          },
          { delayMs: 50 },
          { crash: 3 },
        ],
      },
    ],
  },
  'malformed-mid-run': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          textUpdate('agent_message_chunk', 'About to misbehave.', 'msg-bad'),
          { malformed: 'this is not JSON-RPC' },
          { waitForCancel: true },
        ],
      },
    ],
  },
  // JSON, but no JSON-RPC message (no method, id, result or error).
  'not-jsonrpc-mid-run': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [{ malformed: '{"hello":"world"}' }, { waitForCancel: true }],
      },
    ],
  },
  'malformed-at-start': { stdoutAtStart: 'Codex ACP adapter ready (not JSON)' },
  'initialize-never': { initialize: 'never' },
  'initialize-error': { initialize: { error: { code: -32603, message: 'Internal error' } } },
  'exit-at-start': { exitAtStart: 2 },
  'protocol-v2': { protocolVersion: 2 },
  // The error message carries a key: the agentError message must show it masked.
  'session-new-fails': {
    sessionNew: { error: { code: -32603, message: `Internal error (OPENAI key ${FAKE_API_KEY})` } },
  },
  'session-new-never': { sessionNew: 'never' },
  'prompt-fails': {
    prompts: [{ steps: [], error: { code: -32603, message: 'Internal error' } }],
  },
  'prompt-auth-required': {
    prompts: [{ steps: [], error: { code: AUTH_REQUIRED, message: 'Authentication required' } }],
  },
  env: { prompts: [{ stopReason: 'end_turn', steps: [{ echo: 'env' }, { echo: 'cwd' }] }] },
  refusal: { prompts: [{ stopReason: 'refusal', steps: [] }] },
  'max-tokens': { prompts: [{ stopReason: 'max_tokens', steps: [] }] },
  'max-turn-requests': { prompts: [{ stopReason: 'max_turn_requests', steps: [] }] },
  // A stop reason ACP v1 does not define.
  'unknown-stop': { prompts: [{ stopReason: 'some_new_reason', steps: [] }] },
  // Never answers session/cancel in time: the turn sleeps a minute first.
  'ignores-cancel': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          textUpdate('agent_message_chunk', 'Thinking very hard...', 'msg-slow'),
          { delayMs: 60000 },
        ],
      },
    ],
  },
  // JSON-RPC messages whose params miss required fields (a tool call without a title, a text
  // block without text); the SDK rejects their params. A valid chunk follows.
  'bad-params-mid-run': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          { update: { sessionUpdate: 'tool_call', toolCallId: 'call-untitled', kind: 'read' } },
          { update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text' } } },
          textUpdate('agent_message_chunk', 'After the bad updates.', 'msg-after-bad'),
        ],
      },
    ],
  },
  burst: { prompts: [{ stopReason: 'end_turn', steps: [{ burst: 300 }] }] },
  // Who sees ELECTRON_RUN_AS_NODE (ADR 0003 decision 12): the mock, its command, its Node child.
  'probe-run-as-node': {
    prompts: [{ stopReason: 'end_turn', steps: [{ probeRunAsNode: true }] }],
  },
  // Command output in pieces (ADR 0005 decision 11): a key split across two pieces of one command, a
  // key-value split across three of another while a third command's output and a message come between,
  // and output that did not stream arriving with the end of its command.
  'terminal-output': {
    prompts: [
      {
        stopReason: 'end_turn',
        steps: [
          terminalCommand('call-cat', ['cat', 'notes.txt']),
          terminalOutput('call-cat', `notes\nthe key ${FAKE_API_KEY.slice(0, 6)}`),
          terminalOutput('call-cat', `${FAKE_API_KEY.slice(6)} stays hidden.\n`),
          terminalExit('call-cat', 0),
          terminalCommand('call-env', ['cat', '.env']),
          terminalCommand('call-ls', ['ls']),
          terminalOutput('call-env', 'PATH=/usr/bin\nOPENAI_API_KEY='),
          terminalOutput('call-ls', 'README.md\n'),
          terminalOutput('call-env', FAKE_API_KEY.slice(0, 9)),
          textUpdate('agent_thought_chunk', 'Reading the environment.'),
          terminalOutput('call-env', `${FAKE_API_KEY.slice(9)}\nDEBUG=1\n`),
          terminalExit('call-env', 0),
          terminalOutput('call-ls', 'notes.txt\n', {
            status: 'completed',
            _meta: { terminal_exit: { exit_code: 0, signal: null, terminal_id: 'call-ls' } },
          }),
          terminalCommand('call-grep', ['grep', '-r', 'sk-', '.']),
          terminalOutput('call-grep', `./a.txt: ${FAKE_API_KEY.slice(0, 4)}`, {
            status: 'failed',
            _meta: { terminal_exit: { exit_code: 1, signal: null, terminal_id: 'call-grep' } },
          }),
          textUpdate('agent_message_chunk', 'Done.', 'msg-terminal'),
        ],
      },
    ],
  },
};

function parseArgs(argv) {
  const opts = { scenario: null, scenarioFile: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--scenario') opts.scenario = argv[++i];
    else if (argv[i] === '--scenario-file') opts.scenarioFile = argv[++i];
  }
  return opts;
}

function loadScenario(opts) {
  let spec = {};
  if (opts.scenarioFile) {
    spec = JSON.parse(fs.readFileSync(opts.scenarioFile, 'utf8'));
  } else {
    spec = { extends: opts.scenario || process.env['MOCK_ACP_SCENARIO'] || 'happy' };
  }
  const base = spec.extends ? SCENARIOS[spec.extends] : {};
  if (base === undefined) throw new Error(`unknown scenario ${spec.extends}`);
  const scenario = { ...HAPPY, ...base, ...spec };
  delete scenario.extends;
  return scenario;
}

const scenario = loadScenario(parseArgs(process.argv.slice(2)));

function writeLine(text) {
  process.stdout.write(`${text}\n`);
}

function send(message) {
  writeLine(JSON.stringify({ jsonrpc: '2.0', ...message }));
}

function answer(id, result) {
  send({ id, result });
}

function answerError(id, error) {
  send({ id, error });
}

function notify(method, params) {
  send({ method, params });
}

let nextRequestId = 0;
const pendingRequests = new Map();

/** Sends a request; resolves with the client's response message ({result} or {error}). */
function request(method, params, { withdrawAfterMs } = {}) {
  const id = nextRequestId++;
  send({ id, method, params });
  if (withdrawAfterMs !== undefined) {
    setTimeout(() => {
      if (pendingRequests.has(id)) notify('$/cancel_request', { requestId: id });
    }, withdrawAfterMs);
  }
  return new Promise((resolve) => pendingRequests.set(id, resolve));
}

const session = {
  id: null,
  cwd: process.cwd(),
  loggedIn: scenario.loggedIn,
  turn: 0,
  running: false,
  cancelled: false,
  cancelWaiters: [],
};

function substitute(value) {
  if (typeof value === 'string') return value.split('${cwd}').join(session.cwd.replace(/\\/g, '/'));
  if (Array.isArray(value)) return value.map(substitute);
  if (value && typeof value === 'object') {
    const copy = {};
    for (const [key, item] of Object.entries(value)) copy[key] = substitute(item);
    return copy;
  }
  return value;
}

function sendUpdate(update) {
  notify('session/update', { sessionId: session.id, update: substitute(update) });
}

function agentMessage(text, messageId) {
  sendUpdate({ sessionUpdate: 'agent_message_chunk', messageId, content: { type: 'text', text } });
}

function pushAuthStatus(status) {
  if (status) notify('_auth/status_update', { authStatus: status });
}

function waitForCancel() {
  if (session.cancelled) return Promise.resolve();
  return new Promise((resolve) => session.cancelWaiters.push(resolve));
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function recordPid(role, pid) {
  if (scenario.pidFile && pid) fs.appendFileSync(scenario.pidFile, `${role} ${pid}\n`);
}

/** A child that runs until it is killed. */
function startLongLivedChild() {
  return spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
}

/** Whether a command this process runs sees ELECTRON_RUN_AS_NODE. */
function commandSeesRunAsNode() {
  const result =
    process.platform === 'win32'
      ? spawnSync(process.env['ComSpec'] || 'cmd.exe', ['/d', '/c', `set ${RUN_AS_NODE}`], {
          stdio: 'ignore',
          windowsHide: true,
        })
      : spawnSync('/bin/sh', ['-c', 'test -n "${' + RUN_AS_NODE + '+x}"'], { stdio: 'ignore' });
  return result.status === 0;
}

function runAsNodeReport() {
  return {
    electron: process.versions.electron ?? null,
    preloaded: process.execArgv.includes('--require') || process.execArgv.includes('-r'),
    sees: RUN_AS_NODE in process.env,
    commandSees: commandSeesRunAsNode(),
  };
}

/** Like codex-acp starting Codex: this script on the same executable, with this environment. */
function probeChild() {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [__filename, PROBE_CHILD], {
      env: process.env,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    let output = '';
    const timer = setTimeout(() => {
      child.kill();
      resolve({ answered: false });
    }, PROBE_CHILD_WAIT_MS);
    child.stdout.on('data', (data) => {
      output += data;
    });
    child.on('error', () => {
      clearTimeout(timer);
      resolve({ answered: false });
    });
    child.on('close', () => {
      clearTimeout(timer);
      try {
        resolve({ answered: true, ...JSON.parse(output) });
      } catch {
        resolve({ answered: false });
      }
    });
  });
}

/** The same child through util.promisify(execFile), as codex-acp runs its helper commands. */
async function probeChildByExecFile() {
  try {
    const { stdout } = await promisify(execFile)(process.execPath, [__filename, PROBE_CHILD], {
      env: process.env,
      timeout: PROBE_CHILD_WAIT_MS,
      windowsHide: true,
    });
    return { answered: true, ...JSON.parse(stdout) };
  } catch {
    return { answered: false };
  }
}

/**
 * Starts the command detached through a launcher, as PowerShell's Start opens a login page in a browser
 * that was not running, and records the launcher, then the command as soon as the launcher reports it
 * started it. The launcher exits then, and the promise resolves once it is gone, as codex-acp's `open`
 * waits for PowerShell on Windows; with `stays`, the launcher runs on and the promise resolves as soon
 * as the command started. `delayMs` makes the launcher wait that long first (a slow or cold PowerShell).
 */
function openBrowserStandIn(command, stays, delayMs) {
  if (process.platform === 'win32') return startThroughPowerShell(command, stays, delayMs);
  const launcher = [
    "const { spawn } = require('node:child_process');",
    'const [stays, delayMs, command, ...args] = process.argv.slice(1);',
    'setTimeout(() => {',
    "  const browser = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true });",
    '  browser.unref();',
    "  browser.on('spawn', () => process.stdout.write(`${browser.pid}\\n`));",
    "  if (stays === 'stays') setInterval(() => {}, 1000);",
    '}, Number(delayMs));',
  ].join('\n');
  const child = spawn(
    process.execPath,
    ['-e', launcher, stays ? 'stays' : 'exits', String(delayMs), ...command],
    { stdio: ['ignore', 'pipe', 'ignore'] },
  );
  return launched(child, stays);
}

/**
 * Records a browser launcher and, once it printed it, the pid of the browser it started: also when the
 * sign-in ends before the launcher does. Resolves once the launcher is gone, or with `stays` once the
 * browser started.
 */
function launched(child, stays) {
  recordPid('browser-launcher', child.pid);
  return new Promise((resolve) => {
    let output = '';
    let reported = false;
    child.stdout.on('data', (data) => {
      output += data;
      if (!reported && output.includes('\n')) {
        reported = true;
        recordPid('browser', Number(output.trim()) || null);
        if (stays) resolve();
      }
    });
    child.on('error', () => resolve());
    child.on('close', () => resolve());
  });
}

/** A command-line argument as the Windows C runtime reads it back. */
function windowsArgument(text) {
  if (text !== '' && !/[\s"]/.test(text)) return text;
  return `"${text.replace(/(\\*)"/g, '$1$1\\"').replace(/(\\+)$/, '$1$1')}"`;
}

/**
 * openBrowserStandIn on Windows: through PowerShell's Start-Process, like
 * codex-acp's `Start` (the shell starts the program, so it inherits no handle
 * of the mock's, as a browser started that way does not).
 */
function startThroughPowerShell([file, ...args], stays, delayMs) {
  const quote = (text) => `'${text.replace(/'/g, "''")}'`;
  const argumentList =
    args.length > 0 ? ` -ArgumentList ${quote(args.map(windowsArgument).join(' '))}` : '';
  const script = [
    "$ProgressPreference = 'SilentlyContinue'",
    delayMs > 0 ? `Start-Sleep -Milliseconds ${delayMs}` : '',
    `$browser = Start-Process -FilePath ${quote(file)}${argumentList} -WindowStyle Hidden -PassThru`,
    '[Console]::Out.WriteLine($browser.Id)',
    stays ? 'while ($true) { Start-Sleep -Seconds 1 }' : '',
  ].join('\n');
  const powerShell = `${process.env['SystemRoot'] || 'C:\\Windows'}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe`;
  const child = spawn(
    powerShell,
    [
      '-NoProfile',
      '-NonInteractive',
      '-EncodedCommand',
      Buffer.from(script, 'utf16le').toString('base64'),
    ],
    { stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true },
  );
  return launched(child, stays);
}

async function runStep(step) {
  if (step.update) sendUpdate(step.update);
  else if (step.delayMs !== undefined) await delay(step.delayMs);
  else if (step.permission) {
    const response = await request(
      'session/request_permission',
      {
        sessionId: session.id,
        toolCall: substitute(step.permission.toolCall),
        options: step.permission.options,
      },
      { withdrawAfterMs: step.permission.withdrawAfterMs },
    );
    let said;
    if (response.error) {
      said = `error ${response.error.code}`;
    } else {
      const outcome = response.result && response.result.outcome ? response.result.outcome : {};
      said =
        outcome.outcome === 'selected'
          ? `selected ${outcome.optionId}`
          : outcome.outcome || 'no outcome';
    }
    agentMessage(`permission: ${said}`, 'msg-permission');
  } else if (step.echo === 'cwd') {
    agentMessage(`cwd: ${session.cwd} | process cwd: ${process.cwd()}`, 'msg-cwd');
  } else if (step.echo === 'env') {
    agentMessage(`env: ${Object.keys(process.env).sort().join(',')}`, 'msg-env');
  } else if (step.echo === 'runtime') {
    const electron = process.versions.electron ?? 'none';
    agentMessage(`runtime: node ${process.versions.node}, electron ${electron}`, 'msg-runtime');
  } else if (step.spawnChild) {
    const child = startLongLivedChild();
    recordPid('child', child.pid);
    agentMessage(`child pid: ${child.pid}`, 'msg-child');
  } else if (step.probeRunAsNode) {
    const report = {
      adapter: runAsNodeReport(),
      codex: await probeChild(),
      execFileChild: await probeChildByExecFile(),
    };
    sendUpdate({
      sessionUpdate: 'tool_call',
      toolCallId: 'call-probe-run-as-node',
      title: `Probe ${RUN_AS_NODE}`,
      kind: 'execute',
      status: 'completed',
      rawOutput: report,
    });
  } else if (step.waitForCancel) await waitForCancel();
  else if (step.stderr !== undefined) process.stderr.write(`${step.stderr}\n`);
  else if (step.malformed !== undefined) writeLine(step.malformed);
  else if (step.crash !== undefined) process.exit(step.crash);
  else if (step.burst !== undefined) {
    for (let i = 1; i <= step.burst; i++) agentMessage(`update ${i}`, `msg-burst-${i}`);
  }
}

async function prompt(id) {
  if (session.running) {
    answerError(id, { code: INVALID_PARAMS, message: 'A prompt is already running' });
    return;
  }
  const turns = scenario.prompts || [];
  const turn = turns[Math.min(session.turn, turns.length - 1)] || {
    steps: [],
    stopReason: 'end_turn',
  };
  session.turn++;
  session.running = true;
  session.cancelled = false;
  for (const step of turn.steps || []) {
    if (session.cancelled) break;
    await runStep(step);
  }
  session.running = false;
  if (session.cancelled) {
    answer(id, { stopReason: 'cancelled' });
  } else if (turn.error) {
    answerError(id, turn.error);
  } else {
    answer(id, { stopReason: turn.stopReason || 'end_turn' });
  }
}

function initialize(id) {
  if (scenario.initialize === 'never') return;
  if (scenario.initialize && scenario.initialize.error) {
    answerError(id, scenario.initialize.error);
    return;
  }
  answer(id, {
    protocolVersion: scenario.protocolVersion,
    agentInfo: scenario.agentInfo,
    agentCapabilities: {
      loadSession: false,
      promptCapabilities: { embeddedContext: true, image: true },
      ...(scenario.advertiseAuthStatus ? { _meta: { authStatus: {} } } : {}),
    },
    authMethods: scenario.authMethods,
  });
  setImmediate(async () => {
    pushAuthStatus(scenario.authStatus);
    for (const step of scenario.afterInitialize || []) await runStep(step);
  });
}

async function authenticate(id, params) {
  const methodId = params && params.methodId;
  if (!scenario.authMethods.some((method) => method.id === methodId)) {
    answerError(id, { code: -32600, message: 'Invalid request' });
    return;
  }
  if (scenario.browserStandIn) {
    // codex-acp opens the login page first, whether the sign-in then succeeds, fails or never ends.
    await openBrowserStandIn(
      scenario.browserStandIn,
      scenario.browserLauncher === 'stays',
      scenario.browserLauncherDelayMs || 0,
    );
  }
  if (scenario.authenticate === 'never') return;
  if (scenario.authenticate === 'fail') {
    answerError(id, {
      code: AUTH_REQUIRED,
      message: 'Sign-in did not complete',
      data: { methodId },
    });
    return;
  }
  if (scenario.authenticate !== 'succeed-without-login') {
    session.loggedIn = true;
    pushAuthStatus({
      kind: 'account',
      label: 'ChatGPT Plus',
      account: { email: 'mock-user@example.com' },
    });
  }
  answer(id, {});
}

function newSession(id, params) {
  if (scenario.sessionNew === 'never') return;
  if (scenario.sessionNew && scenario.sessionNew.error) {
    answerError(id, scenario.sessionNew.error);
    return;
  }
  if (!session.loggedIn) {
    answerError(id, { code: AUTH_REQUIRED, message: 'Authentication required' });
    return;
  }
  session.id = `mock-session-${process.pid}`;
  session.cwd = (params && params.cwd) || process.cwd();
  for (const update of scenario.updatesBeforeSessionNewAnswer || []) sendUpdate(update);
  answer(id, {
    sessionId: session.id,
    modes: {
      currentModeId: 'agent',
      availableModes: [
        { id: 'read-only', name: 'Read only' },
        {
          id: 'agent',
          name: 'Auto review',
          description: 'Only ask for actions detected as unsafe',
        },
      ],
    },
    configOptions: [modelOption('gpt-mock')],
  });
}

function cancel() {
  if (!session.running) return;
  session.cancelled = true;
  for (const resolve of session.cancelWaiters.splice(0)) resolve();
}

function handle(message) {
  if (message.method === undefined && message.id !== undefined) {
    const resolve = pendingRequests.get(message.id);
    pendingRequests.delete(message.id);
    if (resolve) resolve(message);
    return;
  }
  const { id, method, params } = message;
  switch (method) {
    case 'initialize':
      return initialize(id);
    case 'authenticate':
      return void authenticate(id, params);
    case 'session/new':
      return newSession(id, params);
    case 'session/prompt':
      return void prompt(id);
    case 'session/cancel':
      return cancel();
    default:
      if (id !== undefined)
        answerError(id, { code: METHOD_NOT_FOUND, message: 'Method not found' });
  }
}

function main() {
  if (scenario.exitAtStart !== undefined && scenario.exitAtStart !== null) {
    process.exit(scenario.exitAtStart);
  }
  recordPid('adapter', process.pid);
  if (scenario.childAtStart) recordPid('startup-child', startLongLivedChild().pid);
  for (const line of scenario.stderrAtStart || []) process.stderr.write(`${line}\n`);
  if (scenario.stdoutAtStart) writeLine(scenario.stdoutAtStart);
  const lines = readline.createInterface({ input: process.stdin });
  lines.on('line', (line) => {
    if (line.trim() === '') return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      process.stderr.write('mock-acp-agent: unreadable input line\n');
      return;
    }
    handle(message);
  });
  lines.on('close', () => {
    // Like codex-acp: stdin EOF ends the adapter (it leaves its children to the client's tree kill).
    setTimeout(() => process.exit(0), scenario.exitOnEofMs || 0);
  });
}

if (process.argv.includes(PROBE_CHILD)) {
  process.stdout.write(JSON.stringify(runAsNodeReport()));
} else {
  main();
}
