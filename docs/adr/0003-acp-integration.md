# ADR 0003: ACP 연동 방식

## 상태

Accepted (2026-10-23 팀 결정). Claude Code 어댑터를 설치 파일에 동봉할지만 Anthropic 조건을 확인한 뒤 정한다 (결정 12, 열린 질문).
ACP를 stdio로 쓰고 Java ACP Client를 둔다는 것은 [ADR 0002](0002-java-spring-runtime.md)와 Jira로 이미 정해졌다. 이 ADR은 그 위의 세부(프로토콜 버전, SDK, capability, 실행 명령)를 정한다.
Codex 연결을 구현하며 포트 이름(결정 4), 개발 중 Codex 실행 명령(결정 8, 실행 명령 표), 셸이 런타임에 넘기는 Node(결정 12)를 정했고 열린 질문 두 개(`npx` 실행, 사용자 정의 통지 수신)를 닫았다. SDK가 건너뛰는 메시지(감지 표)와 로그인 브라우저의 위험(결과)을 적었다 (2026-10-26, ACP PR). `ELECTRON_RUN_AS_NODE`를 어댑터 자신의 Node 프로세스에만 두는 preload(결정 12)와, 로그인 동안 어댑터의 새 프로세스를 job object 밖으로 내보내는 규칙(결과)을 정하고, 열린 질문 하나(`ELECTRON_RUN_AS_NODE`를 뺄 방법)를 닫았다 (2026-10-30, Codex 실행 보강 PR).

## 날짜

2026-10-21

## 맥락

