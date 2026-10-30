# 아키텍처

이 문서는 agents-room의 목표 구조를 설명한다. 구현을 시작한 것은 [데스크톱 셸](#데스크톱-셸)(`desktop/`)과 렌더러의 데스크톱 분기와 화면([데스크톱 렌더러 화면](#데스크톱-렌더러-화면)), 로컬 런타임([`runtime/`](#로컬-런타임-코드-구성): 프로젝트 열기와 Codex ACP 연결)뿐이다. 나머지 코드는 아직 pixel-agents 기준선([`d1e007a`](upstream.md#포크-기준)) 그대로이고, 나머지 구성요소는 계획이다.
화면과 문구는 [ux.md](ux.md), 결정과 대안은 [ADR](adr/README.md), 업스트림 코드 상세는 [upstream.md](upstream.md)에 있다.

## 용어집

| 본문 표기       | 영어 / 식별자                      | 정의                                                                                                                                                                                                                                                                                          | Jira                     |
| --------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ |
| 에이전트        | Agent, `agentId`                   | 프로젝트에 등록된 실행 단위다. 실행기·모델 제공자·모델·인증 방식의 조합(AgentConfig)을 가진다. 시작하면 ACP 어댑터 프로세스 1개, ACP 세션 1개와 연결되고, 캐릭터 1개로 표시된다. MVP에서는 에이전트마다 프로세스·세션·실행이 동시에 최대 1개다.                                               | SCRUM-10, 23, 28         |
| 서브에이전트    | Sub-agent                          | 에이전트가 실행 중에 스스로 만든 하위 에이전트다. ACP가 자식 세션 ID와 부모 관계를 실제로 보고할 때만 별도 캐릭터로 만들며, 캐릭터 표시는 post-MVP다(SCRUM-56). 사용자가 연결한 워크플로우의 다음 에이전트와는 다른 개념이다.                                                                 | SCRUM-6, 21, 29, 56      |
| 실행기          | Harness, `harnessId`               | 코딩 에이전트 제품이다. MVP는 Codex와 Claude Code이고(SCRUM-23, 66), Gemini CLI·OpenCode·Custom 같은 나머지는 그 뒤에 붙인다([ADR 0003](adr/0003-acp-integration.md)). Jira의 "Harness", "실행기", "Agent 종류"는 같은 뜻이다.                                                                | SCRUM-9, 10, 26          |
| ACP 어댑터      | ACP adapter                        | 실행기를 ACP의 Agent 역할로 노출하는 실행 파일이다. 로컬 런타임이 자식 프로세스로 띄우고 stdio로 JSON-RPC를 주고받는다. 예: `codex-acp`, `claude-agent-acp`. ACP가 없는 실행기를 감싸는 것은 "브리지"라고 부른다(post-MVP).                                                                   | SCRUM-23, 57, 66         |
| Java ACP Client | Java ACP Client                    | 로컬 런타임 안에서 ACP의 Client 역할을 하는 구성요소다. 어댑터 프로세스마다 연결이 1개다.                                                                                                                                                                                                     | SCRUM-23, 66             |
| 모델 제공자     | Model Provider, `providerId`       | 모델을 서비스하는 곳이다: Amazon Bedrock(MVP는 mock), OpenAI(ChatGPT 로그인, MVP 확인용). 배포 서비스의 모델은 Bedrock으로 제공한다. 실행기와는 별개다.                                                                                                                                       | SCRUM-10, 25, 65         |
| 모델            | Model, `modelId`                   | 제공자의 모델 ID 또는 inference profile ID다. 지정하지 않으면 실행기 기본값을 쓴다. 실제로 쓰인 모델은 어댑터가 보고한 값만 표시한다.                                                                                                                                                         | SCRUM-25, 26             |
| 인증 방식       | Auth method, `authMethodId`        | 실행기가 모델 제공자에 인증하는 경로다: ChatGPT 로그인(MVP 확인용), AWS profile. Claude 계정 로그인은 쓰지 않는다. 요금제(plan)와는 별개이며, 요금제는 앱이 설정하지 않는다([ADR 0004](adr/0004-model-authentication.md)).                                                                    | SCRUM-65, 25             |
| 자격증명 참조   | Credential reference               | 앱이 저장하는 값 중 비밀이 아닌 참조값이다(AWS profile 이름, region). 비밀 원문은 저장하지 않는다.                                                                                                                                                                                            | SCRUM-34, 58, 65         |
| 프로젝트        | Project, `projectId`               | 사용자가 등록한 로컬 폴더다. 검증된 절대 경로가 ACP `session/new`의 `cwd`가 된다.                                                                                                                                                                                                             | SCRUM-13, 31             |
| 워크스페이스    | Workspace                          | 프로젝트 하나와 그 프로젝트의 에이전트 설정, 레이아웃, 실행 상태를 묶은 단위다. MVP에서는 프로젝트와 1:1이다.                                                                                                                                                                                 | SCRUM-13                 |
| 세션            | Session (ACP session), `sessionId` | 어댑터가 `session/new`로 발급하는 대화 단위다. 어댑터 프로세스 1개 안에서 열리고, 그 안에서 실행이 차례로 일어난다.                                                                                                                                                                           | SCRUM-9, 23              |
| 실행            | Run, `runId`                       | 사용자 요청 1건, 곧 `session/prompt` 1회의 처리다([ADR 0005](adr/0005-agent-event-model.md)). Jira는 "실행"을 프로세스·세션·프롬프트 단위로 섞어 쓴다(SCRUM-23, 28, 47, 60). 이 문서 세트는 프롬프트 단위로 정하고, SCRUM-28의 "Agent 실행"은 에이전트로 읽는다.                              | SCRUM-23, 24, 28, 47, 60 |
| Tool 호출       | Tool call, `toolCallId`            | 에이전트가 보고한 개별 동작이다(파일 읽기, 편집, 명령 실행 등). ACP `tool_call`과 `tool_call_update`로 오며 `kind`와 `status`를 가진다.                                                                                                                                                       | SCRUM-19, 27             |
| AgentEvent      | AgentEvent                         | 로컬 런타임이 ACP 메시지와 프로세스 생명주기를 정규화한 공통 이벤트다. 렌더러는 AgentEvent, 상태 스냅샷, 명령 결과(`projectOpened` 등)만 받는다. 정의는 [ADR 0005](adr/0005-agent-event-model.md)에 있다.                                                                                     | SCRUM-11, 23, 66         |
| 에이전트 상태   | Agent status                       | 캐릭터와 Detail Panel이 보여주는 상태값이다. 값과 표시는 [ux.md](ux.md#상태-어휘), 전이 규칙은 [ADR 0005](adr/0005-agent-event-model.md)에 있다.                                                                                                                                              | SCRUM-6, 11              |
| 캐릭터          | Character                          | 픽셀 월드에서 에이전트 1개를 나타내는 스프라이트다. 상태와 행동은 AgentEvent로만 바뀐다.                                                                                                                                                                                                      | SCRUM-11, 27, 28         |
| Detail Panel    | Detail Panel                       | 캐릭터를 선택하면 화면 오른쪽에 열리는 패널이다. 선택한 에이전트의 요청, 세션 상태, 현재 Tool, 이벤트 순서, 결과를 보여준다.                                                                                                                                                                  | SCRUM-6, 19              |
| 권한 요청       | Permission request, `permissionId` | 에이전트가 위험할 수 있는 동작 전에 보내는 ACP `session/request_permission` 요청이다. 사용자가 고른 선택지로 원래 요청에 응답해야 한다. 워크플로우의 승인 단계와는 다르다.                                                                                                                    | SCRUM-33, 45             |
| 워크플로우      | Workflow (post-MVP)                | 사용자가 GUI에서 여러 에이전트를 연결해 만든 실행 흐름이다. 자체 실행 ID를 가지며, 서브에이전트 관계와 구분해서 표시한다.                                                                                                                                                                     | SCRUM-12, 20, 29, 30     |
| Capability      | Capability                         | ACP `initialize`에서 클라이언트와 어댑터가 서로 알린 기능이다. 알리지 않은 기능은 지원하지 않는 것으로 본다.                                                                                                                                                                                  | SCRUM-9, 10, 50          |
| 데스크톱 셸     | Desktop shell                      | 창, OS 대화상자, 앱 생명주기, 설치와 업데이트를 맡는 껍데기다([ADR 0001](adr/0001-desktop-framework.md)). 에이전트 프로세스를 직접 띄우지 않는다.                                                                                                                                             | SCRUM-8, 17              |
| 로컬 런타임     | Local runtime                      | 셸이 띄우는 Java/Spring 프로세스다([ADR 0002](adr/0002-java-spring-runtime.md), 띄우는 방식은 [ADR 0001](adr/0001-desktop-framework.md)). 프로젝트, 에이전트 설정, 프로세스, ACP 연결, 이벤트 정규화, 권한 중개, 저장을 맡는다. Jira의 "Spring 런타임", "로컬 런타임", "데스크톱 런타임"이다. | SCRUM-8, 22, 23          |
| 렌더러          | Renderer                           | 픽셀 월드와 패널을 그리는 웹 UI다(현재 `webview-ui/`). 표시와 입력만 맡으며 프로세스, 파일, 비밀에 직접 접근하지 않는다.                                                                                                                                                                      | SCRUM-8, 22              |

표기 규칙:

- 처음 나올 때는 "실행(Run)", "실행기(Harness)"처럼 괄호로 영어를 병기하고, 그다음부터는 본문 표기만 쓴다.
- "자식 에이전트", "하위 Agent"는 Jira 문구를 인용할 때만 쓰고, 본문에서는 "서브에이전트"로 쓴다.
- "브리지"는 ACP가 없는 실행기를 감싸는 것(SCRUM-57)에만 쓰고, ACP 어댑터를 브리지라고 부르지 않는다. "서버"는 로컬 런타임의 동의어로 쓰지 않는다. agents-room은 사용자가 URL을 입력하는 웹 서버가 아니다(SCRUM-36).
- 업스트림 pixel-agents의 Sub-agent·Teammate·Headless·Hooks 모드 정의는 쓰지 않는다. 뜻이 다르다([upstream.md](upstream.md#레거시-참조)).

## 구성도

```text
Desktop shell (ADR 0001): 창, 메뉴, OS 대화상자(폴더 선택), 설치와 업데이트
 ├─ Renderer (webview-ui: React + Canvas)
 │      ▲ AgentEvent, 상태 스냅샷      ▼ 명령 (목록은 ADR 0005)
 │      계약: core/asyncapi.yaml (ADR 0005), 전송 방식: ADR 0001
 └─▶ Local runtime (Java/Spring, ADR 0002): 띄우는 방식은 ADR 0001
        ├─ Renderer gateway: 렌더러와 통하는 유일한 통로
        ├─ Project/Workspace service
        ├─ Agent config registry
        ├─ Process supervisor ──spawn──▶ ACP adapter 자식 프로세스 (에이전트마다 1개)
        ├─ ACP client + session manager ◀── stdio JSON-RPC (ADR 0003) ──▶ codex-acp, claude-agent-acp
        ├─ Event normalizer (+ 상태 저장소)
        ├─ Permission broker
        └─ Persistence (앱 데이터 디렉터리)
ACP adapter ── 어댑터 자체 인증과 모델 호출 (ADR 0004) ──▶ Amazon Bedrock (MVP는 mock 서버), OpenAI(ChatGPT, MVP 확인용)
```

- 렌더러는 에이전트 프로세스를 띄우지 않는다. 프로세스의 시작과 종료는 로컬 런타임이 맡는다 (SCRUM-22).
- 에이전트마다 자식 프로세스와 ACP 연결이 따로 있다. 한 에이전트의 오류나 잘못된 메시지는 다른 에이전트로 번지지 않는다 (SCRUM-24, 50, 66).
- ACP 메시지는 런타임 밖으로 나가지 않는다. 렌더러는 정규화된 AgentEvent만 받으므로, 실행기가 달라도 같은 런타임 API를 쓴다 (SCRUM-9).
- ACP 어댑터는 실행기 실행, Tool 실행, 모델 호출, 자체 자격증명 저장을 맡고 agents-room의 상태는 모른다 (SCRUM-23, 66).

## 데스크톱 셸

코드는 npm 워크스페이스 `desktop/`에 있다. main과 preload를 esbuild로 `desktop/dist/`에 묶는다(CJS). 결정과 이유는 [ADR 0001](adr/0001-desktop-framework.md)에 있다.

| 파일 (`desktop/src/`)                                                           | 맡는 일                                                                                    |
| ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `main.ts`                                                                       | 앱 생명주기, 단일 인스턴스 잠금, 창 생성, 실패 대화상자, 종료 때 런타임 정리               |
| `appProtocol.ts`, `bundlePath.ts`                                               | Vite 빌드(`dist/webview`)를 `app://bundle/`로 내보낸다. 경로 탈출을 막고 CSP 헤더를 붙인다 |
| `window.ts`                                                                     | 창 옵션, 새 창·webview·외부 내비게이션 차단, 권한 거절                                     |
| `preload.ts`, `ipc.ts`                                                          | `window.agentsRoom` API 두 개와 IPC sender 검증                                            |
| `runtimeSupervisor.ts`, `runtimeCommand.ts`, `readyLine.ts`, `runtimeLog.ts` 등 | 런타임 실행 명령 결정, 실행, 준비 줄 해석, 로그와 토큰 가리기, 프로세스 트리 종료          |

- 렌더러 출처는 `app://bundle`이다. CSP와 스킴 권한은 ADR 0001 결정 6에 있다. CSP 헤더는 400과 404 응답에도 붙는다. 오류 응답도 렌더러 출처의 문서이기 때문이다. `npm run desktop:dev`는 Vite dev 서버(`http://localhost:5173`)를 띄우고, 이때만 그 출처를 허용하며 CSP 헤더를 붙이지 않는다.
- preload API는 `getRuntimeEndpoint(): Promise<{url, token}>`과 `pickProjectFolder(): Promise<string | null>` 두 개뿐이다. 타입은 `core/src/desktopBridge.ts`에 있다. 접속 정보는 런타임의 준비 줄이 온 뒤에야 응답한다.
- 렌더러는 `window.agentsRoom`이 있으면 데스크톱으로 판단한다(`webview-ui/src/runtime.ts`). `transport/desktopTransport.ts`는 접속 정보를 받아 `?token=`을 붙이고 `WebSocketTransport`로 직접 접속한다. 다시 접속할 때마다 접속 정보를 새로 묻는다. 런타임을 다시 시작하면 포트와 토큰이 바뀌기 때문이다.
- 런타임 소켓은 계약의 agents-room 프레임만 다룬다. 받는 프레임의 타입은 `RuntimeServerMessage`, 보내는 명령의 타입은 `RuntimeClientMessage`이다. `core/src/messages.ts`가 두 union에서 `x-legacy`가 아닌 원소만 모아 생성한다 ([ADR 0005](adr/0005-agent-event-model.md) 결정 11).
- 상속한 UI(`useExtensionMessages.ts`, Settings, 캔버스)는 업스트림 프로토콜로 말하므로 데스크톱에서는 따로 된 transport를 쓴다.
  - 받는 것은 렌더러가 스스로 만든 에셋 프레임뿐이다. 연결 상태는 런타임 소켓의 상태를 그대로 보여준다.
  - 상속 UI가 보내는 업스트림 명령은 런타임에 보내지 않는다. 런타임은 그 명령을 모두 `INVALID_MESSAGE`로 거절한다. `webviewReady`도 보내지 않는다. 런타임은 연결될 때마다 묻지 않아도 스냅샷을 보낸다 ([Renderer gateway](#renderer-gateway)).
- 런타임이 보낸 프레임은 `webview-ui/src/runtimeState.ts`의 상태 저장소가 받는다. 이 저장소는 런타임 소켓을 다른 무엇보다 먼저 구독하므로 연결 직후의 스냅샷을 놓치지 않는다.
  - 담는 값: 마지막 스냅샷, 현재 프로젝트, 에이전트 목록, 에이전트별 최근 이벤트 200개(`recentEvents`, 프레임 하나에 하나)와 마지막 `seq`, 에이전트별 상태(`agentStates`)와 "최근 이벤트" 목록(`eventLogs`), 에이전트별 `connectionChecked`, 마지막 `projectOpenFailed`와 `commandRejected`. 프레임은 받은 그대로 담는다. 프레임에서 빠진 선택 필드는 빠진 채로(`undefined`) 둔다. 생성된 타입의 `?:` 필드와 같다. 스냅샷에 프로젝트가 없을 때만 `project`를 `null`로 담는다. 화면은 둘 다 "미지정"으로 보여준다 (D3).
  - 스냅샷은 앞서 담은 값을 모두 바꾼다. 스냅샷의 `lastSeq` 이하인 이벤트는 버린다. `agentsUpdated`에서 빠진 에이전트의 값은 지운다.
  - 이벤트는 받을 때 한 번씩 그 에이전트의 상태에 반영한다(`webview-ui/src/runtimeAgentState.ts`): 상태, 세션, 시작 단계, 실행과 작업 요청, 현재 실행의 Tool 호출, 마지막 실행과 그 Tool 호출, 응답 글, 대기 중인 권한 요청, 오류, 인증 표시값, 세션 단위 보고, 마지막으로 보고된 `agentInfo`. 스냅샷을 받으면 이 상태를 스냅샷에서 다시 만든다. 스냅샷에 없는 것(시작 단계, 응답 글, 실행이 어느 시작의 것인지)은 스냅샷의 이벤트 꼬리에서 꺼낸다. 이 상태는 런타임이 보낸 값을 옮길 뿐, 상태를 다시 계산하거나 Tool 호출을 다시 병합하지 않는다 (ADR 0005 결정 4, 6). 200개로 자른 목록을 다시 읽지 않으므로 긴 실행도 상태를 잃지 않는다.
  - "최근 이벤트" 목록(`eventLogs`)은 같은 메시지의 이어진 텍스트 청크를 받을 때 한 항목으로 합친 뒤 최근 200개를 남긴다. 런타임의 꼬리(`RecentEvents`)와 같은 규칙이지만, 생각 청크는 남기고(접힌 행), 합친 항목은 첫 청크의 `seq`를 쓴다. 화면은 이 값들에서 자기 값을 꺼낸다 ([데스크톱 렌더러 화면](#데스크톱-렌더러-화면)).
- 렌더러가 런타임에 명령을 보내는 길은 `transport/index.ts`의 `sendRuntimeCommand(command)` 하나다. 런타임 소켓으로 보내고, 소켓이 열려 있지 않으면 열릴 때까지 쌓아 둔다. 답은 프레임으로 와서 위 상태 저장소에 담긴다. 패널과 e2e test hook(`__pixelAgentsTestHooks.sendRuntimeCommand`)이 이것을 부른다.
- `WebSocketTransport`는 구독자가 생기기 전에 온 메시지를 순서대로 잡아 두었다가 첫 구독자에게 넘긴다(`transport/heldMessages.ts`). 연결 직후 오는 메시지를 잃지 않기 위해서다.
- 렌더러는 스프라이트, 가구, 기본 레이아웃을 자기 번들에서 읽는다(`webview-ui/src/localAssets.ts`). Vite 빌드가 디코딩한 JSON(`assets/decoded/*.json`)을 함께 내보낸다. 런타임은 에셋을 보내지 않는다. 이 에셋 프레임의 타입(`LocalAssetMessage`)은 계약 밖의 렌더러 전용 타입이고, 프레임은 소켓을 지나지 않는다.
- 데스크톱에서는 레이아웃 편집("Layout")과 "+ Agent" 버튼을 숨긴다. 레이아웃 편집은 post-MVP다 (SCRUM-59). Settings에서도 VS Code나 업스트림 서버만 처리하는 항목을 숨긴다: Open Sessions Folder, Export/Import Layout, Add Asset Directory, Watch All Sessions, Instant Detection (Hooks), Display Headless as Ghosts. 런타임이 맡을 설정은 계약이 정해지면 다시 넣는다.

### 데스크톱 렌더러 화면

데스크톱에서만 `App.tsx`가 픽셀 월드를 세 열 화면의 가운데에 둔다(`webview-ui/src/desktopUi/DesktopShell.tsx`, D7). VS Code와 standalone은 지금까지처럼 픽셀 월드만 그린다. 화면 배치와 문구는 [ux.md](ux.md#sprint-0-구현)에 있다.

| 파일 (`webview-ui/src/desktopUi/`)                                                                            | 맡는 일                                                                                                                                                                                                                                                                                                                                               |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agentView.ts`                                                                                                | 에이전트마다 화면에 쓸 값(상태, 세션 상태, 시작 단계, 작업 요청, 현재 실행의 Tool 호출과 현재 Tool, 마지막 실행, 답 글, 대기 중인 권한 요청, 오류, 세션 단위 보고)을 저장소가 접어 둔 상태(`agentStates`)에서 꺼낸다. 스냅샷 없이 `agentsUpdated`로 온 에이전트는 `offline`에서 시작한다. 새 시작 뒤에는 이전 실행을 현재 실행으로 내놓지 않는다 (R8) |
| `eventRows.ts`, `detailText.ts`, `fileTreeRows.ts`, `agentControls.ts`, `strings.ts`                          | "최근 이벤트" 행(이어진 청크 합치기), Detail Panel의 줄(실행 결과와 그 색, 사용량과 비용), 파일 트리 순서, 시작·종료·연결 확인 버튼을 켤 조건, 이름 옆에 실행기 배지를 붙일지(ux.md 에이전트 이름 식별), 한국어 문구                                                                                                                                  |
| `commandFeedback.ts`                                                                                          | 보낸 명령과 그 답을 잇는다. 저장소는 마지막 `commandRejected`와 에이전트별 마지막 `connectionChecked`만 담으므로, 명령을 보낼 때 그 값을 적어 두고 그보다 새 값이 같은 대상(에이전트, 권한 요청)을 가리키면 그 명령의 답으로 본다                                                                                                                     |
| `characterBehaviour.ts`, `agentHandles.ts`, `runtimeWorld.ts`, `createRuntimeWorld.ts`                        | 에이전트 상태와 Tool `kind`를 캐릭터 행동으로 바꾼다(ux.md 행동 매핑). UUID `agentId`를 엔진의 정수 id로 잇는다(1부터, 다시 쓰지 않는다). 엔진은 바뀐 부분만 고친다: 같은 값이 다시 와도 `addAgent`, `setAgentActive`를 다시 부르지 않는다 (SCRUM-27)                                                                                                 |
| `useRuntimeState.ts`                                                                                          | 상태 저장소를 구독하되, 다시 그리기는 애니메이션 프레임마다 한 번으로 묶는다. 저장소는 프레임을 모두 바로 담는다 (D24, SCRUM-22)                                                                                                                                                                                                                      |
| `DesktopShell.tsx`, `ProjectPanel.tsx`, `FileTree.tsx`, `AgentCard.tsx`, `DetailPanel.tsx`, `AgentLabels.tsx` | 세 열 화면, 프로젝트·에이전트 패널, Detail Panel, 캐릭터 위 이름 라벨                                                                                                                                                                                                                                                                                 |

- 선택은 엔진의 `selectedAgentId` 하나다. 캔버스 클릭이 바꾼 값을 화면이 프레임마다 읽어 `agentId`로 되돌리고, 카드 클릭은 같은 값을 쓴다. 그래서 캐릭터와 Detail Panel의 선택이 어긋나지 않는다.
- 렌더러는 명령을 `sendRuntimeCommand`로만 보낸다: `openProject`(셸의 `pickProjectFolder()`가 고른 경로, 최근 프로젝트의 경로), `startAgent`, `stopAgent`, `checkConnection`(카드의 "연결 확인"), `sendPrompt`, `respondPermission`. `cancelRun`, `saveAgentConfig`, `relocateProject`를 부르는 화면은 아직 없다. 거절(`commandRejected`)은 그 명령을 보낸 자리에 보여준다 ([ux.md](ux.md#sprint-0-구현)).
- 픽셀 월드는 에이전트마다 캐릭터를 하나 둔다. 런타임이 정한 팔레트와 색조를 쓰고, 첫 스냅샷에 있던 에이전트는 생성 효과 없이, 나중에 온 에이전트는 효과와 함께 나타난다. 엔진에 더한 것은 작다: 자리에 앉은 채 멈추는 캐릭터(`pinnedToSeat`), 반투명(`isOffline`), Tool kind 아이콘(`activityIcon`), 말풍선 3종(`error`, `cancelled`, `warning`), 그리고 런타임 에이전트의 권한·오류 말풍선은 클릭으로 지우지 않는 규칙이다 ([upstream.md](upstream.md#상속-범위)). 서브에이전트 캐릭터를 만드는 길(`addSubagent`)은 부르지 않는다 (R4).

### 셸과 런타임의 약속

셸은 런타임을 띄우고 지켜본다. 형식은 ADR 0001 결정 4와 5에, 두 쪽이 함께 따르는 계약은 [셸과 런타임의 연결](#셸과-런타임의-연결)에 있다.

1. 실행 명령을 정한다: `AGENTS_ROOM_RUNTIME_COMMAND`(JSON argv, 테스트와 디버깅용) → `runtime/build/dev-runtime.json`(`{"java", "jar"}`, 런타임 Gradle 빌드가 쓴다) 순서다. 둘 다 없으면 실패 대화상자를 띄운다. 설치판의 경로는 패키징 때 정한다. 셸의 테스트·개발용 환경변수(`AGENTS_ROOM_RUNTIME_COMMAND`, `AGENTS_ROOM_READY_TIMEOUT_MS`, `AGENTS_ROOM_USER_DATA_DIR`, `AGENTS_ROOM_RENDERER_URL`)는 패키징하지 않은 실행에서만 읽는다(`devOverrides.ts`). 설치판은 모두 무시한다.
2. 환경변수 `AGENTS_ROOM_TOKEN`, `AGENTS_ROOM_DATA_DIR`(Electron `userData`), `AGENTS_ROOM_ALLOWED_ORIGINS`, `AGENTS_ROOM_NODE`(셸 자신의 실행 파일 `process.execPath`)와 `AGENTS_ROOM_NODE_ELECTRON=1`을 넘기고 UTF-8 JVM 옵션으로 띄운다. 런타임은 이 Electron을 Node로 써서 어댑터를 띄운다 ([어댑터 실행](#어댑터-실행)). 셸 전용 환경변수는 넘기지 않는다. 설치판은 런타임의 어댑터 override(`AGENTS_ROOM_HARNESS_<ID>_COMMAND`, `AGENTS_ROOM_ADAPTERS_DIR`)도 넘기지 않는다([어댑터 실행](#어댑터-실행)). 모든 OS에서 detached로 띄운다. Windows에서는 셸의 job object 밖에 두기 위해서다. 그 안에 있으면 셸이 죽을 때 OS가 런타임도 바로 끝내서, 런타임이 어댑터를 정리하지 못한다.
3. stdout의 `AGENTS_ROOM_READY {"port":<int>}`를 30초 기다린다. 그 밖의 stdout과 stderr는 `<userData>/logs/runtime.log`에 토큰을 가려서 쓴다. 셸도 실행마다 `the runtime started (pid N)` 줄을 남긴다. 앞 세션의 로그는 `runtime.previous.log`로 한 번 남긴다. 이름을 바꾸지 못하면(다른 프로그램이 파일을 열고 있을 때) `runtime.log`에 이어 쓰고, 그 파일도 열 수 없으면 `runtime-<pid>.log`에 쓴다. 실패 대화상자는 실제로 쓰는 파일의 경로를 보여준다.
4. 시작 실패나 예상하지 않은 종료에는 "로컬 런타임을 시작하지 못했습니다" 대화상자를 띄운다 (문구는 [ux.md](ux.md#문구-목록)). "다시 시작"은 새 토큰으로 런타임을 한 번 더 띄운다. 자동으로 다시 시작하지 않는다.
5. 앱을 끄면 stdin을 닫고 3초 기다린 뒤, 살아 있으면 프로세스 트리를 강제로 끝낸다.

e2e(`npm run e2e:desktop`, `e2e/desktop/`)는 이 약속만 말하는 가짜 런타임(`e2e/desktop/fixtures/fake-runtime.cjs`)으로 셸을 검증한다. `e2e/desktop/realRuntime.spec.ts`는 셸이 `runtime/build/dev-runtime.json`의 실제 런타임 jar를 띄우게 하고 두 쪽의 연결을 끝까지 확인한다: 준비 줄, `app://bundle`에서 토큰으로 접속, 토큰과 Origin 검사, 렌더러에 온 스냅샷, 앱을 끈 뒤 남은 java 프로세스가 없음. `e2e/desktop/agentRun.spec.ts`는 같은 실제 런타임이 기본 Codex 에이전트를 mock ACP agent로 띄우게 하고(`AGENTS_ROOM_HARNESS_CODEX_COMMAND`, `AGENTS_ROOM_E2E=1`), 폴더 선택기(stub)로 고른 프로젝트를 열고, 에이전트를 시작하고, 프롬프트를 보낸다. 렌더러의 상태 저장소에 온 이벤트의 순서(`seq`, `starting` → `ready` → `running` → `ready`, Tool 호출, 메시지 청크, `runEnded`), 중지, 비정상 종료 뒤의 `error`와 다시 시작, 앱을 끈 뒤 남은 java와 mock 프로세스가 없음을 본다. mock은 셸의 Electron을 Node로 써서 돈다. `e2e/desktop/panels.spec.ts`는 같은 실제 런타임과 mock으로 화면을 DOM으로 조작한다: 폴더 선택기(stub)의 취소, 잘못된 폴더의 오류 문구와 다시 고르기, 프로젝트 이름·경로·파일 트리, 최근 프로젝트, 캐릭터 클릭과 카드 클릭의 선택, 시작과 작업 요청, Tool kind마다 바뀌는 "현재 Tool"과 캐릭터 행동(test hook으로 읽는다), 수신 순서의 이벤트, 최종 응답, 엔진의 캐릭터 수(서브에이전트나 중복 캐릭터가 없음), 권한 요청 버튼과 캐릭터 클릭, 보고되지 않은 Plan이 없음, "연결 확인", 200개보다 많은 이벤트를 보내는 실행의 상태와 처음부터 남은 응답, 실행 중 다른 프로젝트 열기 거절, 실행 중 창 크기 변경, 어댑터가 죽은 뒤의 오류와 다시 시작(이전 실행은 현재 실행에서 빠진다), 인증 단계 문구, 인증 실패와 세션 생성 실패의 단계별 오류. 이전 세션이 남긴 데이터는 실행 전에 앱 데이터 디렉터리에 써 둔다(`beforeLaunch`). 데스크톱 e2e의 모든 실행은 `AGENTS_ROOM_E2E=1`과 격리된 `CODEX_HOME`을 쓴다. 이 spec들은 런타임을 빌드하지 않은 로컬에서는 건너뛴다. CI(`desktop-windows` job)는 런타임을 먼저 빌드하고, 거기서 jar가 없으면 실패한다.

## 런타임 구성요소

### Project/Workspace service

- 렌더러가 `openProject {path}`로 보낸 경로를 검증한다. 셸의 폴더 선택기가 고른 경로도 검증 없이 믿지 않는다 (SCRUM-18, 31).
- 검사 순서와 오류 코드(`ProjectErrorCode`, [ADR 0005](adr/0005-agent-event-model.md) 결정 8)는 다음과 같다. 처음 실패한 검사가 코드를 정한다. 실패하면 아무것도 바꾸지 않고, `projectOpenFailed`로 그 코드를 알리고 다시 고르게 한다.
  1. 절대 경로인가: `PATH_NOT_ABSOLUTE`
  2. 실제 경로(`toRealPath`)로 바꾼다. 링크, 대소문자, Windows 8.3 짧은 이름이 풀린다: `PATH_NOT_FOUND` (접근 거부는 `PATH_NOT_READABLE`)
  3. 디렉터리인가: `PATH_NOT_DIRECTORY`
  4. 항목 목록을 읽을 수 있는가: `PATH_NOT_READABLE`
- 검증된 실제 경로가 프로젝트 경로이고, ACP `session/new`의 `cwd`가 된다. 이름은 폴더 이름이다.
- `projectId`를 발급하고 최근 프로젝트를 관리한다. 같은 실제 경로는 다시 시작해도 같은 `projectId`다. 폴더가 옮겨지거나 지워졌으면 "위치 다시 지정"을 요구하고, 다른 경로에서 대신 실행하지 않는다 (SCRUM-32).
- `projectId`는 `recent-projects.json`이 기억한다. 그래서 `agents.json`은 이 파일에 저장된 `projectId`에만 쓴다.
  - 이 파일을 읽을 수 없는 동안(다른 프로그램이 잠갔을 때 등) 연 프로젝트는 이번 실행에서만 쓰는 `projectId`를 받고, 아무 파일도 쓰지 않는다. 프로젝트를 열 때마다 파일을 다시 읽고, 읽히면 저장된 `projectId`로 돌아간다.
  - 저장되지 않은 `projectId`의 기본 에이전트는 메모리에만 둔다. 그 `projectId`가 저장된 뒤에 `agents.json`을 쓴다.
- 열린 프로젝트는 한 번에 하나다. 프로젝트마다 에이전트 설정과 상태를 분리한다 (SCRUM-13).
- 최소 파일 트리는 읽기 전용이다 (SCRUM-31).
  - 깊이 2(프로젝트의 자식과 손자)까지, 500개까지 담는다. 빠진 항목이 있으면 잘렸다고 표시한다. 얕은 항목이 먼저 들어간다.
  - 폴더마다 디렉터리를 먼저, 그다음 파일을 이름순(대소문자 무시)으로 놓는다.
  - 심볼릭 링크와 Windows junction은 따라가지 않고 파일처럼 보여준다.
  - `.git`과 `node_modules`는 보여주되 안으로 들어가지 않는다. 읽을 수 없는 하위 폴더는 자식 없이 보여준다.
  - 500개 제한은 읽는 양도 묶는다. 폴더마다 아직 담을 수 있는 만큼만, 그리고 잘렸는지 알기 위해 하나 더 읽는다. 그래서 잘린 폴더에서 어떤 항목이 보일지는 OS가 나열하는 순서를 따른다. 보이는 항목은 위 순서로 정렬한다.
- 경로 검증과 파일 트리 읽기는 잠금 없이 한다. 큰 폴더를 여는 동안에도 다른 명령과 새 렌더러의 스냅샷은 기다리지 않는다. 검증이 끝난 뒤에 현재 프로젝트를 바꾸는 단계만 짧게 잠근다.

### Agent config registry

- 프로젝트별 AgentConfig를 관리한다: `{agentId, projectId, name, harnessId, providerId, modelId | null, authMethodId, providerSettings {awsProfile?, awsRegion?, endpointUrl?}}`. 비밀은 담지 않는다 ([ADR 0004](adr/0004-model-authentication.md), SCRUM-10, 65).
- `providerId`는 `amazon-bedrock`과 `openai`다. MVP의 `amazon-bedrock`은 `endpointUrl`로 Bedrock mock 서버를 가리킨다 ([ADR 0004](adr/0004-model-authentication.md) 결정 10, SCRUM-67). 고를 수 있는 제공자, 모델, 인증 방식은 실행기 카탈로그([ADR 0003](adr/0003-acp-integration.md))와 정적 호환성 규칙으로 정한다: `claude-code`는 `amazon-bedrock`만 쓴다 (ADR 0004 결정 4). 모델은 `claude-code`가 Anthropic 계열, `codex`가 OpenAI GPT 계열만 쓴다 (제안). 설치되지 않은 실행기나 지원하지 않는 조합은 실행할 수 있다고 표시하지 않는다 (SCRUM-26, 58).
- 설정 상태(`configured`, `auth_required`, `unsupported`, `executable_missing`)를 계산한다 (SCRUM-25, 36). 카탈로그가 실행하지 않는 조합은 `unsupported`, 어댑터 실행 명령을 정할 수 없거나 마지막으로 띄울 때 실행 파일이 없었으면 `executable_missing`([어댑터 실행](#어댑터-실행)), 마지막으로 본 어댑터가 설정한 인증 방식(`chat-gpt`)을 광고하지 않았으면 `unsupported`, 어댑터가 마지막으로 "로그인 안 됨"(kind `none`)을 알렸으면 `auth_required`, 그 밖은 `configured`다 (D9). 시작과 연결 확인이 본 것이 이 값에 들어가고, `connectionChecked`의 `readiness`도 이 값이다. 그래서 카드와 확인 결과가 다르지 않다. 값이 바뀌면 `agentsUpdated`를 다시 보낸다.
- "에이전트 추가"(SCRUM-25, 26)가 생기기 전까지는 프로젝트를 처음 열 때 에이전트 하나를 만들어 저장한다: 이름 `Codex`, `harnessId` `codex`, `providerId` `openai`, `modelId` 없음(실행기 기본값), `authMethodId` `chat-gpt`, palette 0, hueShift 0. `agentId`(UUID)도 저장하므로 다시 시작해도 같다 (Sprint 0 제안).

### Process supervisor

- 어댑터를 자식 프로세스로 띄운다. `cwd`는 프로젝트 경로이고, env는 명시적으로 구성한다 (env 위생은 ADR 0004, SCRUM-9). MVP에서는 OS 수준 격리(sandbox)를 하지 않으므로 `cwd` 지정은 접근 제한이 아니다 (SCRUM-35, 표시 규칙은 [ux.md](ux.md#관찰한-것만-보여준다)).
- 종료 코드와 시그널을 감지한다. 에이전트를 끌 때(`stopAgent`)는 진행 중인 실행을 먼저 [취소](#실행-취소)하고 정해진 시간만 기다린다. 그다음 자손 목록 스냅샷([`ProcessHandle.descendants()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html)) → stdin 닫기 → 대기 → 어댑터 종료 → 스냅샷에 남은 자손 종료 순서로 끝낸다. 상태 전이는 [ADR 0005](adr/0005-agent-event-model.md) 결정 4에 있다.
- 셸이 죽으면 런타임이 스스로 끝내고 자식 프로세스를 정리한다 (방식은 ADR 0001). OS별 보장 범위는 [셸과 런타임의 연결](#셸과-런타임의-연결)에 있다.
- 실행 중인 에이전트를 끄면 그동안 상태는 `cancelling`이다. `session/cancel`을 보내고 대기 중인 권한 요청을 `cancelled`로 닫은 뒤 `session/prompt` 응답을 5초까지 기다린다. 그다음 stdin을 닫고, SDK가 2초 기다린 뒤 프로세스를 끝내고, 남은 트리를 강제로 끝낸다. 끝난 뒤에야 `offline`이 된다. Windows에서 codex-acp는 stdin EOF 뒤 끝나는 데 2초쯤 걸려서 SDK의 2초와 앞뒤를 다툰다. 먼저 끝나면 종료 코드 0이고(셸의 Electron으로 띄운 2026-10-26 확인), 늦으면 SDK가 끝내서 종료 코드 1이 로그에 남는다(`PATH`의 node로 띄운 2026-10-24 확인). 어느 쪽이든 요청한 종료이므로 `sessionEnded {reason: stopped}`다 (D13).
- 요청하지 않은 종료를 감지하면 남은 트리부터 끝내고 `error`가 된다. `error`에서는 어댑터 프로세스가 없다.
- 런타임이 끝날 때(stdin EOF, 셸 종료)는 에이전트마다 열려 있는 어댑터 프로세스를 모두 바로 끝낸다: 세션의 것, 연결 확인의 것, 시작 중이거나 끝내는 중인 것.

### 어댑터 실행

Process supervisor가 어댑터를 띄우는 규칙이다 ([ADR 0003](adr/0003-acp-integration.md) 결정 8, [ADR 0004](adr/0004-model-authentication.md) 결정 5).

- 실행 명령은 다음 순서로 정한다 (`harness.HarnessLauncher`). 셸을 거치지 않는다: `npx`와 `cmd /c`를 쓰지 않는다.
  1. `AGENTS_ROOM_HARNESS_<ID>_COMMAND`(Codex는 `AGENTS_ROOM_HARNESS_CODEX_COMMAND`): JSON 배열 argv를 그대로 쓴다. e2e와 디버깅용이다. 첫 원소가 절대 경로인데 그 파일이 없으면 실행할 수 없다고 본다.
  2. `AGENTS_ROOM_E2E=1`인데 1이 없으면 실행하지 않는다 (fail closed). e2e가 실수로 실제 어댑터를 띄우지 않게 한다.
  3. `<node> <adapters>/node_modules/@agentclientprotocol/codex-acp/dist/index.js`. `<adapters>`는 `AGENTS_ROOM_ADAPTERS_DIR`이 있으면 그 폴더, 없으면 런타임 jar(테스트에서는 작업 폴더) 위쪽에서 처음으로 `tools/adapters/package.json`이 있는 `tools/adapters`다. `<node>`는 `AGENTS_ROOM_NODE`(셸이 넘기는 실행 파일의 절대 경로)가 있으면 그것, 없으면 `PATH`의 `node`(Windows는 `node.exe`)를 절대 경로로 쓴다. 런타임 jar를 셸 없이 띄우면(개발, 테스트) `PATH`의 node가 쓰인다.
- 설치판(셸의 `app.isPackaged`)은 1의 `AGENTS_ROOM_HARNESS_<ID>_COMMAND`와 `AGENTS_ROOM_ADAPTERS_DIR`를 런타임에 넘기지 않는다(이름의 대소문자와 상관없이). 셸의 테스트 변수와 같은 이유다: 사용자 환경변수를 바꿀 수 있는 프로그램이 아무 명령이나 에이전트로 띄우게 된다 (ADR 0001 결정 5). `AGENTS_ROOM_E2E`는 어댑터를 막기만 하므로 넘긴다.
- 런타임의 Gradle 테스트는 `AGENTS_ROOM_E2E=1`, 빈 `AGENTS_ROOM_HARNESS_CODEX_COMMAND`·`AGENTS_ROOM_ADAPTERS_DIR`·`AGENTS_ROOM_NODE*`, 격리된 `CODEX_HOME`으로 돈다(`runtime/build.gradle.kts`, 빈 값은 없는 것으로 본다). 개발자 셸의 override나 설치된 codex-acp를 테스트가 띄우지 않는다. 어댑터가 필요한 테스트는 mock 명령을 Spring 속성으로 정하고, 이 속성이 환경변수보다 우선한다.
- Node는 셸의 Electron이다 (ADR 0003 결정 12). 셸은 패키징 여부와 상관없이 `AGENTS_ROOM_NODE`에 자기 실행 파일(`process.execPath`)을, `AGENTS_ROOM_NODE_ELECTRON`에 `1`을 넘긴다. 실행 파일이 그 파일인 실행(설치된 어댑터든 1의 override든, 파일로 비교한다)은 어댑터 env에 `ELECTRON_RUN_AS_NODE=1`을 더한다. 그래서 Electron이 일반 Node(Electron 45는 Node 24.22.0)로 돈다. 개발 PC에서 codex-acp 2.2.2가 이렇게 `initialize`와 `session/new`까지 도는 것을 확인했다 (2026-10-26). 런타임 자신의 env에 있는 `ELECTRON_RUN_AS_NODE`는 넘기지 않는다: 이 값을 넣는 것은 실행뿐이다.
  - 이 값은 어댑터 자신의 Node 프로세스에만 둔다 (`harness.ElectronAsNodePreload`). 그 실행은 `<electron> --require <preload> <어댑터> ...`이다. preload는 런타임 jar의 리소스이고 런타임이 `<AGENTS_ROOM_DATA_DIR>/runtime/electron-as-node.cjs`에 써서 쓰므로(내용이 다르면 다시 쓴다) 개발 실행과 설치판이 같은 경로를 쓴다. preload는 어댑터 코드보다 먼저 이 값을 env에서 지우고, `child_process`의 `spawn`·`spawnSync`·`execFile`·`execFileSync`·`fork`를 감싸서 어댑터 자신의 실행 파일(`process.execPath`)로 띄우는 자식에만 이 값과 `--require <preload>`를 다시 붙인다(`util.promisify(execFile)`의 결과 모양은 그대로다). codex-acp는 Codex(`@openai/codex/bin/codex.js`)를 같은 Electron으로 띄우므로 Codex는 Node로 돌고, Codex가 실행하는 명령(그리고 그 명령이 띄운 Electron 앱, 예: 이 저장소의 `npm run desktop:start`)은 이 값을 보지 않는다 (ADR 0003 결정 12). preload를 쓰지 못하면 경고를 남기고 이 값만 넘긴다. `PATH`의 node로 띄우는 실행(셸 없이 띄운 런타임)은 이 값도 preload도 더하지 않는다.
- `tools/adapters/`는 npm 워크스페이스가 아니다. codex-acp와 Codex 실행 파일은 448 MB라서 루트의 `npm ci`는 이것을 설치하지 않는다. `npm run adapters:install`이 `tools/adapters/package-lock.json`대로 설치한다(`--ignore-scripts`). CI는 설치하지 않고 mock ACP agent만 쓴다. 설치판에 어댑터를 동봉하는 일은 ADR 0003 결정 12를 따른다 (Sprint 2).
- 실행 명령을 정할 수 있는지가 설치 확인이다: 정하면 `configured`(인증 상태에 따라 `auth_required`), 못 정하면 `executable_missing`이다. 결과를 기억해 두고, 프로젝트를 열 때와 에이전트를 시작하거나 연결을 확인할 때 다시 확인한다. 스냅샷을 만들 때는 디스크를 읽지 않는다.
- 작업 폴더는 프로젝트의 실제 경로다. SDK의 stdio transport에는 작업 폴더 옵션이 없어서 `getProcessBuilder()`를 재정의한다. `session/new`의 `cwd`도 같은 경로다.
- env는 아래 허용 목록의 변수만 넘긴다(`inheritEnvironment(false)`, `harness.AdapterEnvironment`). 이름은 대소문자를 가리지 않는다. 그 밖의 변수, 곧 셸의 `AGENTS_ROOM_*`(WebSocket 토큰 포함), 자격증명 변수, 어댑터 스위치(`NO_BROWSER`, `APP_SERVER_LOGS`, `DEFAULT_AUTH_REQUEST`, `CODEX_PATH`, `CODEX_CONFIG`), `NODE_OPTIONS`는 넘기지 않는다. 자격증명 변수(ADR 0004 결정 5)가 있었으면 그 이름만 로그에 남기고, env 값은 어떤 것도 로그에 남기지 않는다.
  - Windows 시스템과 프로그램 위치: `SystemRoot`, `windir`, `SystemDrive`, `ComSpec`, `PATHEXT`, `OS`, `PROCESSOR_ARCHITECTURE`, `PROCESSOR_IDENTIFIER`, `NUMBER_OF_PROCESSORS`, `COMPUTERNAME`, `USERDOMAIN`, `USERNAME`, `ProgramData`, `ProgramFiles`, `ProgramFiles(x86)`, `ProgramW6432`, `CommonProgramFiles`, `CommonProgramFiles(x86)`, `CommonProgramW6432`, `ALLUSERSPROFILE`, `PUBLIC`
  - 사용자 폴더: `USERPROFILE`, `HOMEDRIVE`, `HOMEPATH`, `HOME`, `APPDATA`, `LOCALAPPDATA`, `TEMP`, `TMP`, `TMPDIR`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_CACHE_HOME`, `XDG_STATE_HOME`, `XDG_RUNTIME_DIR`
  - POSIX 세션: `PATH`, `SHELL`, `USER`, `LOGNAME`, `TERM`, `LANG`, `LANGUAGE`, `TZ`, `LC_*`
  - Codex: `CODEX_HOME`. 사용자가 정해 둔 경우에만 있다. 런타임은 설정하지 않는다 (ADR 0004 인증 방식 표)
  - 네트워크: `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`, `ALL_PROXY`, `SSL_CERT_FILE`, `SSL_CERT_DIR`, `NODE_EXTRA_CA_CERTS`
- stdout은 ACP 전용이다. stderr는 줄마다 마스킹해서 `[agent <이름> <agentId 앞 8자>]` 접두어를 붙여 런타임 로그(로거 `agentsroom.runtime.acp.adapter-stderr`)에 남긴다 (ADR 0003 결정 9). SDK 로거(`com.agentclientprotocol`)는 INFO 아래로 내리지 않는다. DEBUG와 TRACE는 JSON-RPC 페이로드를 그대로 남기기 때문이다.
- 프로세스 트리: SDK는 띄운 프로세스를 알려 주지 않는다. 그래서 어댑터를 한 번에 하나씩 띄우면서 런타임의 자식 목록을 앞뒤로 비교해 찾는다. 그 자손은 1초마다 모아 둔다(`acp.ProcessTreeWatch`). 부모가 먼저 끝나 트리에서 빠진 손자(codex.exe가 남긴 git.exe)까지 끝내기 위해서다. 끝낼 때는 기록한 프로세스 중 살아 있는 것을 모두 강제로 끝낸다. `ProcessHandle`은 시작 시각까지 비교하므로 재사용된 PID를 끝내지 않는다. 로그인(`authenticate`)을 시작한 연결은 웹 브라우저(실행 파일 이름 `chrome`, `msedge`, `firefox` 등)를 기록하지도 끝내지도 않는다. codex-acp가 로그인 페이지를 어댑터 프로세스에서 열어서, 떠 있지 않던 브라우저는 어댑터의 자손이 되기 때문이다. 로그인 동안 그 어댑터 트리가 새로 띄운 프로세스는 Windows의 job object도 빠져나가므로(아래 [셸과 런타임의 연결](#셸과-런타임의-연결)) 앱을 꺼도 남고, 이 기록과 끝내기에서도 빠진다.

### ACP client + session manager

- 어댑터 프로세스마다 ACP 연결이 1개이고, 실행기가 달라도 같은 인터페이스를 쓴다. `initialize`(버전, capability) → 인증 → `session/new` 순서이며, 세부는 ADR 0003과 0004에 있다 (SCRUM-9, 23, 66).
- (`agentId`, `sessionId`)를 매핑한다. 세션마다 진행 중인 실행은 1개이고, 새 `session/prompt`는 앞 실행의 응답을 받은 뒤에만 보낸다 (SCRUM-24).
- 어댑터가 클라이언트에 보내는 요청에 응답한다. `session/request_permission`은 Permission broker로 넘기고, fs와 terminal 메서드는 MVP에서 광고하지 않는다.
- SDK는 포트 `acp.AgentConnection` 뒤에 있다(ADR 0003 결정 4). SDK 구현은 `AcpSdkConnection`이고, SDK 레코드를 포트의 값으로 바꾸고 마스킹하는 일은 `AcpTranslator` 한 곳에서 한다. 청크 텍스트만은 세션이 청크의 이음매를 넘어 마스킹한다 (아래 Event normalizer). 위쪽(`session`)은 SDK 타입을 모른다.
- 시작 단계는 `agentStarting {stage}`로 알린다: `spawn` → `initialize`(응답한 `protocolVersion`이 1인지 런타임이 확인한다) → `authenticate`(로그인을 실제로 할 때만) → `session_new`. 인증 단계의 조건(D8)은 [ADR 0004](adr/0004-model-authentication.md) 인증 방식 표에 있다. 어댑터가 `session/new` 응답보다 먼저 보낸 update(예: 명령 목록)는 잡아 두었다가 `sessionStarted` 뒤에 보낸다.
- 단계마다 제한 시간이 있다 (D14, `session.AgentTimeouts`). 속성 `agentsroom.runtime.acp.*`(예: `initialize-timeout=30s`)로 바꿀 수 있고, 테스트는 짧게 둔다.

  | 단계                                                                          | 제한 | 넘으면                                     |
  | ----------------------------------------------------------------------------- | ---- | ------------------------------------------ |
  | `initialize`                                                                  | 30초 | `ACP_INIT_FAILED`                          |
  | 첫 `_auth/status_update` 기다리기 (어댑터가 `_meta.authStatus`를 광고할 때만) | 3초  | 상태 없이 `session/new`로 간다             |
  | `authenticate` (브라우저 로그인)                                              | 5분  | `AUTH_FAILED`                              |
  | `session/new`                                                                 | 60초 | `SESSION_CREATE_FAILED`                    |
  | `stopAgent`가 취소 응답을 기다리기                                            | 5초  | 프로세스를 끝낸다                          |
  | 프로세스 끝내기 (stdin EOF, 종료, 트리 강제 종료)                             | 10초 | —                                          |
  | `session/prompt`                                                              | 없음 | 실행 종료는 응답으로만 판단한다 (ADR 0005) |

- 에이전트마다 메일박스(가상 스레드 하나, `session.AgentController`)가 있다. 명령, 어댑터가 보낸 메시지, 오래 걸리는 단계(시작, 끝내기, 연결 확인은 각자의 스레드에서 돈다)의 결과가 모두 이 메일박스에서 하나씩 처리된다. SDK의 수신 스레드는 메시지를 넘기기만 한다: 그 스레드가 늦으면 SDK가 응답 처리도 늦추기 때문이다. 상태 변경과 그 프레임은 gateway의 publish 잠금 아래 한 단계로 나가고, `seq`도 거기서 붙는다.
- 시작마다 세대가 새로 생긴다. 끝난 프로세스의 늦은 메시지, 응답, 단계 결과는 버리므로 새 프로세스의 이벤트에 섞이지 않는다.
- 연결 확인(`checkConnection`)은 자기 어댑터 프로세스를 띄워 `initialize`와 첫 인증 상태까지만 보고 끝낸다. 그동안 어댑터가 끝나거나 ACP가 아닌 줄을 쓰면 정상으로 보지 않고 그 오류(`PROCESS_EXITED`, `PROTOCOL_ERROR`)를 알린다. 결과는 `connectionChecked`(모든 렌더러)로만 알리고 에이전트 상태는 바꾸지 않는다. 선택지에서 `api-key`는 뺀다.

### Event normalizer

- `session/update`, 요청과 응답, 프로세스 생명주기를 AgentEvent로 바꾸고 `seq`와 `runId`를 붙인다 ([ADR 0005](adr/0005-agent-event-model.md), SCRUM-23, 66). 생성된 Java `AgentEvent` 인터페이스가 envelope 필드의 getter와 setter를 선언하므로, 이벤트 종류마다 따로 처리하지 않고 붙이고 에이전트별 마지막 `seq`를 기록한다.
- 에이전트별 현재 상태를 계산해서 상태 저장소에 반영한다. 상태 저장소가 단일 출처다. 업스트림 `AgentStateStore`의 개념을 이어받되, broadcast 메시지는 타입 검사를 한다 (SCRUM-19).
- 알 수 없는 update는 `unknownUpdate`로, Tool 호출은 ADR 0005의 병합 규칙대로 바꾼다. 내용을 추측해서 채우지 않는다 (SCRUM-27, 50).
- 어댑터에서 온 문자열은 포트에서 모두 마스킹한다(청크 텍스트는 다음 항목). 구조가 있는 값(`rawInput`, `rawOutput`, `_meta`, `content`, `modes`, `configOptions`)은 깊이와 상관없이 문자열마다 마스킹하고, 비밀처럼 보이는 키(`token`, `*_API_KEY` 등) 아래의 문자열은 통째로 가린다. id(`sessionId`, `toolCallId`, 권한 선택지의 `optionId`)는 런타임이 되돌려 줘야 하므로 받은 그대로 둔다.
- 청크 텍스트는 메시지(청크 kind와 `messageId`, 없으면 실행)마다 이어진 글로 마스킹한다 (`session.ChunkSeams`가 메시지마다 `logging.StreamMasker`를 둔다). 청크는 앞 글(최대 512자)과 함께 마스킹하므로, 여러 청크에 나뉘어 온 키 이름의 값, `Bearer` 값, `sk-` 키, access key ID도 실시간 이벤트에서 가려진다. 통째로 가리는 비밀의 시작일 수 있는 청크 끝(최대 `StreamMasker.holdLimit()`자, WebSocket 토큰이 등록되어 있으면 106자)은 다음 청크까지 잡아 둔다. 잡아 둔 글은 그 에이전트의 다른 이벤트나 같은 kind의 다음 메시지 앞에서, 지금 모습대로 마스킹한 청크 이벤트로 먼저 나간다. 실행·세션 경계에서는 메시지를 잊는다. 규칙과 남는 한계는 [ADR 0005](adr/0005-agent-event-model.md) 결정 11에 있다.
- 최근 이벤트 꼬리(`session.RecentEvents`)는 생각 청크를 빼고, 이어진 텍스트 청크의 글을 그대로 이어 붙인다. 보낸 청크가 이미 이음매를 넘어 마스킹되었으므로 꼬리는 실시간 이벤트와 같다.

### Permission broker

- 대기 중인 요청을 등록한다: `permissionId` ↔ (`agentId`, JSON-RPC 요청 id) (SCRUM-33). 구현은 `permission.PermissionBroker`이고, 요청마다 어댑터가 기다리는 응답을 들고 있다가 사용자가 고른 선택지나 `cancelled`로 한 번만 완료한다.
- MVP 기본 정책은 [ADR 0005](adr/0005-agent-event-model.md) 결정 7에 있다. 에이전트별 세부 정책은 post-MVP다 (SCRUM-62).
- 실행을 취소하거나 에이전트를 끄면 대기 중인 요청 전부에 `cancelled`로 응답한다 (SCRUM-33).
- 어댑터가 요청을 거두면(`$/cancel_request`) SDK가 기다리던 응답을 취소한다. broker는 그 요청을 잊고, 런타임은 `permissionResolved {outcome: cancelled}`로 닫는다. 그 뒤의 `respondPermission`은 `UNKNOWN_PERMISSION`이다 (ADR 0005 결정 7).

### Persistence

- 셸이 넘긴 앱 데이터 디렉터리(`AGENTS_ROOM_DATA_DIR`, Electron `userData`) 아래 `runtime/`에 저장한다. 업스트림이 쓰는 `~/.pixel-agents/`와는 공유하지 않는다. 프로젝트 설정도 사용자의 프로젝트 폴더 안에 쓰지 않는다 (제안).

  ```text
  <AGENTS_ROOM_DATA_DIR>/runtime/
    recent-projects.json             연 프로젝트 목록, 최근 순 (projectId 대응표를 겸한다)
    projects/<projectId>/agents.json 프로젝트별 AgentConfig
    electron-as-node.cjs             어댑터를 셸의 Electron으로 띄울 때의 preload (런타임이 쓴다, [어댑터 실행](#어댑터-실행))
  ```

- MVP 저장 대상은 최근 프로젝트, 프로젝트별 AgentConfig, 사무실 레이아웃, UI 설정이다 (SCRUM-32, 37). 레이아웃 편집(SCRUM-59)은 post-MVP다. 비밀과 "실행 중" 상태는 저장하지 않으므로, 앱을 다시 시작하면 모든 에이전트는 `offline`이다. 이벤트 이력은 post-MVP다 (SCRUM-52).
- 원자적으로 저장한다: 같은 폴더의 tmp 파일에 쓰고 디스크에 내린 뒤 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`로 바꿔 넣는다 (업스트림에서 이어받은 교훈).
- 파일이 손상되면 덮어쓰지 않는다. JSON이 아니거나 형식이 다르거나, 값이 계약 범위(예: `palette` 0–5, `hueShift` 0 또는 45–315)를 벗어나면 손상으로 본다. `<파일 이름>.corrupt-<epoch ms>`로 옮겨 남기고, 빈 상태로 시작하고, 로그에 남긴다. 손상된 `agents.json`이면 기본 에이전트를 새로 만든다. 사용자에게 알리고 "초기화 유지"와 "복구 시도" 중에서 고르게 하는 것은 SCRUM-37이다 (제안).
- 파일이 있는데 읽을 수 없으면(잠김, 권한) 손상으로 보지 않고 덮어쓰지도 않는다. `recent-projects.json`이면 [Project/Workspace service](#projectworkspace-service)의 이번 실행 전용 `projectId` 규칙을 따른다. `agents.json`이면 이번에 열 때만 기본 에이전트를 쓰고, 다음에 열 때 다시 읽는다.

### Renderer gateway

- 렌더러와 통하는 유일한 통로다. 계약은 `core/asyncapi.yaml`이고 TS와 Java 바인딩을 생성한다 (ADR 0005 결정 11). 전송 방식은 ADR 0001에서 정한다 (SCRUM-8, 22).
- 렌더러가 연결될 때마다(재연결 포함) `snapshot`을 먼저 보내고, 그다음부터 이벤트를 보낸다. 렌더러는 스냅샷의 `lastSeq` 이하인 이벤트를 버린다. 지난 이벤트는 다시 보내지 않으므로, 스냅샷에는 Detail Panel을 다시 그릴 값과 최근 이벤트 꼬리를 담는다 (ADR 0005 결정 2). 메시지 청크는 묶어서 보낼 수 있지만, 상태 전이는 하나도 빠뜨리지 않는다 (SCRUM-28).
  - 스냅샷을 보낼 큐에 넣는 일과 브로드캐스트 대상에 들어가는 일은 한 단계다. 렌더러가 보는 상태를 바꾸는 일과 그 브로드캐스트도 같은 잠금 아래서 한 단계로 한다.
  - 그래서 스냅샷이 어떤 변경을 이미 담았으면 그 변경의 프레임은 오지 않는다. 담지 않았으면 그 변경의 프레임이 모두 스냅샷 뒤에 온다.
  - 이 잠금 아래서는 메모리 작업과 작은 파일 쓰기만 한다. 오래 걸리는 디스크 읽기는 잠금 밖에서 미리 한다.
- 보내기 전에 어댑터에서 온 값과 진단 메시지를 마스킹 계층에 통과시킨다 ([ADR 0004](adr/0004-model-authentication.md) 결정 8, ADR 0005 결정 11).
- 명령(목록은 ADR 0005)을 검증한다: 형식이 맞는지, 존재하는 `projectId`·`agentId`인지, 대기 중인 `permissionId`인지, 지금 상태에서 받을 수 있는지. 받지 않는 명령은 `commandRejected {reason}`으로 답하고 아무것도 바꾸지 않는다. 생성된 Java 클래스는 필수 필드를 강제하지 않으므로 이 검증을 gateway가 한다.
- 런타임이 끝날 때는 웹 서버보다 먼저 모든 렌더러 연결을 닫고, 그 뒤에 접속한 렌더러도 바로 닫는다(`RendererGateway`가 `SmartLifecycle`이다). 웹 서버는 멈출 때 열린 연결을 제한 시간(3초)까지 기다린다. 앱을 끄는 동안에도 렌더러는 연결되어 있으므로, 연결을 먼저 닫지 않으면 런타임은 셸이 기다리는 3초 안에 끝나지 못한다.

## 셸과 런타임의 연결

[ADR 0001](adr/0001-desktop-framework.md) 결정 4, 5를 구체화한 계약이다. 데스크톱 셸과 로컬 런타임이 똑같이 따른다.

| 항목        | 계약                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 실행        | `<java> -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar <jar>`. stdin, stdout, stderr는 모두 pipe다. 셸은 런타임이 살아 있는 동안 stdin을 열어 둔다                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| env         | `AGENTS_ROOM_TOKEN`: 실행마다 새로 만든 32바이트 난수(base64url). `AGENTS_ROOM_DATA_DIR`: 앱 데이터 디렉터리(절대 경로). `AGENTS_ROOM_ALLOWED_ORIGINS`: 쉼표로 구분한 Origin 목록. 토큰이 없거나 43자 base64url이 아니면, 또는 데이터 디렉터리가 없거나 절대 경로가 아니면 시작하지 않는다. 이때 stdout에는 아무것도 쓰지 않고 0이 아닌 코드로 끝난다. `AGENTS_ROOM_NODE`: 어댑터를 띄울 Node 실행 파일의 절대 경로로, 셸 자신의 실행 파일이다. `AGENTS_ROOM_NODE_ELECTRON=1`: 그 파일이 Electron이므로 어댑터 env에 `ELECTRON_RUN_AS_NODE=1`을 넣고, 그 값을 어댑터 자신의 Node 프로세스에만 두는 preload를 함께 띄운다. 이 둘이 없으면 `PATH`의 node를 쓴다 ([어댑터 실행](#어댑터-실행)) |
| Origin 목록 | 배포본은 `app://bundle`이고, `desktop:dev`에서만 `http://localhost:5173`을 더한다. 변수가 없으면 `app://bundle`만 허용한다                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 준비 줄     | 127.0.0.1의 임시 포트에 바인딩하고, 준비되면 stdout에 `AGENTS_ROOM_READY {"port":<int>}`와 `\n`을 UTF-8로 한 번 쓴다. 그 밖의 출력(로그 전부)은 stderr로 간다. 읽는 쪽은 줄 끝의 `\r`을 지운다                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 렌더러 접속 | `ws://127.0.0.1:<port>/ws?token=<token>`. 토큰이 없거나 틀리면 업그레이드 전에 401이다(상수 시간 비교). Origin 헤더가 있는데 목록에 없으면 403이다. Origin이 없는 클라이언트(Node, curl)도 토큰은 있어야 한다                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| 로그        | access log는 없다. 마스킹 계층이 토큰 값, `token=`, `*_API_KEY`·`*TOKEN*`·`*SECRET*`·`Authorization` 뒤의 값, Bearer 값, `AKIA`·`ASIA`·`sk-` 형태의 키를 가린다 ([ADR 0004](adr/0004-model-authentication.md) 결정 8). logback을 거치지 않고 stderr에 쓰는 출력(`System.out`, `System.err`)도 줄마다 같은 계층을 지난다. 잡히지 않은 예외는 logback으로 남긴다                                                                                                                                                                                                                                                                                                                              |
| 종료        | stdin EOF나 부모 프로세스 종료를 감지하면 열린 렌더러 연결을 먼저 닫고, Spring 컨텍스트를 닫고 끝낸다. 이때 살아 있는 자손 프로세스를 모두 죽인다. 렌더러가 연결되어 있어도 셸이 기다리는 3초 안에 끝난다. 5초 안에 끝나지 않으면 남은 자손을 죽이고 강제로 끝낸다. 셸이 살아 있으면 셸이 3초 뒤에 런타임 프로세스 트리를 먼저 끝내므로, 이 5초는 셸이 먼저 죽었을 때의 상한이다. 런타임이 코드를 실행하지 못하고 죽을 때의 정리는 아래에 있다                                                                                                                                                                                                                                              |
| 개발 실행   | 런타임 빌드가 `runtime/build/dev-runtime.json`(`{"java": JDK 25의 java 경로, "jar": boot jar 경로}`)을 쓴다. 셸은 개발 중에 이 파일을 읽는다. env `AGENTS_ROOM_RUNTIME_COMMAND`(JSON 배열 argv)가 있으면 그쪽이 우선한다(테스트용, 패키징하지 않은 실행에서만)                                                                                                                                                                                                                                                                                                                                                                                                                              |

- 자식 프로세스 정리를 보장하는 범위는 OS마다 다르다.
  - Windows: 런타임은 시작할 때 자기만 가진 job object(`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`)에 들어간다. 그 뒤에 생기는 자식은 누가 띄우든(ACP SDK 포함) 이 job 안에서 생기고, 그 자손도 그렇다. 런타임이 어떻게 끝나든 Windows가 이 job의 프로세스를 모두 죽인다. 부모가 먼저 끝난 손자도 포함된다.
  - 예외는 로그인이다 (SCRUM-65, ADR 0003 결과). 어댑터는 띄운 직후 이 job 안의 자기 job(`process.AdapterJob`, 중첩 job)에 들어간다. 그 어댑터가 `authenticate`를 기다리는 동안만 그 job과 런타임의 job이 `JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK`를 켜서, 그 트리가 새로 띄운 프로세스(로그인 페이지의 브라우저)가 job 밖에서 생긴다. 런타임의 job은 진행 중인 로그인의 수를 세어 마지막 로그인이 끝날 때 다시 막고, 다른 어댑터의 job은 그동안에도 막혀 있다. job을 빠져나간 프로세스는 끝내기(`ProcessTreeKiller.killDescendants`, `acp.ProcessTreeWatch`)에서도 빠진다.
  - 이것이 필요한 이유: 런타임은 정리 코드를 돌리지 못하고 끝날 수 있다. 강제로 끝날 때(작업 관리자의 작업 끝내기, 셸이 종료 3초 뒤에 쓰는 `taskkill /T /F`)가 그렇다. `taskkill /T`는 부모 관계를 따라가므로 부모가 먼저 끝난 손자를 놓친다.
  - 셸은 런타임을 detached로 띄워 libuv의 kill-on-close job 밖에 둔다(이유는 [셸과 런타임의 약속](#셸과-런타임의-약속) 2). libuv의 job은 자식이 조용히 빠져나가게 두므로, 그 job으로는 런타임의 자식을 묶을 수도 없다.
  - 그 밖의 OS: 정상 종료할 때만 정리하고, 런타임의 프로세스 트리에 아직 있는 자손만 죽인다. 부모가 먼저 끝난 손자와, 런타임이 `SIGKILL`로 죽었을 때의 자식은 남을 수 있다 (macOS·Linux 배포는 SCRUM-64).
- 셸은 Gradle을 직접 부르지 않는다. Gradle 출력이 stdout에 섞이면 준비 줄을 읽을 수 없다.
- 렌더러 쪽(출처 `app://bundle`, preload의 `window.agentsRoom.getRuntimeEndpoint()`가 주는 `{url, token}`)은 [데스크톱 셸](#데스크톱-셸)에 있다.

## 로컬 런타임 코드 구성

`runtime/`은 Gradle(Kotlin DSL) 프로젝트다. Gradle 9.8.2 wrapper, JDK 25 toolchain, Spring Boot 4.1.2 WebFlux, Jackson 3을 쓴다 ([ADR 0002](adr/0002-java-spring-runtime.md)). 패키지 루트는 `agentsroom.runtime`이다.

| 패키지        | 맡는 일                                                                                                                                                              |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `config`      | 셸이 넘긴 env 읽기(`RuntimeEnvironment`), 127.0.0.1과 임시 포트 고정                                                                                                 |
| `shell`       | stdout 제어 경로(`ControlChannel`), 준비 줄, stdin EOF와 부모 종료 감시, 종료 시간 제한                                                                              |
| `process`     | Windows job object(`ChildProcessContainment`, 어댑터마다의 중첩 job `AdapterJob`), 프로세스 트리 종료(`ProcessTreeKiller`): 자손 목록을 먼저 잡고 모두 강제 종료한다 |
| `logging`     | 마스킹 계층(`SecretMasker`, logback `%mask`, stderr 전체를 가리는 `MaskingOutputStream`, 조각으로 오는 글의 `StreamMasker`)                                          |
| `gateway`     | Renderer gateway: `/ws`, 토큰과 Origin 게이트, 연결별 세션, 브로드캐스트, 계약 코덱과 명령 형식 검사, 명령 처리                                                      |
| `project`     | 경로 검증(`ProjectPathValidator`), 최소 파일 트리(`FileTreeReader`)                                                                                                  |
| `workspace`   | Project/Workspace service: 현재 프로젝트, 최근 목록, 스냅샷 데이터                                                                                                   |
| `agent`       | AgentConfig, 기본 에이전트, 정적 실행기 카탈로그(`HarnessCatalog`)                                                                                                   |
| `persistence` | 데이터 디렉터리의 JSON 파일(원자적 저장, 손상 파일 보존)                                                                                                             |
| `harness`     | [어댑터 실행](#어댑터-실행) 명령과 설치 확인(`HarnessLauncher`), env 허용 목록(`AdapterEnvironment`), Electron preload(`ElectronAsNodePreload`)                      |
| `acp`         | ACP 포트(`AgentConnection`)와 SDK 구현(`AcpSdkConnection`), SDK 레코드의 변환과 마스킹, 프로세스 트리 기록                                                           |
| `session`     | 에이전트별 상태 기계와 이벤트(`AgentController`), 에이전트 명령의 경로와 스냅샷(`AgentSupervisor`), Tool 병합, 청크 이음매의 마스킹(`ChunkSeams`), 최근 이벤트 꼬리  |
| `permission`  | Permission broker: 대기 중인 권한 요청                                                                                                                               |

- 메시지 타입은 `core/asyncapi.yaml`에서 생성한 Java 바인딩(패키지 `agentsroom.contract`)이다 ([ADR 0005](adr/0005-agent-event-model.md) 결정 11).
  - Gradle 작업 `generateContractBindings`가 빌드 때 루트에서 `node --import tsx scripts/generate-messages.ts --java`(`npm run asyncapi:generate:java`와 같은 명령)를 실행해 `runtime/build/generated/sources/contract`에 만든다. 커밋하지 않는다. npm과 cmd.exe를 거치지 않으므로 Windows와 Linux에서 인자와 따옴표 처리가 같다.
  - 생성 코드는 별도 source set(`contract`)에서 컴파일한다. Modelina 출력에 불필요한 캐스트 같은 lint 경고가 있어서, 손으로 쓴 코드의 `-Xlint:all -Werror`를 풀지 않으려는 것이다.
  - 상속 프레임(`x-legacy`)은 바인딩에 없다. 그래서 런타임은 상속 프레임을 보내지 않고, 상속 명령은 모르는 명령처럼 거절한다.
- 코덱(`ContractMessageCodec`)은 명령을 바인딩에 넣기 전에 JSON 트리에서 형식을 검사한다(`CommandShapes`). 검사 항목은 필수 필드, 타입(문자열, null을 허용하는 문자열, 객체), 정의되지 않은 필드, 중복 키, 뒤에 남은 내용이다. 생성된 클래스는 이 중 아무것도 강제하지 않고, 빠진 필드와 `null`도 구분하지 못한다.
  - 프레임은 두 번 읽는다. 먼저 트리를 만들지 않는 스트리밍 읽기로 JSON인지와 거절에 돌려줄 `type`과 id를 찾는다. 이 읽기에는 깊이·숫자 길이·이름 길이 제한이 없으므로(메모리는 1 MiB 프레임 제한이 묶는다) JSON인 프레임은 모두 `type`과 id를 돌려받는다. 같은 키가 두 번 나오면 `JSON.parse`처럼 마지막 값을 쓴다.
  - 그다음 Jackson 기본 제한(깊이 500, 숫자 길이 1000, 이름 길이 50000)으로 트리를 읽는다. 제한을 넘으면 "Frame exceeds the runtime's JSON limits", 같은 키가 두 번 나오면 "'path' appears twice"처럼 그 키 이름으로 거절한다. "Frame is not JSON"은 `JSON.parse`도 받지 않는 프레임에만 쓴다.
- 명령은 형식 → id → 구현 여부 순서로 확인한다 ([ADR 0005](adr/0005-agent-event-model.md) 결정 2).
  - 형식이 틀리거나 모르는 명령이면 `INVALID_MESSAGE`, 모르는 `projectId`면 `UNKNOWN_PROJECT`, 모르는 `agentId`면 `UNKNOWN_AGENT`, 대기 중이 아닌 `permissionId`면 `UNKNOWN_PERMISSION`이다.
  - 아는 `projectId`는 최근 목록에 있는 프로젝트다(이번 실행 전용 id 포함). 아는 `agentId`는 현재 프로젝트의 에이전트다. `saveAgentConfig`가 둘 다 담으면 그 에이전트가 그 프로젝트의 것이어야 한다. 아는 `permissionId`는 아직 응답하지 않은 권한 요청이다.
  - `commandRejected`는 명령의 `type`과, 명령에 들어 있던 `agentId`·`projectId`·`permissionId`(`saveAgentConfig`는 `config` 안의 값)를 돌려준다. `message`는 마스킹 계층을 거치고, 필드 이름은 쓰지만 값은 옮기지 않는다. id는 자기 필드로만 돌아간다.
- 연결될 때마다 먼저 `snapshot`을 보낸다. 에이전트마다 지금 상태와 최근 이벤트 꼬리가 들어 있다.
- `openProject`가 성공하면 모든 렌더러에 `projectOpened`와 `agentsUpdated`를 보내고, 실패하면 보낸 렌더러에만 `projectOpenFailed`를 보낸다. 현재 프로젝트의 에이전트가 돌고 있으면 다른 프로젝트는 열지 않는다(`INVALID_STATE`).
- `startAgent`, `stopAgent`, `sendPrompt`, `checkConnection`, `respondPermission`은 그 에이전트의 메일박스로 간다. 결과는 이벤트(`agentEvent`)나 `connectionChecked`로 모든 렌더러에 나가고, 지금 상태에서 받을 수 없는 명령은 보낸 렌더러에만 `commandRejected`(`INVALID_STATE`)로 답한다. 전이는 [ADR 0005](adr/0005-agent-event-model.md)의 전이표대로다.
- `cancelRun`, `relocateProject`, `saveAgentConfig`는 id를 확인한 뒤 `commandRejected`(`NOT_IMPLEMENTED`)로 답한다 (SCRUM-24, 32, 25).
- `AgentSummary`의 `readiness`, `reportsToolDetails`, `reportsSubagentSessions`는 정적 실행기 카탈로그(`HarnessCatalog`)에서 온다 ([ux.md](ux.md#관찰한-것만-보여준다) R3, R4).
  - 카탈로그는 `codex`를 `openai` + `chat-gpt`로 실행한다. codex-acp는 Tool 호출에 kind와 status를 붙여 보내므로 `reportsToolDetails: true`다. agents-room이 `subagents` capability를 광고하지 않으면 서브에이전트도 일반 Tool 호출로 보내고 자식 세션을 알리지 않으므로 `reportsSubagentSessions: false`다 (Sprint 0 제안).
  - 카탈로그에 없는 실행기나 조합(예: Codex + Bedrock, [ADR 0004](adr/0004-model-authentication.md)의 spike 전)은 `unsupported`다. 모르는 실행기는 두 값이 모두 `false`다.
  - `configured`는 정적 설정이 맞고 어댑터 실행 명령을 정할 수 있을 때만 보낸다([어댑터 실행](#어댑터-실행)). 정할 수 없으면 `executable_missing`이고, 렌더러는 시작할 수 없는 에이전트에 "에이전트 시작"을 켜지 않는다. 어댑터가 "로그인 안 됨"을 알리면 `auth_required`가 된다. 이때 "에이전트 시작"이 로그인 단계를 연다 ([ADR 0004](adr/0004-model-authentication.md)).
- 계약 테스트: 런타임이 만드는 모든 프레임과 WebSocket 대화로 받은 프레임을 `node scripts/check-contract-fixtures.mjs --verdicts <dir>`로 검사한다. `npm run contract:check`와 같은 ajv 설정이다. 그 밖에 다음을 확인한다.
  - 유효 fixture는 모두 코덱을 통과한다. 명령은 필드를 잃지 않고 바인딩되고, 서버 프레임은 fixture와 같은 JSON으로 다시 인코딩된다. 무효 명령 fixture는 모두 거절한다.
  - 형식 검사표의 명령과 필드는 생성된 클래스와 같다.
  - 유효 명령 fixture와 그 변형(필드 삭제, `null`, 다른 타입, 필드 추가)에서 코덱은 ajv와 같은 판정을 낸다.
  - Java JSON Schema 라이브러리를 쓰지 않는 이유는, 검사기가 둘이면 스키마(`if`/`then`/`else`, 타입 없는 `not`, AsyncAPI `discriminator`)를 서로 다르게 읽을 수 있어서다. node는 바인딩 생성 때문에 이미 필요하다.
- ACP 테스트는 실제 런타임 클래스(SDK 포함)가 mock ACP agent(`e2e/desktop/fixtures/mock-acp-agent.cjs`, node로 실행)를 Codex 어댑터로 띄우고 렌더러 WebSocket으로 대화한다(`AcpAgentIntegrationTests`). mock은 시나리오 파일대로 답한다: 정상 실행, 로그인 단계, 권한 요청, 실행 중 중지, 비정상 종료, 잘못된 줄, `initialize` 무응답 등. 받은 프레임은 모두 계약 검사기로 확인한다. 상태 기계의 순서 문제(시작 단계 중 중지, 중지 중 종료, 늦은 메시지)는 가짜 연결로 확인한다(`AgentControllerTests`). node가 없으면 mock 테스트는 이유를 남기고 건너뛴다.
- 빌드와 테스트는 루트에서 `npm run runtime:build`(빌드, 테스트, `dev-runtime.json`)와 `npm run runtime:test`로 한다. `npm run runtime:assemble`은 테스트 없이 jar와 `dev-runtime.json`만 만든다. CI의 `desktop-windows` job이 이것을 쓴다. `scripts/gradle.mjs`가 OS에 맞는 wrapper를 부른다. node가 PATH에 있어야 하고, 루트에서 먼저 `npm ci`(또는 `npm install`)를 해야 한다.
- CI의 `runtime` job이 같은 빌드를 Windows(windows-latest)와 Linux(ubuntu-latest)에서 돌린다. Windows 전용 테스트(job object, junction, 8.3 짧은 이름)는 Windows에서 돌고 Linux에서는 건너뛴다. 볼륨에서 8.3 이름이 꺼져 있거나 `mklink /J`가 실패하면 그 테스트만 건너뛴다.
- Gradle 빌드 캐시가 켜져 있다. 그래서 테스트 작업은 계약 검사기의 결과를 바꿀 수 있는 파일(`core/fixtures`, `core/asyncapi.yaml`, `scripts/check-contract-fixtures.mjs`, `package-lock.json`, `.nvmrc`)을 입력으로 선언한다. 이 중 하나가 바뀌면 캐시된 결과를 쓰지 않고 다시 돈다.
- job object는 FFM API로 kernel32를 부른다. 그래서 boot jar 매니페스트에 `Enable-Native-Access: ALL-UNNAMED`를 넣었다. 셸의 실행 명령은 그대로다.
- Gradle을 띄우려면 JDK 17 이상이 `JAVA_HOME`이나 PATH에 있어야 한다. 빌드에 쓰는 JDK 25가 없으면 Gradle이 기본 `~/.gradle`(`~/.gradle/jdks`)에 내려받는다. Windows에서 `GRADLE_USER_HOME`을 깊은 경로로 바꾸면 압축을 풀 때 MAX_PATH(260자)를 넘어 실패한다.

## 식별자

| 식별자                     | 발급 주체                       | 범위와 수명                                                                    | 용도                                                                                |
| -------------------------- | ------------------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------- |
| `projectId`                | 로컬 런타임 (UUID)              | 등록된 폴더 1개. 경로를 다시 지정해도 유지된다                                 | 프로젝트별 설정 분리 (SCRUM-13, 32)                                                 |
| `agentId`                  | 로컬 런타임 (UUID)              | AgentConfig 1개. 재시작해도 유지되며 캐릭터와 1:1이다                          | 이벤트와 권한 요청의 귀속 (SCRUM-28, 66)                                            |
| `sessionId`                | ACP 어댑터 (`session/new` 응답) | 어댑터 프로세스 1개의 수명 안. 재시작하면 새 값이다                            | ACP 메시지 라우팅 (SCRUM-23)                                                        |
| `runId`                    | 로컬 런타임 (UUID)              | `session/prompt` 1회                                                           | 늦게 온 이벤트 구분(SCRUM-24), 비정상 종료 귀속(SCRUM-47), 이력(SCRUM-52, post-MVP) |
| `toolCallId`               | ACP 어댑터                      | 세션 안에서만 유일하다. 전역 키는 (`agentId`, `sessionId`, `toolCallId`)       | Tool 상태 병합, 중복 캐릭터·애니메이션 방지 (SCRUM-27)                              |
| `permissionId`             | 로컬 런타임 (UUID)              | 권한 요청 1건. 연결마다 따로 매겨지는 JSON-RPC 요청 id와 1:1로 대응한다        | 원래 요청에 응답 (SCRUM-33)                                                         |
| `seq`                      | 로컬 런타임                     | 에이전트마다 1부터 단조 증가하는 수신 순번. 스냅샷의 `lastSeq`가 마지막 값이다 | 수신 순서 보존, 재연결 뒤 중복 제거 (SCRUM-19, 52)                                  |
| `workflowRunId` (post-MVP) | 로컬 런타임                     | 워크플로우 실행 1회                                                            | SCRUM-30, 44                                                                        |

- 렌더러는 JSON-RPC 요청 id를 보지 않는다. ACP가 발급한 ID(`sessionId`, `toolCallId`)는 불투명한 문자열로 다룬다.
- 실행 밖에서 온 세션 단위 update(예: `available_commands_update`)는 `runId = null`이다.
- 위 식별자와 Run의 단위, 아래 흐름의 상태·이벤트 이름은 ADR 0005에서 정했다. Jira는 실행 식별자가 필요하다는 것만 정했다 (SCRUM-23, 24, 47). Run을 `session/prompt` 단위로 정한 이유와 대안, 상태 전이표는 [ADR 0005](adr/0005-agent-event-model.md)에 있다.

## 주요 흐름

단계별 오류 코드와 상태 전이는 [ADR 0005](adr/0005-agent-event-model.md)에 있다. 아래는 정상 경로와 갈림길만 적는다.

### 프로젝트 열기

1. 사용자가 프로젝트 열기를 요청하면 렌더러가 preload의 `pickProjectFolder()`를 부르고, 셸이 OS 폴더 선택기를 연다. 취소하면 `null`이 돌아오고 아무것도 바뀌지 않는다 (SCRUM-18, 31).
2. 렌더러가 고른 경로를 WebSocket으로 `openProject`에 담아 런타임에 보낸다. 셸과 런타임 사이의 stdio로는 경로를 보내지 않는다 (ADR 0001).
3. Project/Workspace service가 경로를 검증하고, `projectId`를 발급하고, 최근 목록을 갱신한다. 실패하면 `projectOpenFailed {code}`를 보내고 다시 고르게 한다.
4. 렌더러에 `projectOpened`(프로젝트 이름, 경로, 최소 파일 트리)를 보내고, 이어서 그 프로젝트의 에이전트 목록(`agentsUpdated`)을 보낸다.

### 에이전트 시작

1. `startAgent {agentId}`를 받으면 AgentConfig를 확인하고 명령과 env를 구성한다. 상태는 `starting`이고, 아래 단계 중 하나가 실패하면 어댑터 프로세스 트리를 끝내고 `error`가 된다 (SCRUM-23, 66).
2. 어댑터 프로세스를 실행한다 (`cwd` = 프로젝트 경로).
3. `initialize`(`protocolVersion` 1, client capability)를 보내 버전을 확인하고, 인증을 확인한다 ([ADR 0004](adr/0004-model-authentication.md)). 어댑터가 "로그인 안 됨"을 알렸거나 `session/new`가 인증을 요구하면 `authenticate`로 어댑터의 로그인(Codex는 브라우저)을 연다. 로그인을 기다리는 동안 상태는 `starting`이고 단계는 `authenticate`다. "에이전트 종료"로 그만둘 수 있다.
4. `session/new {cwd: 절대 경로, mcpServers: []}`로 받은 `sessionId`를 `agentId`에 묶고 `ready`가 된다.

### 프롬프트에서 캐릭터까지

1. `sendPrompt {agentId, text}`를 받으면 `runId`를 발급하고 `runStarted`를 낸 뒤 `session/prompt`를 보낸다. 상태는 `running`이다 (SCRUM-23).
2. Event normalizer가 `session/update` 스트림을 AgentEvent로 바꾸고 `seq`를 붙여 상태 저장소에 반영한다.
3. Renderer gateway가 이벤트를 보내고, 렌더러가 캐릭터 애니메이션과 Detail Panel을 갱신한다 (SCRUM-19, 27).
4. `session/prompt` 응답이 오면 `runEnded {outcome}`를 내고 `ready`로 돌아간다. 실행이 끝났는지는 이 응답으로만 판단한다 (결과 규칙은 ADR 0005 결정 5).

### 권한 요청

1. 어댑터가 `session/request_permission`을 보낸다 (JSON-RPC 요청 id N, SCRUM-33).
2. Permission broker가 `permissionId`를 발급하고 `permissionRequested`를 낸다. 상태는 `awaiting_permission`이다.
3. 렌더러가 대화상자를 보여주고, 사용자가 고른 `optionId`를 `respondPermission`으로 보낸다. 대화상자를 닫으면 요청은 대기 상태로 남는다 (정책은 ADR 0005 결정 7).
4. broker가 N에 `selected`로 응답하고 `permissionResolved`를 낸다. 남은 요청이 없으면 `running`으로 돌아간다.

### 실행 취소

1. `cancelRun {agentId, runId}`를 받으면 `session/cancel` 통지를 보내고, 대기 중인 권한 요청 전부에 `cancelled`로 응답한다. 상태는 `cancelling`이다 (SCRUM-24).
2. `session/prompt` 응답이 오면 `runEnded`를 낸다 (결과와 끝나지 않은 Tool 호출의 처리는 ADR 0005 결정 5, 6). 응답이 제한 시간 안에 오지 않으면 경고하고 사용자에게 강제 종료를 권한다 (제안). 강제 종료는 `stopAgent`와 같은 경로다.
3. 응답 뒤에 늦게 온 update는 끝난 `runId`에 붙이지 않는다. 다른 에이전트는 영향을 받지 않는다.

### 비정상 종료

1. Process supervisor가 요청하지 않은 프로세스 종료를 감지한다 (종료 코드, 시그널, SCRUM-47).
2. 진행 중인 실행이 있었으면 `runEnded {outcome: failed}`를 낸다. 완료로 표시하지 않는다. 대기 중인 권한 요청은 `permissionResolved {outcome: cancelled}`로 닫는다.
3. 세션이 있었으면 `sessionEnded {reason: exited}`를 낸다. 상태는 `error`가 되고, GUI는 계속 돈다 (SCRUM-22).
4. 다시 시작하면 새 프로세스, 새 세션이다. 이전 세션을 이어간다고 표시하지 않고, 자동 재시작을 무한히 반복하지 않는다.

### 앱 종료

1. 셸이 닫힌다. 실행 중인 에이전트가 있을 때의 경고는 [ux.md](ux.md#화면-구성)에 있다.
2. 셸이 런타임의 stdin을 닫는다. 런타임이 stdin EOF나 부모 프로세스 종료를 감지한다 (방식은 ADR 0001). 셸이 비정상으로 끝나도 같다. 셸이 런타임을 detached로 띄우므로 OS가 런타임을 셸과 함께 끝내지 않는다.
3. 런타임이 모든 어댑터 프로세스 트리를 정리하고 끝난다. 3초 안에 끝나지 않으면 셸이 런타임 프로세스 트리를 강제로 끝낸다. OS별 보장 범위는 [셸과 런타임의 연결](#셸과-런타임의-연결)에 있다.

## 현재 코드와 목표 구조

판정은 계획이다. 파일 단위 판정과 이유는 [upstream.md](upstream.md#상속-범위)에만 있다.

- 그대로 쓴다: 픽셀 월드(`webview-ui/src/office/`의 레이아웃·에디터, `OfficeCanvas.tsx`), `WebSocketTransport`, `core/src/transport.ts` 같은 렌더러 공용 코드. 엔진과 스프라이트(`characters.ts`, `officeState.ts`, `renderer.ts`, `types.ts`, `spriteData.ts`)는 데스크톱 캐릭터를 위해 조금 고쳤다 ([데스크톱 렌더러 화면](#데스크톱-렌더러-화면)).
- 고쳐 쓴다: 렌더러의 메시지 처리(`useExtensionMessages.ts`, `toolUtils.ts`. 데스크톱의 에이전트 처리는 `desktopUi/`가 맡았다. `useExtensionMessages.ts`는 VS Code와 standalone의 메시지와 데스크톱의 에셋 프레임을 처리하고, `toolUtils.ts`의 읽기 Tool 목록은 데스크톱에서 ACP kind로 채운다)와 전송 분기(`transport/index.ts`), 계약(`core/asyncapi.yaml`, ADR 0005), e2e. 계약에는 agents-room 프레임과 명령을 상속 프레임 옆에 더했다. 상속 프레임은 `x-legacy: true`이고, `server/`와 `adapters/vscode/`를 지울 때 함께 지운다.
- 개념만 옮긴다: `server/`(Node/Fastify)는 Java로 다시 구현하고 상태 저장소, 연결 시 스냅샷, 원자적 저장 같은 구조만 가져온다 ([ADR 0002](adr/0002-java-spring-runtime.md)).
- 버린다: Claude hooks와 JSONL 처리(`server/src/providers/hook/**`, `fileWatcher.ts`, `transcriptParser.ts`), `adapters/vscode/**`.
- 새로 만든다: 권한 대화상자, 에이전트 설정 화면([ux.md](ux.md#화면-구성)), 데스크톱 셸(뼈대는 `desktop/`에 있다), Java 런타임(골격은 `runtime/`에 있다). 프로젝트·에이전트 패널과 Detail Panel은 Sprint 0 범위로 만들었다(`webview-ui/src/desktopUi/`).

## 관련 문서

- [ux.md](ux.md): [화면 구성](ux.md#화면-구성), [상태 어휘](ux.md#상태-어휘), [관찰한 것만 보여준다](ux.md#관찰한-것만-보여준다), [문구 목록](ux.md#문구-목록)
- ADR: [0001 데스크톱 셸 프레임워크](adr/0001-desktop-framework.md), [0002 Java/Spring 로컬 런타임](adr/0002-java-spring-runtime.md), [0003 ACP 연동 방식](adr/0003-acp-integration.md), [0004 모델 제공자 인증과 자격증명](adr/0004-model-authentication.md), [0005 AgentEvent 모델](adr/0005-agent-event-model.md), [목록과 작성 규칙](adr/README.md)
- [upstream.md](upstream.md): [포크 기준](upstream.md#포크-기준), [상속 범위](upstream.md#상속-범위), [레거시 참조](upstream.md#레거시-참조)
