# Pixel Agents e2e tests

Playwright end-to-end tests for the VS Code extension, the standalone `npx pixel-agents` server, and the agents-room desktop shell (`e2e/desktop/`, its own config `e2e/playwright.desktop.config.ts`). This README is the single source of truth for what's e2e-tested, what's not, and how to run the suite.

## What this suite covers

Behavioral overview by area. Each area corresponds to a `test.describe` block in the spec files, an `@area:` tag on each test title, and an Allure `epic` label.

### Spawn paths (`@area:spawn`)

Agents being created and adopted. Covers internal terminals launched by clicking `+ Agent`, external Claude sessions adopted by the hook server or the JSONL scanner, basic Task subagent appearance/despawn, and lead+teammate routing for inline and tmux team modes.

### Lifecycle regressions (`@area:lifecycle`)

Edge cases that historically caused agent-character desync: `/clear`, `--resume`, X-button close, dismissal cooldown, parallel sub-agents, teammate add/remove, rapid `/clear` followed by a new tool, late resume after stale cleanup.

### Cross-cutting checks (`@area:cross-cutting`)

Invariants that should hold across every spawn path: tool status text matches the active tool name, sound chimes fire on the right events, restored agents skip the matrix spawn animation, hook installer preserves third-party hooks, settings persist across webview reload, sub-agent permission timer fires, layout editor enter/paint/save/exit smoke.

### Teams routing (`@area:teams`)

Lead and teammate tool routing in both inline and tmux team modes, internal and external.

### Hooks-off matrix (`@area:matrix`)

Every spawn permutation (internal vs external origin × basic vs inline-teammate vs tmux-teammate mode) re-verified against the heuristic JSONL-polling path with the hook server disabled. Confirms the polling-based detection produces the same agent state as the hook-driven path.

### Standalone server (`@area:standalone`)

The `npx pixel-agents` CLI path: hook-driven lifecycle propagates from the local server into the browser SPA via the single `/ws` WebSocket endpoint.

### Desktop shell (`@area:desktop-shell`)

The agents-room Electron shell (`desktop/`) serving the built renderer at `app://bundle`, against a fake local runtime. Covers: the office renders from the renderer's own assets and the renderer joins the runtime WebSocket with the per-launch token; a refused handshake leaves the renderer reconnecting; the folder picker returns `null` on cancel and the path on pick; no new windows or navigation away from `app://bundle`; every `app://bundle` response, 404 and 400 included, carries the CSP, so a missing path cannot be framed; the desktop Settings modal hides the entries only VS Code or the upstream server answer; the runtime failure dialog for a missing ready line (the shell kills that runtime) and for an exit before ready ("다시 시작" starts exactly one new runtime, "종료" quits); quitting ends the runtime, and kills a runtime that ignores stdin EOF together with its child process; a shell that ends abruptly leaves the runtime alive long enough to end its child on stdin EOF.

### Desktop shell with the local runtime (`@area:desktop-runtime`)

The built shell against the real local runtime: the boot jar `runtime/build/dev-runtime.json` names, launched by the shell exactly as in development, with nothing faked on either side. This is the end-to-end check of the shell↔runtime handshake (`docs/architecture.md`, "셸과 런타임의 연결"). Covers: the ready line; the renderer joining from `app://bundle` with the per-launch token, while the runtime refuses a missing or wrong token (401) and a foreign Origin (403); the runtime's connect-time snapshot reaching the renderer's runtime state, with no upstream command sent to the runtime; quitting the app ends the runtime on stdin EOF within the shell's 3 s grace, a connected renderer included, and leaves no java process; a shell that ends abruptly leaves no java process either. Needs the runtime jar from `npm run runtime:build` (or `npm run runtime:assemble`, which skips the runtime tests, as CI does): without it the specs skip locally and fail in CI.

### Desktop shell running an agent over ACP (`@area:desktop-agent`)