- Jira: SCRUM-9는 `initialize`와 capability 협상, `session/new`·`session/prompt`·`session/update`·`session/cancel` 처리, 여러 세션 동시 관리, 실행기(Harness) 2종 이상을 요구한다. SCRUM-23은 Codex를 먼저 연결하고, SCRUM-66은 Claude Code를 같은 인터페이스로 연결한다. SCRUM-24는 실행(Run) 취소다. post-MVP로 SCRUM-50(잘못된 메시지 격리)과 SCRUM-57(브리지)이 있다.
- 버전: 안정 프로토콜 버전은 정수 `1`이다. 스키마 artifact의 semver(최신 stable `schema-v1.26.0`, 2026-10-16)는 wire 버전이 아니다 ([ACP README](https://github.com/agentclientprotocol/agent-client-protocol/blob/main/README.md), [schema-v1.26.0](https://github.com/agentclientprotocol/agent-client-protocol/releases/tag/schema-v1.26.0)). v2는 pre-release(`2.0.0-alpha.9`)이고 v1과 호환되지 않는다 ([schema/v2](https://github.com/agentclientprotocol/agent-client-protocol/tree/main/schema/v2)). (2026-10-20 기준)
- 전송: stdio는 줄 단위 JSON-RPC 2.0이고 UTF-8이다. 메시지 안에는 줄바꿈이 없다. stdout은 ACP 전용이고, 로그는 stderr에 쓸 수 있다 ([Transports](https://agentclientprotocol.com/protocol/transports)).
- 역할: 어댑터도 클라이언트에 요청을 보낸다(`session/request_permission`, `fs/*`, `terminal/*`). 그래서 클라이언트도 응답자여야 한다. 광고하지 않은 capability는 지원하지 않는 것으로 본다 ([Initialization](https://agentclientprotocol.com/protocol/initialization)).
- codex-acp, claude-agent-acp, gemini, opencode에서 클라이언트의 `terminal/create`를 쓰는 곳을 찾지 못했다 (코드 검색, 추정).
- 실행 확인 (2026-10-24, codex-acp 2.2.2, Java SDK 0.80.0): Windows에서 `ProcessBuilder("npx")`는 `CreateProcess error=2`로 실패한다. `npx.cmd`와 `cmd.exe /c`는 되지만 cmd.exe가 인자를 다시 해석한다. `node <codex-acp>/dist/index.js`를 직접 띄우면 된다. codex-acp의 `_auth/status_update`는 SDK의 `extNotificationHandler`로 받는다. SDK가 모르는 update(`notice`, compaction, codex-acp의 `subagent_*`)는 `UnknownSessionUpdate`로 온다. SDK는 응답한 `protocolVersion`을 확인하지 않는다.
- 실행 확인 (2026-10-26, ACP PR): 런타임이 빈 `CODEX_HOME`으로 codex-acp 2.2.2를 띄워 `initialize`(`authMethods` `api-key`, `chat-gpt`, 인증 상태 kind `none` "Not logged in")와 `session/new`(`-32000` Authentication required)까지 확인했다. `authenticate`는 보내지 않았다(브라우저 로그인을 연다).
- 실행 확인 (2026-10-26, ACP PR): Electron 45.0.0의 실행 파일을 `ELECTRON_RUN_AS_NODE=1`로 띄우면 Node 24.22.0으로 돌고, codex-acp 2.2.2의 `dist/index.js`가 `initialize`(0.8~2.3초)와 `session/new`(`-32000`)까지 돈다. codex-acp는 Codex를 `process.execPath`(같은 Electron)로 `@openai/codex/bin/codex.js`를 실행해 띄우므로, 그 자식도 이 변수로 Node가 된다. stdin EOF 뒤 종료 코드 0으로 끝났다. 셸이 넘기는 env로 띄운 런타임 jar도 같은 경로로 인증 단계까지 갔다: 인증 상태 알림이 오면 바로 `authenticate`, 알림이 없으면 `session/new`의 `-32000` 뒤에 `authenticate`를 보냈다. 이 두 확인에서는 어댑터 앞의 중계 스크립트가 `authenticate`를 어댑터에 넘기지 않았다.
- 실행 확인 (2026-10-30, Codex 실행 보강 PR): `ELECTRON_RUN_AS_NODE=1`로 띄운 Electron 45.0.0은 Node의 `--require`를 따른다. 미리 불러온 스크립트가 어댑터 코드보다 먼저 돌고, 거기서 바꾼 `child_process` 함수는 CJS의 `require`와 ESM의 `import { spawn }`(`syncBuiltinESMExports` 뒤) 모두에 보인다. 감싼 `execFile`에는 `util.promisify.custom`을 따로 두어야 한다: 없으면 `util.promisify(execFile)`이 `{stdout, stderr}` 대신 stdout 문자열로 끝나서, 이 형태를 쓰는 어댑터 코드(codex-acp에 묶인 `default-browser`, `open`의 WSL 경로 등)가 깨진다. codex-acp 2.2.2는 Codex를 ESM `import { spawn } from "node:child_process"`로 띄운다 (`dist/index.js`). Codex(`codex.js`)는 네이티브 `codex.exe`를 `{...process.env}`로 띄우고, Codex가 실행하는 명령은 그 env를 받는다.
- 실행 확인 (2026-10-23, claude-agent-acp 0.94.0, Claude Code 2.1.303): Claude 로그인 정보가 없는 격리 HOME에서 ADR 0004 결정 10의 Bedrock mock env로 실행했다. `initialize`의 `authMethods`는 비어 있고 `agentInfo.title`은 "Claude Agent"다. `_auth/status_update`는 kind `external`, label "AWS Bedrock"이다. `session/new`, `session/prompt`, 권한 요청(`allow_once`, `allow_always`, `reject_once`), 승인 뒤 Write 도구의 실제 파일 생성까지 통과했다.

## 결정

1. ACP 프로토콜 버전 1만 지원한다. `initialize`에 `protocolVersion: 1`을 보내고, 다른 값이 오면 연결을 닫고 `PROTOCOL_UNSUPPORTED`로 처리한다. v2는 범위 밖이다. 다만 "턴 종료"는 이벤트 모델에서 추상화해 둔다 ([ADR 0005](0005-agent-event-model.md)).
2. 공식 Java SDK `com.agentclientprotocol:acp-core`를 `0.80.0`(2026-10-20 기준 최신)으로 정확히 고정한다. JSON 매퍼는 `acp-json-jackson3`다 (Spring Boot 4.x, [ADR 0002](0002-java-spring-runtime.md) 결정 6, [java-sdk](https://github.com/agentclientprotocol/java-sdk)).
3. 어댑터 프로세스마다 `AcpAsyncClient` 하나를 `StdioAcpClientTransport`로 만든다. `acp-spring-boot-starter`의 자동 구성 클라이언트는 명령 하나에 클라이언트 하나만 만들기 때문에 쓰지 않는다 ([starter README](https://github.com/agentclientprotocol/java-sdk/blob/main/acp-spring-boot-autoconfigure/README.md)).
4. SDK는 자체 포트 인터페이스 뒤에 숨긴다: `AgentConnection`(어댑터 프로세스 1개와의 연결), `AgentConnectionFactory`(띄우기), `AgentConnectionListener`(어댑터가 먼저 보내는 update, 권한 요청, 인증 상태, 종료). 패키지는 `agentsroom.runtime.acp`다. AgentEvent로 바꾸는 일은 이 경계에서 한다: SDK 타입은 포트를 지나지 않고, 어댑터에서 온 문자열은 여기서 마스킹한다. 필요하면 얇은 NDJSON JSON-RPC 클라이언트로 바꿔 끼울 수 있게 한다.
5. client capability는 아래 capability 표를 따른다. `clientInfo.name`은 `agents-room`이다.
6. `session/new`에는 `cwd`로 프로젝트 절대 경로를, `mcpServers`로 `[]`를 보낸다. `session/load`와 `session/resume`은 MVP에서 쓰지 않고, SCRUM-48에서 capability를 확인한 뒤 쓴다. 모델은 어댑터가 `configOptions`(category `model`)를 주면 `session/set_config_option`으로 고르고, 아니면 spawn 시점의 설정으로 정한다. 실행기별 spike가 필요하다 ([Session Config Options](https://agentclientprotocol.com/protocol/session-config-options)).
7. 세션마다 진행 중인 실행은 하나다. 취소할 때는 `session/cancel` 통지를 보내고, 대기 중인 권한 요청 전부에 `cancelled`로 응답한 뒤, `stopReason`을 기다린다 ([Prompt Turn](https://agentclientprotocol.com/protocol/prompt-turn)). 상태와 결과는 ADR 0005가 정한다.
8. 실행 명령은 아래 실행 명령 표처럼 버전을 고정한다. 런타임은 어댑터를 셸 없이 띄운다: `npx`와 `cmd /c`를 쓰지 않는다. 개발 중 Codex는 저장소의 `tools/adapters/`(npm 워크스페이스가 아니다, `npm run adapters:install`)에 고정 설치한 codex-acp를 `node`로 직접 실행한다. 테스트와 디버깅에서는 `AGENTS_ROOM_HARNESS_<ID>_COMMAND`(JSON argv)가 그 명령을 대신하고, `AGENTS_ROOM_E2E=1`이면 이 변수가 없을 때 실행하지 않는다(실수로 실제 어댑터를 띄우지 않게). 설치판의 셸은 이 override와 `AGENTS_ROOM_ADAPTERS_DIR`를 런타임에 넘기지 않는다. [ADR 0001](0001-desktop-framework.md) 결정 5가 셸의 테스트 변수를 설치판에서 무시하는 것과 같은 이유다. 찾는 순서와 env는 [architecture.md](../architecture.md#어댑터-실행)에 있다. 폐기된 `@zed-industries/codex-acp`, `@zed-industries/claude-code-acp`, `@zed-industries/claude-agent-acp`는 쓰지 않는다 ([codex-acp](https://github.com/agentclientprotocol/codex-acp), [claude-agent-acp](https://github.com/agentclientprotocol/claude-agent-acp)). MVP 실행기는 Codex와 Claude Code다 (SCRUM-23, 66). Claude Code는 Claude 계정 로그인 없이 Bedrock(MVP는 mock)으로만 연결한다 ([ADR 0004](0004-model-authentication.md) 결정 4, 10). Gemini CLI, OpenCode, 그 밖의 실행기(SCRUM-10의 "OpenCode/Custom")는 MVP 뒤로 미룬다.
9. 로그: 어댑터 stderr는 신뢰할 수 없는 출력으로 보고, 마스킹([ADR 0004](0004-model-authentication.md))한 뒤에만 진단 로그에 남긴다. SDK 로거 `com.agentclientprotocol.sdk.client.transport.agent-stderr`는 INFO 수준에서 필터 없이 기록하므로 마스킹 계층을 거치게 한다. 페이로드 전체를 남기는 DEBUG/TRACE 로깅은 배포 빌드에서 끈다.
10. 알 수 없는 update(SDK의 `UnknownSessionUpdate`)는 ADR 0005의 `unknownUpdate`로 보낸다.
11. ACP 수준 오류는 아래 감지 표처럼 판정한다. 코드의 정의는 ADR 0005, 화면 문구는 [ux.md](../ux.md#문구-목록)에 있다.
12. 어댑터 배포: 아래 실행 명령 표의 명령은 개발용이다. 배포본은 어댑터를 셸인 Electron의 실행 파일에 `ELECTRON_RUN_AS_NODE=1`을 주어 일반 Node.js로 실행하고([Electron 환경 변수](https://www.electronjs.org/docs/latest/api/environment-variables)), 이를 위해 `runAsNode` fuse를 켜 둔다([Fuses](https://www.electronjs.org/docs/latest/tutorial/fuses)). 이 실행 방식과 fuse 유지는 2026-10-23에 팀이 합의했다. 지원 중인 Electron 43~45는 모두 Node 24를 내장하므로 claude-agent-acp의 요구(Node ≥ 22)를 만족한다 ([Electron releases](https://releases.electronjs.org/), 2026-10-23 기준). 그래서 사용자는 Node를 따로 설치하지 않는다 (SCRUM-36). 개발 실행(패키징하지 않은 셸)도 같은 방식이다: 셸은 런타임에 `AGENTS_ROOM_NODE`(자기 실행 파일 `process.execPath`)와 `AGENTS_ROOM_NODE_ELECTRON=1`을 넘기고, 런타임은 그 파일로 띄우는 어댑터의 env에만 `ELECTRON_RUN_AS_NODE=1`을 더한다. 셸 없이 띄운 런타임(테스트, 수동 실행)은 `PATH`의 node를 쓴다. 이 값은 어댑터 자신의 Node 프로세스에만 둔다 (2026-10-30): 그 실행은 `<electron> --require <preload> <어댑터> ...`이고, preload(`<AGENTS_ROOM_DATA_DIR>/runtime/electron-as-node.cjs`. 런타임 jar의 리소스를 데이터 디렉터리에 써서 개발 실행과 설치판이 같은 경로를 쓴다)가 어댑터 코드보다 먼저 이 값을 env에서 지운다. 어댑터가 자기 실행 파일(`process.execPath`)로 띄우는 자식(codex-acp의 Codex, Node `fork`)만 이 값과 preload를 다시 받는다. 그래서 Codex가 실행하는 명령과 그 자손은 이 값을 보지 않고, 그 명령이 띄운 Electron 앱(VS Code, 이 저장소의 셸 등)은 앱으로 돈다. `PATH`의 node로 띄우면 이 값도 preload도 더하지 않는다. 세부는 [architecture.md](../architecture.md#어댑터-실행)에 있다.
    - codex-acp: 패키지를 `node_modules`째 설치 파일에 넣는다. codex-acp와 그 안에 담기는 Codex(`@openai/codex`)는 둘 다 Apache-2.0이다 (npm 레지스트리, 2026-10-23 기준).
    - claude-agent-acp: 어댑터 자체는 Apache-2.0이지만, 의존하는 `@anthropic-ai/claude-agent-sdk`는 "© Anthropic PBC. All rights reserved"이고 플랫폼별 Claude Code 실행 파일을 함께 담는다. 제품에서 Claude Code를 실행하는 조건(아래 열린 질문)을 확인하기 전까지 동봉하지 않는다. MVP 확인은 개발 PC에서 `npx`로 실행한다.

| capability                                                       | 값            | 근거                                                                                                                            |
| ---------------------------------------------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `fs.readTextFile`, `fs.writeTextFile`, `terminal`                | `false`       | 어댑터가 파일과 명령을 직접 다룬다 (코드 검색, 추정)                                                                            |
| `auth.terminal`                                                  | `false`       | claude-agent-acp는 이 값이 `true`일 때만 Claude 계정 로그인 방법을 광고한다. Claude 계정 로그인은 쓰지 않는다 (ADR 0004 결정 4) |
| elicitation, `auth._meta.gateway`, unstable 기능(`subagents` 등) | 광고하지 않음 | URL elicitation은 ADR 0004의 열린 질문이다. gateway 인증은 쓰지 않는다 (ADR 0004). `subagents`는 ADR 0005                       |
| `_meta.terminal_output`                                          | 광고한다      | 셸 출력을 구조화된 형태로 받는다                                                                                                |

| `harnessId`   | 어댑터 실행 명령 (버전은 2026-10-20 기준)                                                                                     | 비고                                                                                                                                                         |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `codex`       | `<node> tools/adapters/node_modules/@agentclientprotocol/codex-acp/dist/index.js` (2.2.2, `tools/adapters/package-lock.json`) | MVP, SCRUM-23. 개발 실행이다. `<node>`는 셸의 Electron이다(결정 12). 배포본은 결정 12                                                                        |
| `claude-code` | `npx -y @agentclientprotocol/claude-agent-acp@0.94.0`                                                                         | MVP, Node ≥ 22, SCRUM-66. 모델은 Bedrock mock (SCRUM-67). 2026-10-23 수동 확인의 명령이다. 런타임은 아직 띄우지 않으며, 붙일 때 Codex처럼 고정 설치로 바꾼다 |
| `gemini-cli`  | `gemini --acp`                                                                                                                | MVP 이후. [ACP registry 항목](https://github.com/agentclientprotocol/registry/blob/main/gemini/agent.json)                                                   |
| `opencode`    | `opencode acp`                                                                                                                | MVP 이후. [OpenCode ACP](https://opencode.ai/docs/acp/)                                                                                                      |

| 감지원                                                                                                                                                  | code                                                                           |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| 프로세스 실행이 ENOENT로 실패                                                                                                                           | `EXECUTABLE_MISSING`                                                           |
| `initialize` 응답 전에 종료, 또는 stdout에 ACP가 아닌 출력                                                                                              | `ADAPTER_START_FAILED`                                                         |
| 응답한 `protocolVersion`이 1이 아님                                                                                                                     | `PROTOCOL_UNSUPPORTED`                                                         |
| `initialize` 오류 또는 시간 초과                                                                                                                        | `ACP_INIT_FAILED`                                                              |
| 어느 요청이든 JSON-RPC 오류 `-32000`. 단, 아래 `authenticate` 실패가 우선한다                                                                           | `AUTH_REQUIRED`                                                                |
| `authenticate` 요청 자체의 실패 (로그인 미완료, 취소, 만료)                                                                                             | `AUTH_FAILED`                                                                  |
| `session/new` 오류                                                                                                                                      | `SESSION_CREATE_FAILED`                                                        |
| `session/prompt` 오류 응답                                                                                                                              | `PROMPT_FAILED`                                                                |
| 해석할 수 없는 줄. 그 연결만 격리한다 (SCRUM-50)                                                                                                        | `PROTOCOL_ERROR`                                                               |
| JSON-RPC 메시지이지만 params가 스키마에 맞지 않음(필수 필드 없음). SDK가 경고 로그를 남기고 건너뛰며(요청에는 `-32602`로 답한다) 런타임에는 오지 않는다 | 없음. 이벤트도 상태 변화도 없다 ([ADR 0005](0005-agent-event-model.md) 전이표) |

## 검토한 대안

### Kotlin SDK

- 장점: JetBrains가 관리하는 공식 SDK다. `com.agentclientprotocol:acp` 0.33.0, Apache-2.0 (2026-10-20 기준, [kotlin-sdk](https://github.com/agentclientprotocol/kotlin-sdk)).
- 단점: Java에서 Kotlin API를 쓰는 비용이 있다 (추정).
- 판단: 대안으로 남긴다.

### 자체 JSON-RPC 구현

- 장점: 의존성이 없고 스키마를 바로 따라갈 수 있다.
- 단점: 양방향 요청, `$/cancel_request` 등을 직접 구현해야 한다.
- 판단: 대비책으로 남긴다. 결정 4의 포트가 교체 지점이다.

### Node sidecar의 TS SDK

- 장점: 공식 TS SDK `@agentclientprotocol/sdk`를 쓸 수 있다.
- 단점: SCRUM-66의 "Java ACP Client"와 맞지 않는다. 감독할 프로세스가 하나 더 생긴다.
- 판단: 채택하지 않는다.

### ACP v2 동시 지원

- 장점: v2가 안정화되면 옮기는 비용이 줄어든다.
- 단점: alpha이고 v1과 호환되지 않으며 안정화 일정이 없다.
- 판단: 채택하지 않는다. 턴 종료만 추상화해 둔다 (결정 1).

## 결과

- 좋은 점: 공식 SDK가 양방향 JSON-RPC와 `$/cancel_request`를 처리한다. 실행기를 추가해도 같은 포트를 다시 쓴다 (SCRUM-23 인수 조건).
- 비용과 위험:
  - SDK는 아직 0.x라 버전 사이 호환을 보장하지 않는다(0.80.0은 0.18.0과 바이너리 호환되지 않는다, [CHANGELOG](https://github.com/agentclientprotocol/java-sdk/blob/main/CHANGELOG.md)). 유지보수자도 적다.
  - SDK가 스키마를 늦게 따라온다. `notice`, compaction, `subagent_update`는 `UnknownSessionUpdate`로만 오고, `ClientCapabilities`에는 `subagents` 필드가 없다 ([AcpSchema.java](https://github.com/agentclientprotocol/java-sdk/blob/main/acp-core/src/main/java/com/agentclientprotocol/sdk/spec/AcpSchema.java)).
  - stdio 송신 큐에 크기 제한이 없다는 알려진 문제가 있다. 제한 큐는 0.81.0에 예정되어 있다 ([CHANGELOG](https://github.com/agentclientprotocol/java-sdk/blob/main/CHANGELOG.md) 0.80.0 Known issues).
  - 어댑터를 동봉하면(결정 12) 설치 파일이 커지고, 어댑터 버전은 앱을 새로 릴리스할 때만 바뀐다.
  - `ELECTRON_RUN_AS_NODE`의 preload(결정 12)는 `child_process`의 `spawn`, `spawnSync`, `execFile`, `execFileSync`, `fork`를 바꿔서 자식을 고른다. 어댑터가 이 함수를 거치지 않고 자기 실행 파일을 띄우면(셸을 거치는 `exec`·`execSync`, 네이티브 애드온 등) 그 자식은 Node가 아니라 Electron 앱으로 돈다. codex-acp는 이 함수만 쓴다 (코드 확인, 2026-10-30). 데이터 디렉터리에 preload를 쓰지 못하면 런타임은 경고를 남기고 예전처럼 이 값만 넘긴다(자식 전부가 이 값을 받는다).
  - codex-acp의 `chat-gpt` 로그인은 어댑터 프로세스가 로그인 페이지를 연다(Windows는 PowerShell `Start`). 브라우저가 떠 있지 않았으면 새 브라우저가 어댑터의 자손이 되고, 런타임의 kill-on-close job object(Windows) 안에서 생겨 앱을 끌 때 함께 끝났다. 그래서 런타임은 어댑터마다 자기 job 안에 job을 하나 더 두고(중첩 job, `process.AdapterJob`), `authenticate`가 진행되는 동안(성공, 실패, 5분 시간 초과, 중지까지)만 그 job과 런타임의 job에 `JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK`를 켠다. 여러 에이전트가 함께 로그인하면 런타임의 job은 그 수를 세고, 마지막 로그인이 끝날 때 다시 막는다. 그동안 그 어댑터 트리가 새로 띄운 프로세스(로그인 페이지의 브라우저)는 job 밖에서 생겨 앱을 꺼도 남는다. 다른 어댑터의 트리는 자기 job이 막으므로 그동안에도 묶여 있다. 어댑터 트리 종료(`acp.ProcessTreeWatch`)와 런타임이 끝날 때의 정리(`ProcessTreeKiller.killDescendants`, 유예 시간 뒤의 강제 종료 포함)도 job을 빠져나간 프로세스는 끝내지 않는다. Node(libuv)가 띄운 직속 자식(PowerShell 등)은 libuv가 자기 job에 다시 넣으므로 묶인 채로 남는다 (그 job이 이미 어댑터의 job 안에 있을 때다. codex-acp는 시작할 때 Codex를 띄우므로 그렇다). 런타임은 로그인이 시작되고 끝날 때 로그를 남긴다. mock의 브라우저 대역으로 성공, 5분 제한 시간 초과(짧게 줄여서), 로그인 중 연결 종료 경로를 확인했고(`RuntimeSignInTests`, `ChildProcessContainmentTests`, 데스크톱 e2e), 실제 로그인으로는 미검증이다. 남는 한계:
    - 로그인 동안 그 어댑터 트리가 띄운 다른 프로세스도 같이 빠져나간다. 로그인 중에는 세션이 없어 Codex가 명령을 실행하지 않는다 (추정).
    - 그동안 런타임이 직접 띄우는 프로세스(다른 에이전트의 어댑터, 연결 확인)는 런타임의 job을 잠깐 벗어났다가 바로 다시 들어간다. 그 사이(밀리초)에 런타임이 죽으면 남을 수 있다. 어댑터가 자기 job에 들어가기 전에 띄운 자식은 런타임의 job에만 있다 (Node가 어댑터 코드를 불러오기 전이라 실제로는 없다).
    - 셸은 앱을 끄고 3초 안에 런타임이 끝나지 않으면 `taskkill /T /F`로 런타임 트리를 끝낸다. 이 명령은 부모 관계를 따라가므로, 그때 브라우저를 띄운 프로세스(PowerShell)가 아직 살아 있으면 브라우저도 끝난다. codex-acp의 PowerShell은 브라우저를 띄우고 바로 끝나므로 브라우저는 보통 트리 밖에 있다.
    - Windows 밖에는 job object가 없다. 런타임이 정상 종료할 때의 정리(`ProcessTreeKiller.killDescendants`)는 아직 런타임의 자손으로 남은 브라우저를 끝낼 수 있다(브라우저를 띄우는 방식에 따라 다르다, macOS·Linux 배포는 SCRUM-64).
    - 로그인이 끝난 뒤에 뜬 브라우저는 다시 묶인다. 이미 떠 있던 브라우저는 처음부터 런타임 밖에 있다. 셸이 로그인 URL을 `shell.openExternal`로 여는 방식(URL elicitation과 `chat-gpt-device-code`, [ADR 0004](0004-model-authentication.md) 열린 질문)은 그대로 대안이다.
- 후속 작업: 실제 로그인으로 뜬 브라우저가 에이전트 중지, 로그인 시간 초과, 앱 종료 뒤에도 살아 있는지 확인(ADR 0004의 Codex 브라우저 로그인 확인과 함께. mock의 대역으로는 확인했다), 실행기별 모델 선택 spike, 어댑터 동봉 빌드(패키징한 셸의 `runAsNode` fuse와 preload로 실행 확인), Claude Code 실행 명령을 Codex처럼 고정 설치로 바꾸기.

### 열린 질문

- 제품에서 Claude Code를 실행하는 조건. Agent SDK는 Commercial Terms 아래에서 고객에게 내놓는 제품에도 쓸 수 있다 ([Agent SDK](https://code.claude.com/docs/en/agent-sdk/overview#license-and-terms)). 제품에 Claude Code를 미리 설치하거나 실행하려면 Commercial Terms에 동의하고, 실행 파일을 고치지 않고, 내장 인증 방식(Claude 계정, API key)을 없애거나 제한하지 않으며, 사용자마다 자기 자격증명(Bedrock 포함)을 쓰고, 사용량을 대신 결제·재판매·중개하지 않아야 한다 ([Legal and compliance](https://code.claude.com/docs/en/legal-and-compliance), 2026-10-23 확인). 배포 전에 Anthropic에 확인한다: Claude 계정 로그인을 띄우지 않고 Bedrock만 쓰게 하는 것이 "인증 방식을 제한하지 않는다"에 맞는지, 배포 서비스의 Bedrock 비용을 누가 내는지. 확인 전에는 동봉하지 않고 설치 안내만 한다 (SCRUM-36).
- java-sdk가 `notice`, compaction, `subagents`를 모델링할지 (0.81.x 포함, 공개 로드맵 없음). 지금은 `UnknownSessionUpdate`로만 온다 (맥락).
- ACP v2의 안정화 시점 (명시된 곳 없음).
- OpenCode의 plan 송신과 Gemini의 터미널 동작 (코드 검색으로 추정한 것뿐이다).

## 관련 이슈

SCRUM-9, 10, 23, 24, 36, 48, 50, 57, 66, 67 / [ADR 0001](0001-desktop-framework.md), [ADR 0002](0002-java-spring-runtime.md), [ADR 0004](0004-model-authentication.md), [ADR 0005](0005-agent-event-model.md) / [architecture.md#주요-흐름](../architecture.md#주요-흐름)