The built shell against the real runtime jar, whose seeded Codex agent runs as the mock ACP agent (`AGENTS_ROOM_HARNESS_CODEX_COMMAND`, `AGENTS_ROOM_E2E=1`) on the shell's own Electron as Node (`AGENTS_ROOM_NODE`, `ELECTRON_RUN_AS_NODE`), as codex-acp would. The project and agent panels do not exist yet, so commands go out on the renderer's runtime socket through the `sendRuntimeCommand` test hook and the assertions read the renderer's runtime state (`getRuntimeState`). Covers: a project opened through the stubbed folder picker gets the seeded Codex agent (`configured`); one prompt streams its events to the renderer in receive order (`seq` 1..N without gaps; status `starting` → `ready` → `running` → `ready`; start stages without sign-in for a logged-in adapter; `authStatus` without the account email; `runStarted`, a tool call merged by its id, the answer chunks and `runEnded {completed}`, all with the run's `runId` and the session's `sessionId`); the adapter ran on Electron's Node in the project folder, which `session/new` got as its `cwd`; stopping ends the session as `stopped` and the adapter process; an adapter that crashes mid-run fails the run (`PROCESS_EXITED`, never `completed`), ends the session as `exited`, puts the agent in `error` (also in a reconnecting renderer's snapshot), and a new start opens a new session; quitting leaves no java or mock process. Same build needs as `@area:desktop-runtime`.

### Pet system (`@area:pets`)

The animated pets feature, which has no hook dependency. Pet sprites load and the `petSpritesLoaded` broadcast arrives with manifest display names; placing a pet from the Pets-tab carousel toggles it on/off and persists across a panel reload via `~/.pixel-agents/layout.json`; clicking a pet shows a heart bubble that auto-dismisses and dismisses again on re-click. Pets render only on the canvas, so live state is read through the `getPets` / `petClick` e2e test hooks. FSM internals, pathfinding, FOLLOW, z-sort, and legacy-layout migration are covered by webview unit tests, not e2e.

## What's NOT covered (gaps + deferred)

Scenarios that exist as product behavior but are not in the automated suite. PRs that close a gap should remove the corresponding row.

| Scenario                                                                                 | Why not automated                                                                                                                            | Tracked                      |
| ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Multi-window `layout.json` cross-sync                                                    | Needs two VS Code instances simultaneously; fixture work                                                                                     | none                         |
| External asset directory add/remove via Settings                                         | Needs bundled test asset packs                                                                                                               | none                         |
| Bypass-permissions startup flag                                                          | Security-sensitive; manual review path                                                                                                       | none                         |
| Workspace folder add/remove mid-session                                                  | Edge case; infra-heavy                                                                                                                       | none                         |
| Heuristic-timer cancellation after **internal-terminal** agent close                     | VS Code terminal panel collapse races the canvas click on the X overlay; covered via the external-agent variant which dodges the layout race | external variant in suite    |
| Producer/viewer relay scenarios (multi-viewer replay, producer reconnect reconciliation) | Producer endpoint not yet built                                                                                                              | `feat/producer-viewer-split` |

## Pre-release manual smoke (~30 min)

CI green on this suite is the safety net for behavioral regressions. The checks below are what e2e can't meaningfully assert on (visual polish, real-Claude integration, cross-process behaviors). Run them before tagging a Marketplace release — not on every PR.

**Visual + interactive polish** (after any change touching `renderer.ts`, `spriteCache.ts`, `colorize.ts`, `*.tsx`, CSS, or `editorActions.ts`):

- Pan around the office with middle-mouse drag — characters z-sort correctly against same-row chairs and lower-row desks, no flicker.
- Spawn 3+ agents — matrix spawn animation renders cleanly, characters move smoothly between seats.
- Open the Layout editor — paint floor with HSBC sliders, place + rotate (R) furniture, toggle on/off (T) state, drag-to-move in SELECT, multi-stage Esc unwinds correctly.
- Hover and click characters — overlay text positioning is correct, selection outline crisp, click on a seat reassigns.

**Real Claude Code integration** (mock-claude is a fixture; real Claude's JSONL has edge cases the mock doesn't):

- Launch the Extension Development Host (F5), click + Agent, ask Claude to do a few tool-heavy turns and a permission-requiring tool. Watch for character desync, missing animations, stuck permission bubbles.
- Use a session with a large pasted image (multi-MB base64 user message) — confirm the "Possible format issue" warning doesn't false-fire and tool tracking still works.
- Test with one MCP server installed — confirm `mcp_progress` records don't break tool status.

**`npx pixel-agents` standalone** (e2e covers Chrome via Playwright; verify other browsers + real workflow):

- `node dist/cli.js` (or `npx pixel-agents` after publish), open `http://localhost:3100` in Firefox AND Safari, run a real Claude session in a terminal — confirm characters appear and animate via WebSocket.
- Refresh the browser mid-session — WebSocketTransport reconnects, agents reappear from server state.

**Cross-window sync** (rarely covered by CI, easy to break):

- Open two VS Code windows. Edit the layout in one (paint a tile, save). Within ~2 s the other window picks it up.

**First-run experience** (before publishing):

- Delete `~/.pixel-agents/` entirely. Launch the extension fresh — default layout loads, first-run tooltip appears, no console errors, hooks auto-install on first agent spawn.

**Platform sanity** (CI hosts ≠ your machine):

- On the OS you primarily develop on, run a normal session for ~5 minutes — confirm no surprise CPU spikes, no leaked file watchers, panel reload doesn't lose state.

Skip the F5 matrix walk-through that used to take hours — the e2e suite covers it. Hand-driven testing now exists only to catch what automated assertions structurally can't see.

## Running

```bash
cd pixel-agents
npm run compile && npm run e2e               # full suite (~10 min)

npm run e2e -- --grep "@area:spawn"          # filter by area tag
npm run e2e -- --grep "@area:cross-cutting"
npm run e2e -- --headed                      # watch chromium for standalone test

npm run desktop:build && npm run e2e:desktop   # desktop shell suite (e2e/desktop, no VS Code download)
npm run runtime:build                          # once (or runtime:assemble, no tests), so the real-runtime desktop specs run instead of skipping

npm run e2e:inventory                        # regenerate the inventory section below (both configs)
npm run test:report                          # build the Allure dashboard from latest run
npm run test:report:open                     # serve + open the Allure dashboard in a browser
```

## Mocking model & rules

E2E tests drive Pixel Agents through a Claude-like **process boundary**, not by poking internals. The mocked `claude` (`e2e/fixtures/mock-claude` → `mock-claude-runner.cjs`) behaves like the real CLI for the parts Pixel Agents observes: it spawns as a process, creates its own append-only JSONL transcripts, and executes the installed hook script under `~/.pixel-agents/hooks` — the same path the real CLI uses. The builder API itself (`claudeScenario(...)`, `.at()`, `.appendJsonl()`, `.emitHook()`, `.holdOpenFor()`) is documented in CONTRIBUTING.md → "Mock claude".

Rules for a correct test:

- **Drive behavior through a scenario, not by hand.** Define timed actions with the `claudeScenario(...)` builder and let the mock perform them. Don't hand-write transcript files or hand-fire hooks inside a terminal-driven test body.
- **Transcripts are append-only.** Existing JSONL lines are never mutated in place; new records appear later in the stream. Scenarios model this with timed `.appendJsonl(...)` steps.
- **Assert only on Playwright-visible outcomes** — agent overlays, character state, sound hooks — never on the mock's internals. The mock never decides pass/fail.
- **Desktop shell tests mock the local runtime at its process boundary.** `e2e/desktop/fixtures/fake-runtime.cjs` speaks only the shell↔runtime handshake (env token, the `AGENTS_ROOM_READY {"port":N}` stdout line, a token- and Origin-gated `/ws`, exit on stdin EOF) and is passed to the shell through `AGENTS_ROOM_RUNTIME_COMMAND`. Its flags pick a behavior (silent, exit before ready, refuse the handshake, ignore stdin EOF, start a child process); the assertions read the window, the shell's `runtime.log` and the OS process table, never the fake's internals. How many runtimes the shell started, and their PIDs, come from the shell's own `[shell] the runtime started (pid N)` lines. The fake's state file is read only to find its child process in the OS process table and to clean up. To end the shell abruptly, kill the PID the Electron main process reports (`app.evaluate(() => process.pid)`): the process Playwright spawned is a launcher, not the main process. It is for shell-level tests only: agent behavior is tested through the real runtime with a mock ACP agent. `realRuntime.spec.ts` runs the real runtime jar instead, through the shell's own dev descriptor lookup (no `AGENTS_ROOM_RUNTIME_COMMAND`); it asserts on the window, the renderer's `getRuntimeState` test hook (enabled by setting `__PIXEL_AGENTS_E2E` and reloading, which also opens a fresh runtime connection), `runtime.log`, the runtime's answers to `/ws` handshakes from the test process, and the java processes running the jar in the OS process table. Native dialogs are stubbed in the main process with `app.evaluate`, because Playwright cannot drive OS dialogs.
- **Agent behavior is mocked at the ACP adapter's process boundary.** `e2e/desktop/fixtures/mock-acp-agent.cjs` is a dependency-free Node script that speaks ACP v1 JSON-RPC over stdio like codex-acp: `initialize` (auth methods `api-key` and `chat-gpt`), `_auth/status_update`, `authenticate`, `session/new`, streamed `session/update`s, `session/request_permission` and `session/cancel`. The runtime starts it as the Codex adapter through `AGENTS_ROOM_HARNESS_CODEX_COMMAND` (JSON argv, e.g. `["<node>", "<repo>/e2e/desktop/fixtures/mock-acp-agent.cjs", "--scenario", "permission"]`; `--scenario-file <file.json>` reads a scenario the test wrote). The runtime passes adapters an allowlisted environment only, so scenarios travel in the argv, never in env vars. With `AGENTS_ROOM_E2E=1` the runtime refuses to start an adapter unless that variable is set, so a spec can never launch the real Codex by accident. Every desktop launch (`helpers/desktop.ts`) sets `AGENTS_ROOM_E2E=1` and an isolated `CODEX_HOME`, and drops every `AGENTS_ROOM_HARNESS_<ID>_COMMAND`, `AGENTS_ROOM_NODE*`, `AGENTS_ROOM_ADAPTERS_DIR` and `CODEX_HOME` a developer's shell holds, in any letter case. The runtime's Gradle tests are fenced the same way (`AGENTS_ROOM_E2E=1`, blank overrides, a scratch `CODEX_HOME`); a test that needs an adapter names the mock as a Spring property. `launch({ realRuntime: true, mockAgent: <scenario> })` writes the scenario to the launch's temp dir and runs the mock as `[<electron>, mock-acp-agent.cjs, --scenario-file, <file>]`: the shell passes its Electron to the runtime as the adapters' Node, so the mock runs on it as Node exactly as codex-acp would. Mock processes are found in the OS process table by that scenario file path (unique per launch), and cleanup kills any left. The script header lists the scenarios and step types (happy run, sign-in stage with and without a status push, permissions including one the adapter withdraws, stop during a run and a cancel that never answers, stop reasons, crash, malformed line, params the SDK rejects, a break after `initialize`, timeouts). Specs assert on what the runtime and the renderer make of it, never on the mock's internals. The runtime's own integration tests (`AcpAgentIntegrationTests`) drive the same mock.
- **Standalone is the one exception.** `standalone/hooks.spec.ts` has no VS Code terminal to host a mocked `claude`, so it POSTs to the server's hook endpoint directly via `sendHookEvent`. That is correct _only_ for the standalone-server path; every terminal-driven test must use the scenario builder.

## What to read before adding a test

- `pixel-agents/CLAUDE.md` — architecture and message protocol
- `pixel-agents/e2e/fixtures/pixel-agents.ts` — fixture lifecycle
- `pixel-agents/e2e/helpers/` — every helper, especially `hooks.ts`, `mock-claude.ts`, `office.ts`, `webview.ts`

When you add a new test:

- Pick a `test.describe` block that matches an existing `@area:` tag, OR add a new area to the "What this suite covers" section above and pick a tag.
- Add `@area:<tag>` to the test title.
- Add Allure `epic` / `feature` / `story` labels matching the area.
- Run `npm run e2e:inventory` and commit the regenerated section.

When you remove a test:

- Run `npm run e2e:inventory` so the inventory drops it.
- If the scenario it tested is now manual or deferred, add a row to "What's NOT covered".

## Narration

Every VS Code run video is narrated. The narrator writes one yellow `[test]` line per
action taken and per assertion verified to a per-test log, and several surfaces
display it by simply tailing that log:

```
narrate.step()/check()  ──►  <tmpHome>/.claude-mock/test-narration.log     (yellow [test])
external mock stdout     ──►  <tmpHome>/.claude-mock/external-narration.log (magenta [external·tag])

Surface A — the "e2e monitor" terminal: opened by the fixture after VS Code
            finishes restoring the review layout (openMonitorTerminal). Tails
            BOTH logs, so even a test with no agents has a narrated surface
            from its first action.
Surface B — every mock-claude terminal tab: the wrapper backgrounds a headerless
            tail of both logs into its own stdout, interleaved with the runner's
            cyan [mock-claude] lines. Whichever tab has focus, the full story
            shows. Because the tail starts at byte 0, a tab opened mid-test
            replays the whole story so far.
```

Standalone recordings are deliberately raw browser artifacts. Their fixture has
no VS Code terminal or narration surface, so the eight standalone videos are
outside this narration contract.

Usage: the `pixelAgents` fixture exposes a `narrator` on its payload — tests call
`narrator.step('…')` before an action and `narrator.check('…')` after an
assertion resolves. Shared helpers narrate universal moments (spawning/closing an
agent) via the module-level `narrate` in `helpers/test-narration.ts`.

**Cosmetic-only contract (never violate):** Pixel Agents never reads terminal
output — its inputs are JSONL transcripts and hook POSTs — so narration cannot
change what a test exercises. Narration must **never carry an assertion, gate
logic, or affect timing**. Deleting every `step`/`check` call must leave all
tests passing. Never call the narrator from inside a browser-context callback
(`frame.waitForFunction`/`evaluate`/`.poll`) — it is Node-side only. Never add a
`waitForTimeout` for narration's sake. After the test body is complete, the
fixture may wait for the final narration marker to render before closing the
recorded window; that teardown-only synchronization cannot gate test behavior.

## Test inventory

This section is auto-generated. Do not edit between the markers; CI fails on drift.

<!-- BEGIN:E2E-INVENTORY -->

106 tests total. Generated by `scripts/generate-e2e-inventory.mjs`. Re-run after adding or removing tests.

### `@area:spawn` (2 tests)

- `e2e/claude/hooks-on/basic.spec.ts:34` — internal terminal spawns agent and Task subagent appears then despawns (Hooks ON / spawn paths)
- `e2e/claude/hooks-on/basic.spec.ts:120` — external Claude session adopted via hook confirmation lifecycle (Hooks ON / spawn paths)

### `@area:lifecycle` (22 tests)

- `e2e/claude/hooks-off/lifecycle.spec.ts:59` — /clear on internal agent reassigns the same character via JSONL polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:137` — /resume at startup reassigns the same agent via JSONL polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:183` — /clear edge case with a sibling agent in the same projectDir via JSONL polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:268` — /clear retains its character after the terminal editor moves (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:346` — heuristic late --resume after stale cleanup prevents zombie agents (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:410` — three parallel Task subagents in one turn render distinct sub-characters via polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:478` — inline teammate removed from team config disappears within one second via polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:540` — rapid /clear then new tool within 500ms lands on the reassigned agent via polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:601` — close via X prevents re-adoption of old JSONL during dismissal cooldown via polling (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:675` — external basic subagent with run_in_background but no teamName routes to basic path (Hooks OFF / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:83` — /clear on internal agent reassigns the same character to the new JSONL (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:145` — /resume reassigns the same agent within the grace window (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:212` — /clear edge case with a sibling agent in the same projectDir (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:303` — --resume after the grace window expires cleans up the old agent (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:388` — three parallel Task subagents in one turn render distinct sub-characters (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:457` — inline teammate removed from team config disappears within one second (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:519` — lead SessionEnd cascade-removes active inline teammates (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:605` — external basic subagent with run_in_background routes to basic path (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:674` — lead permission_prompt routes bubble to teammate not lead when teammates exist (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:763` — TeammateIdle marks only the targeted teammate done and leaves lead unchanged (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:877` — rapid /clear then new tool within 500ms lands on the reassigned agent (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:935` — close via X prevents re-adoption of old JSONL during dismissal cooldown (Hooks ON / lifecycle)

### `@area:cross-cutting` (24 tests)

- `e2e/claude/hooks-off/lifecycle.spec.ts:735` — agentToolsClear fires at turn end via turn_duration JSONL record (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:800` — heuristic permission timer is cancelled when an agent is closed via overlay (Hooks OFF / lifecycle)
- `e2e/claude/hooks-off/lifecycle.spec.ts:879` — sub-agent permission bubble fires on stalled non-exempt sub-tool via heuristic timer (Hooks OFF / lifecycle)
- `e2e/claude/hooks-on/consent.spec.ts:125` — fresh install: the Intro pages to the disclosure and Install writes the hooks (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:217` — Not Now writes nothing, continues the tour, and leaves consent ungranted (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:244` — Don't Ask Again writes nothing and persists hooks off (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:269` — the close x aborts the tour and writes nothing, exactly like Not Now (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:298` — Back from the closing step lets Don't Ask Again undo a landed install (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:334` — Back after Don't Ask Again lets Not Now bring the ask back (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:381` — a failed install is reported, and Not Now brings the ask back (Hooks consent gate › when settings.json cannot be parsed)
- `e2e/claude/hooks-on/consent.spec.ts:427` — clicking the office around the bubble neither answers nor dismisses (Hooks consent gate)
- `e2e/claude/hooks-on/consent.spec.ts:464` — a pre-consent 14-event install migrates to 12 with no prompt (Hooks consent gate / pre-consent install)
- `e2e/claude/hooks-on/consent.spec.ts:504` — Settings toggle removes the migrated hooks and keeps third-party entries (Hooks consent gate / pre-consent install)
- `e2e/claude/hooks-on/consent.spec.ts:552` — a failed uninstall does not persist hooks-off (Hooks consent gate / toggle-off failure)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1034` — done sound chime fires on agentStatus waiting (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1127` — restored agents skip the matrix spawn animation (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1211` — tool status text matches every PreToolUse tool name (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1306` — permission sound chime fires on agentToolPermission (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1428` — pixel-agents hook is installed in settings.json on extension startup (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1450` — hook install and uninstall round-trip via the Settings toggle (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1504` — permission bubble auto-clears when a fresh PreToolUse arrives (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1578` — settings toggles persist across a webview reload (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1626` — layout editor enter paint save persist and exit round-trip (Hooks ON / lifecycle)
- `e2e/claude/hooks-on/lifecycle.spec.ts:1707` — hook uninstall preserves a pre-existing third-party hook entry (Hooks ON / lifecycle)

### `@area:teams` (7 tests)

- `e2e/claude/hooks-on/teams.spec.ts:64` — internal terminal lead with inline teammate routes tools to teammate (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:121` — internal terminal lead with tmux teammate routes tools to teammate (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:195` — new-harness background agent becomes a named teammate character (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:287` — unnamed background spawn stays a sub-agent with live activity and survives Stop (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:358` — named background spawn becomes a teammate and badges the spawner LEAD (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:422` — external session lead with inline teammate routes tools to teammate (Hooks ON / teams)
- `e2e/claude/hooks-on/teams.spec.ts:494` — external session lead with tmux teammate routes tools to teammate (Hooks ON / teams)

### `@area:matrix` (6 tests)

- `e2e/claude/hooks-off/matrix.spec.ts:49` — internal basic spawn adopted via JSONL polling (Hooks OFF / matrix)
- `e2e/claude/hooks-off/matrix.spec.ts:87` — internal inline teammate adopted via JSONL polling (Hooks OFF / matrix)
- `e2e/claude/hooks-off/matrix.spec.ts:143` — internal tmux teammate adopted via JSONL polling (Hooks OFF / matrix)
- `e2e/claude/hooks-off/matrix.spec.ts:208` — external basic spawn adopted via JSONL polling (Hooks OFF / matrix)
- `e2e/claude/hooks-off/matrix.spec.ts:253` — external inline teammate adopted via JSONL polling (Hooks OFF / matrix)
- `e2e/claude/hooks-off/matrix.spec.ts:312` — external tmux teammate adopted via JSONL polling (Hooks OFF / matrix)

### `@area:standalone` (11 tests)

- `e2e/standalone/hooks.spec.ts:17` — propagates hook-driven lifecycle into the browser UI (Standalone / hooks)
- `e2e/standalone/hooks.spec.ts:134` — the tokened page shows the Intro and Install writes the hooks (Standalone / hooks consent)
- `e2e/standalone/hooks.spec.ts:161` — an untokened spectator page never sees the consent dialog (Standalone / hooks consent)
- `e2e/standalone/hooks.spec.ts:190` — the hooks checkbox reflects install state and its click is the consent grant (Standalone / hooks consent)
- `e2e/standalone/multi-server-hooks.spec.ts:31` — extension and standalone both stay hook-driven without cross-contamination (Standalone / multi-server hooks)
- `e2e/standalone/ui.spec.ts:27` — closeAgent despawns the character (Standalone / UI)
- `e2e/standalone/ui.spec.ts:61` — Debug View renders JSONL diagnostics in standalone (Standalone / UI)
- `e2e/standalone/ui.spec.ts:95` — adding an external asset directory triggers a live asset reload (Standalone / UI)
- `e2e/standalone/ui.spec.ts:125` — browser Export Layout downloads the layout file (Standalone / UI)
- `e2e/standalone/ui.spec.ts:140` — browser Import Layout applies the chosen file (Standalone / UI)
- `e2e/standalone/ui.spec.ts:175` — ConnectionIndicator appears when the WebSocket connection drops (Standalone / UI)

### `@area:areas` (8 tests)

- `e2e/claude/hooks-off/areas-multiroot.spec.ts:49` — painting an area labels tiles in the layout (Areas (multi-root))
- `e2e/claude/hooks-off/areas-multiroot.spec.ts:77` — areas can be added and removed (Areas (multi-root))
- `e2e/claude/hooks-off/areas-multiroot.spec.ts:110` — a folder can be mapped to an area and the mapping persists (Areas (multi-root))
- `e2e/claude/hooks-off/areas-multiroot.spec.ts:169` — an agent for the MAPPED folder takes a seat inside its area (Areas (multi-root) › seat preference (alpha → Engineering))
- `e2e/claude/hooks-off/areas-multiroot.spec.ts:200` — an agent for an UNMAPPED folder is not forced into the area (Areas (multi-root) › seat preference (alpha → Engineering))
- `e2e/claude/hooks-off/areas.spec.ts:39` — seeded areas + areaTiles load and showAreas is effective (Areas (single-folder) › seeded area data + show-areas state)
- `e2e/claude/hooks-off/areas.spec.ts:71` — the Areas tool button is hidden without workspace folders (Areas (single-folder))
- `e2e/claude/hooks-off/areas.spec.ts:90` — the Areas tool button is visible with a seeded areas layout (Areas (single-folder) › seeded areas layout (positive gate))

### `@area:carpet` (8 tests)

- `e2e/claude/hooks-off/carpet.spec.ts:50` — carpet sprites load + broadcast, and the Carpet category renders variants (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:71` — painting a tile records it in the carpet layer (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:88` — autotiling: the junction case reflects neighboring carpet tiles (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:129` — erasing removes a carpet tile (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:152` — the carpet eyedropper copies a tile’s variant (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:180` — a carpet stroke is a single undo entry (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:201` — carpet tiles persist across a save + panel reload (Carpet)
- `e2e/claude/hooks-off/carpet.spec.ts:297` — a seeded carpet coexists with furniture on the same tile (Carpet surface placement (seeded))

### `@area:desktop-agent` (2 tests)

- `e2e/desktop/agentRun.spec.ts:192` — a prompt to the seeded Codex agent streams its events in order to the renderer, and stopping and quitting leave no process (Desktop shell running an agent over ACP)
- `e2e/desktop/agentRun.spec.ts:298` — an adapter that crashes mid-run puts the agent in error, never completed, and a new start works (Desktop shell running an agent over ACP)

### `@area:desktop-runtime` (2 tests)

- `e2e/desktop/realRuntime.spec.ts:54` — the shell and the runtime complete the handshake, the renderer gets the snapshot, and quitting leaves no java process (Desktop shell with the real local runtime)
- `e2e/desktop/realRuntime.spec.ts:111` — a shell that ends abruptly leaves no java process: the runtime notices and exits by itself (Desktop shell with the real local runtime)

### `@area:desktop-shell` (11 tests)

- `e2e/desktop/runtime.spec.ts:35` — a runtime that never prints the ready line is killed and gets the failure dialog, and 종료 quits (Desktop shell runtime supervision)
- `e2e/desktop/runtime.spec.ts:65` — a runtime that exits before ready: 다시 시작 starts exactly one new runtime, 종료 quits (Desktop shell runtime supervision)
- `e2e/desktop/runtime.spec.ts:95` — quitting the app ends a runtime that exits on stdin EOF (Desktop shell runtime supervision)
- `e2e/desktop/runtime.spec.ts:112` — quitting the app kills a runtime that ignores stdin EOF, with its child process (Desktop shell runtime supervision)
- `e2e/desktop/runtime.spec.ts:140` — a shell that ends abruptly still lets the runtime clean up its child on stdin EOF (Desktop shell runtime supervision)
- `e2e/desktop/shell.spec.ts:20` — opens the office from app://bundle and joins the runtime with its token (Desktop shell)
- `e2e/desktop/shell.spec.ts:80` — every app://bundle response carries the CSP, and a missing path cannot be framed (Desktop shell)
- `e2e/desktop/shell.spec.ts:126` — the Settings modal lists only what works in the desktop shell (Desktop shell)
- `e2e/desktop/shell.spec.ts:153` — a runtime that refuses the handshake leaves the office up and the renderer reconnecting (Desktop shell)
- `e2e/desktop/shell.spec.ts:162` — cancelling the folder picker returns null and leaves the screen as it was (Desktop shell)
- `e2e/desktop/shell.spec.ts:203` — the renderer cannot open windows or navigate away from app://bundle (Desktop shell)

### `@area:pets` (3 tests)

- `e2e/claude/hooks-off/pets.spec.ts:90` — pet sprites load, broadcast, and expose manifest names in the editor (Pets)
- `e2e/claude/hooks-off/pets.spec.ts:117` — placing a pet toggles it on/off and persists across a panel reload (Pets)
- `e2e/claude/hooks-off/pets.spec.ts:202` — clicking a pet shows a heart bubble that auto-dismisses and dismisses on re-click (Pets)

<!-- END:E2E-INVENTORY -->

## Coverage philosophy

We do not measure e2e via code coverage (too noisy, doesn't map to user-observable scenarios). Coverage is tracked by:

1. **The inventory section above** — every test in the suite with its area tag and file:line.
2. **The "What's NOT covered" gap list** — deliberately maintained; closing a gap removes the corresponding row.
3. **Allure dashboard** — `epic` / `feature` / `story` labels group tests by area without needing this file. Run `npm run test:report` after a suite run, then open `allure-report/allure/index.html` → Behaviors view.
