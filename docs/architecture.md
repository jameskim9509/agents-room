# 아키텍처

이 문서는 agents-room의 목표 구조를 설명한다. 코드는 대부분 pixel-agents 기준선([`d1e007a`](upstream.md#포크-기준)) 그대로다. 로컬 런타임은 골격([`runtime/`](#로컬-런타임-코드-구성))만 있고, 나머지 구성요소는 계획이다.
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
- 설정 상태(`configured`, `auth_required`, `unsupported`, `executable_missing`)를 계산한다 (SCRUM-25, 36).
- "에이전트 추가"(SCRUM-25, 26)가 생기기 전까지는 프로젝트를 처음 열 때 에이전트 하나를 만들어 저장한다: 이름 `Codex`, `harnessId` `codex`, `providerId` `openai`, `modelId` 없음(실행기 기본값), `authMethodId` `chat-gpt`, palette 0, hueShift 0. `agentId`(UUID)도 저장하므로 다시 시작해도 같다 (Sprint 0 제안).

### Process supervisor

- 어댑터를 자식 프로세스로 띄운다. `cwd`는 프로젝트 경로이고, env는 명시적으로 구성한다 (env 위생은 ADR 0004, SCRUM-9). MVP에서는 OS 수준 격리(sandbox)를 하지 않으므로 `cwd` 지정은 접근 제한이 아니다 (SCRUM-35, 표시 규칙은 [ux.md](ux.md#관찰한-것만-보여준다)).
- 종료 코드와 시그널을 감지한다. 에이전트를 끌 때(`stopAgent`)는 진행 중인 실행을 먼저 [취소](#실행-취소)하고 정해진 시간만 기다린다. 그다음 자손 목록 스냅샷([`ProcessHandle.descendants()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html)) → stdin 닫기 → 대기 → 어댑터 종료 → 스냅샷에 남은 자손 종료 순서로 끝낸다. 상태 전이는 [ADR 0005](adr/0005-agent-event-model.md) 결정 4에 있다.
- 셸이 죽으면 런타임이 스스로 끝내고 자식 프로세스를 정리한다 (방식은 ADR 0001). OS별 보장 범위는 [셸과 런타임의 연결](#셸과-런타임의-연결)에 있다.

### ACP client + session manager

- 어댑터 프로세스마다 ACP 연결이 1개이고, 실행기가 달라도 같은 인터페이스를 쓴다. `initialize`(버전, capability) → 인증 → `session/new` 순서이며, 세부는 ADR 0003과 0004에 있다 (SCRUM-9, 23, 66).
- (`agentId`, `sessionId`)를 매핑한다. 세션마다 진행 중인 실행은 1개이고, 새 `session/prompt`는 앞 실행의 응답을 받은 뒤에만 보낸다 (SCRUM-24).
- 어댑터가 클라이언트에 보내는 요청에 응답한다. `session/request_permission`은 Permission broker로 넘기고, fs와 terminal 메서드는 MVP에서 광고하지 않는다.

### Event normalizer

- `session/update`, 요청과 응답, 프로세스 생명주기를 AgentEvent로 바꾸고 `seq`와 `runId`를 붙인다 ([ADR 0005](adr/0005-agent-event-model.md), SCRUM-23, 66). 생성된 Java `AgentEvent` 인터페이스가 envelope 필드의 getter와 setter를 선언하므로, 이벤트 종류마다 따로 처리하지 않고 붙이고 에이전트별 마지막 `seq`를 기록한다.
- 에이전트별 현재 상태를 계산해서 상태 저장소에 반영한다. 상태 저장소가 단일 출처다. 업스트림 `AgentStateStore`의 개념을 이어받되, broadcast 메시지는 타입 검사를 한다 (SCRUM-19).
- 알 수 없는 update는 `unknownUpdate`로, Tool 호출은 ADR 0005의 병합 규칙대로 바꾼다. 내용을 추측해서 채우지 않는다 (SCRUM-27, 50).

### Permission broker

- 대기 중인 요청을 등록한다: `permissionId` ↔ (`agentId`, JSON-RPC 요청 id) (SCRUM-33).
- MVP 기본 정책은 [ADR 0005](adr/0005-agent-event-model.md) 결정 7에 있다. 에이전트별 세부 정책은 post-MVP다 (SCRUM-62).
- 실행을 취소하거나 에이전트를 끄면 대기 중인 요청 전부에 `cancelled`로 응답한다 (SCRUM-33).

### Persistence

- 셸이 넘긴 앱 데이터 디렉터리(`AGENTS_ROOM_DATA_DIR`) 아래 `runtime/`에 저장한다. 업스트림이 쓰는 `~/.pixel-agents/`와는 공유하지 않는다. 프로젝트 설정도 사용자의 프로젝트 폴더 안에 쓰지 않는다 (제안).

  ```text
  <AGENTS_ROOM_DATA_DIR>/runtime/
    recent-projects.json             연 프로젝트 목록, 최근 순 (projectId 대응표를 겸한다)
    projects/<projectId>/agents.json 프로젝트별 AgentConfig
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

## 셸과 런타임의 연결

[ADR 0001](adr/0001-desktop-framework.md) 결정 4, 5를 구체화한 계약이다. 데스크톱 셸과 로컬 런타임이 똑같이 따른다.

| 항목        | 계약                                                                                                                                                                                                                                                                                                                                                           |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 실행        | `<java> -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar <jar>`. stdin, stdout, stderr는 모두 pipe다. 셸은 런타임이 살아 있는 동안 stdin을 열어 둔다                                                                                                                                                                                 |
| env         | `AGENTS_ROOM_TOKEN`: 실행마다 새로 만든 32바이트 난수(base64url). `AGENTS_ROOM_DATA_DIR`: 앱 데이터 디렉터리(절대 경로). `AGENTS_ROOM_ALLOWED_ORIGINS`: 쉼표로 구분한 Origin 목록. 토큰이 없거나 43자 base64url이 아니면, 또는 데이터 디렉터리가 없거나 절대 경로가 아니면 시작하지 않는다. 이때 stdout에는 아무것도 쓰지 않고 0이 아닌 코드로 끝난다          |
| Origin 목록 | 배포본은 `app://bundle`이고, 개발 중에는 `http://localhost:5173`을 더한다. 변수가 없으면 `app://bundle`만 허용한다                                                                                                                                                                                                                                             |
| 준비 줄     | 127.0.0.1의 임시 포트에 바인딩하고, 준비되면 stdout에 `AGENTS_ROOM_READY {"port":<int>}`와 `\n`을 UTF-8로 한 번 쓴다. 그 밖의 출력(로그 전부)은 stderr로 간다. 읽는 쪽은 줄 끝의 `\r`을 지운다                                                                                                                                                                 |
| 렌더러 접속 | `ws://127.0.0.1:<port>/ws?token=<token>`. 토큰이 없거나 틀리면 업그레이드 전에 401이다(상수 시간 비교). Origin 헤더가 있는데 목록에 없으면 403이다. Origin이 없는 클라이언트(Node, curl)도 토큰은 있어야 한다                                                                                                                                                  |
| 로그        | access log는 없다. 마스킹 계층이 토큰 값, `token=`, `*_API_KEY`·`*TOKEN*`·`*SECRET*`·`Authorization` 뒤의 값, Bearer 값, `AKIA`·`ASIA`·`sk-` 형태의 키를 가린다 ([ADR 0004](adr/0004-model-authentication.md) 결정 8). logback을 거치지 않고 stderr에 쓰는 출력(`System.out`, `System.err`)도 줄마다 같은 계층을 지난다. 잡히지 않은 예외는 logback으로 남긴다 |
| 종료        | stdin EOF나 부모 프로세스 종료를 감지하면 Spring 컨텍스트를 닫고 끝낸다. 이때 살아 있는 자손 프로세스를 모두 죽인다. 5초 안에 끝나지 않으면 남은 자손을 죽이고 강제로 끝낸다. 런타임이 코드를 실행하지 못하고 죽을 때의 정리는 아래에 있다                                                                                                                     |
| 개발 실행   | 런타임 빌드가 `runtime/build/dev-runtime.json`(`{"java": JDK 25의 java 경로, "jar": boot jar 경로}`)을 쓴다. 셸은 개발 중에 이 파일을 읽는다. env `AGENTS_ROOM_RUNTIME_COMMAND`(JSON 배열 argv)가 있으면 그쪽이 우선한다(테스트용)                                                                                                                             |

- 자식 프로세스 정리를 보장하는 범위는 OS마다 다르다.
  - Windows: 런타임은 시작할 때 자기만 가진 job object(`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`)에 들어간다. 그 뒤에 생기는 자식은 누가 띄우든(ACP SDK 포함) 이 job 안에서 생기고, 그 자손도 그렇다. 런타임이 어떻게 끝나든 Windows가 이 job의 프로세스를 모두 죽인다. 부모가 먼저 끝난 손자도 포함된다.
  - 이것이 필요한 이유: Electron 셸은 런타임을 libuv의 job에 넣는다. 셸이 `/T` 없이 죽으면(작업 관리자의 작업 끝내기 등) Windows가 런타임을 바로 죽이므로, 런타임의 감시 코드는 돌지 못한다. libuv의 job은 자식이 조용히 빠져나가게 두므로, 런타임 자신의 job이 없으면 런타임의 자식이 남는다.
  - 그 밖의 OS: 정상 종료할 때만 정리하고, 런타임의 프로세스 트리에 아직 있는 자손만 죽인다. 부모가 먼저 끝난 손자와, 런타임이 `SIGKILL`로 죽었을 때의 자식은 남을 수 있다 (macOS·Linux 배포는 SCRUM-64).
- 셸은 Gradle을 직접 부르지 않는다. Gradle 출력이 stdout에 섞이면 준비 줄을 읽을 수 없다.
- 렌더러의 Origin은 `app://bundle`이다. 렌더러는 preload의 `window.agentsRoom.getRuntimeEndpoint()`로 `{url, token}`을 받는다.

## 로컬 런타임 코드 구성

`runtime/`은 Gradle(Kotlin DSL) 프로젝트다. Gradle 9.8.2 wrapper, JDK 25 toolchain, Spring Boot 4.1.2 WebFlux, Jackson 3을 쓴다 ([ADR 0002](adr/0002-java-spring-runtime.md)). 패키지 루트는 `agentsroom.runtime`이다.

| 패키지        | 맡는 일                                                                                                                          |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `config`      | 셸이 넘긴 env 읽기(`RuntimeEnvironment`), 127.0.0.1과 임시 포트 고정                                                             |
| `shell`       | stdout 제어 경로(`ControlChannel`), 준비 줄, stdin EOF와 부모 종료 감시, 종료 시간 제한                                          |
| `process`     | Windows job object(`ChildProcessContainment`), 프로세스 트리 종료(`ProcessTreeKiller`): 자손 목록을 먼저 잡고 모두 강제 종료한다 |
| `logging`     | 마스킹 계층(`SecretMasker`, logback `%mask`, stderr 전체를 가리는 `MaskingOutputStream`)                                         |
| `gateway`     | Renderer gateway: `/ws`, 토큰과 Origin 게이트, 연결별 세션, 브로드캐스트, 계약 코덱과 명령 형식 검사, 명령 처리                  |
| `project`     | 경로 검증(`ProjectPathValidator`), 최소 파일 트리(`FileTreeReader`)                                                              |
| `workspace`   | Project/Workspace service: 현재 프로젝트, 최근 목록, 스냅샷 데이터                                                               |
| `agent`       | AgentConfig, 기본 에이전트, 정적 실행기 카탈로그(`HarnessCatalog`)                                                               |
| `persistence` | 데이터 디렉터리의 JSON 파일(원자적 저장, 손상 파일 보존)                                                                         |

- 메시지 타입은 `core/asyncapi.yaml`에서 생성한 Java 바인딩(패키지 `agentsroom.contract`)이다 ([ADR 0005](adr/0005-agent-event-model.md) 결정 11).
  - Gradle 작업 `generateContractBindings`가 빌드 때 루트에서 `node --import tsx scripts/generate-messages.ts --java`(`npm run asyncapi:generate:java`와 같은 명령)를 실행해 `runtime/build/generated/sources/contract`에 만든다. 커밋하지 않는다. npm과 cmd.exe를 거치지 않으므로 Windows와 Linux에서 인자와 따옴표 처리가 같다.
  - 생성 코드는 별도 source set(`contract`)에서 컴파일한다. Modelina 출력에 불필요한 캐스트 같은 lint 경고가 있어서, 손으로 쓴 코드의 `-Xlint:all -Werror`를 풀지 않으려는 것이다.
  - 상속 프레임(`x-legacy`)은 바인딩에 없다. 그래서 런타임은 상속 프레임을 보내지 않고, 상속 명령은 모르는 명령처럼 거절한다.
- 코덱(`ContractMessageCodec`)은 명령을 바인딩에 넣기 전에 JSON 트리에서 형식을 검사한다(`CommandShapes`). 검사 항목은 필수 필드, 타입(문자열, null을 허용하는 문자열, 객체), 정의되지 않은 필드, 중복 키, 뒤에 남은 내용이다. 생성된 클래스는 이 중 아무것도 강제하지 않고, 빠진 필드와 `null`도 구분하지 못한다.
  - 프레임은 두 번 읽는다. 먼저 트리를 만들지 않는 스트리밍 읽기로 JSON인지와 거절에 돌려줄 `type`과 id를 찾는다. 이 읽기에는 깊이·숫자 길이·이름 길이 제한이 없으므로(메모리는 1 MiB 프레임 제한이 묶는다) JSON인 프레임은 모두 `type`과 id를 돌려받는다. 같은 키가 두 번 나오면 `JSON.parse`처럼 마지막 값을 쓴다.
  - 그다음 Jackson 기본 제한(깊이 500, 숫자 길이 1000, 이름 길이 50000)으로 트리를 읽는다. 제한을 넘으면 "Frame exceeds the runtime's JSON limits", 같은 키가 두 번 나오면 "'path' appears twice"처럼 그 키 이름으로 거절한다. "Frame is not JSON"은 `JSON.parse`도 받지 않는 프레임에만 쓴다.
- 명령은 형식 → id → 구현 여부 순서로 확인한다 ([ADR 0005](adr/0005-agent-event-model.md) 결정 2).
  - 형식이 틀리거나 모르는 명령이면 `INVALID_MESSAGE`, 모르는 `projectId`면 `UNKNOWN_PROJECT`, 모르는 `agentId`면 `UNKNOWN_AGENT`, 대기 중이 아닌 `permissionId`면 `UNKNOWN_PERMISSION`이다.
  - 아는 `projectId`는 최근 목록에 있는 프로젝트다(이번 실행 전용 id 포함). 아는 `agentId`는 현재 프로젝트의 에이전트다. `saveAgentConfig`가 둘 다 담으면 그 에이전트가 그 프로젝트의 것이어야 한다. 에이전트가 아직 실행되지 않으므로 대기 중인 권한 요청은 없고, `respondPermission`은 늘 `UNKNOWN_PERMISSION`이다.
  - `commandRejected`는 명령의 `type`과, 명령에 들어 있던 `agentId`·`projectId`·`permissionId`(`saveAgentConfig`는 `config` 안의 값)를 돌려준다. `message`는 마스킹 계층을 거치고, 필드 이름은 쓰지만 값은 옮기지 않는다. id는 자기 필드로만 돌아간다.
- 지금 처리하는 명령은 `openProject`뿐이다. 연결될 때마다 먼저 `snapshot`을 보낸다. 성공하면 모든 렌더러에 `projectOpened`와 `agentsUpdated`를 보내고, 실패하면 보낸 렌더러에만 `projectOpenFailed`를 보낸다. 계약의 다른 명령은 id를 확인한 뒤 `commandRejected`(`NOT_IMPLEMENTED`)로 답한다.
- `AgentSummary`의 `readiness`, `reportsToolDetails`, `reportsSubagentSessions`는 정적 실행기 카탈로그(`HarnessCatalog`)에서 온다 ([ux.md](ux.md#관찰한-것만-보여준다) R3, R4).
  - 카탈로그는 `codex`를 `openai` + `chat-gpt`로 실행한다. codex-acp는 Tool 호출에 kind와 status를 붙여 보내므로 `reportsToolDetails: true`다. agents-room이 `subagents` capability를 광고하지 않으면 서브에이전트도 일반 Tool 호출로 보내고 자식 세션을 알리지 않으므로 `reportsSubagentSessions: false`다 (Sprint 0 제안).
  - 카탈로그에 없는 실행기나 조합(예: Codex + Bedrock, [ADR 0004](adr/0004-model-authentication.md)의 spike 전)은 `unsupported`다. 모르는 실행기는 두 값이 모두 `false`다.
  - `configured`는 정적 설정이 맞고 어댑터 설치가 있을 때만 보낸다. 이 런타임은 아직 어댑터를 띄우지 못하므로, 카탈로그가 실행하는 조합도 지금은 `executable_missing`이다 (Sprint 0 중간값). 그래서 렌더러는 시작할 수 없는 에이전트에 "에이전트 시작"을 켜지 않는다.
  - 어댑터 실행(SCRUM-23, 65)이 설치 확인(어댑터 경로와 절대 경로 `node`가 있으면 `configured`, 없으면 `executable_missing`)과 인증 상태(`auth_required`)를 붙인다.
- 계약 테스트: 런타임이 만드는 모든 프레임과 WebSocket 대화로 받은 프레임을 `node scripts/check-contract-fixtures.mjs --verdicts <dir>`로 검사한다. `npm run contract:check`와 같은 ajv 설정이다. 그 밖에 다음을 확인한다.
  - 유효 fixture는 모두 코덱을 통과한다. 명령은 필드를 잃지 않고 바인딩되고, 서버 프레임은 fixture와 같은 JSON으로 다시 인코딩된다. 무효 명령 fixture는 모두 거절한다.
  - 형식 검사표의 명령과 필드는 생성된 클래스와 같다.
  - 유효 명령 fixture와 그 변형(필드 삭제, `null`, 다른 타입, 필드 추가)에서 코덱은 ajv와 같은 판정을 낸다.
  - Java JSON Schema 라이브러리를 쓰지 않는 이유는, 검사기가 둘이면 스키마(`if`/`then`/`else`, 타입 없는 `not`, AsyncAPI `discriminator`)를 서로 다르게 읽을 수 있어서다. node는 바인딩 생성 때문에 이미 필요하다.
- 빌드와 테스트는 루트에서 `npm run runtime:build`(빌드, 테스트, `dev-runtime.json`)와 `npm run runtime:test`로 한다. `scripts/gradle.mjs`가 OS에 맞는 wrapper를 부른다. node가 PATH에 있어야 하고, 루트에서 먼저 `npm ci`(또는 `npm install`)를 해야 한다.
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

1. 사용자가 프로젝트 열기를 요청하면 셸이 OS 폴더 선택기를 연다. 취소하면 아무것도 바뀌지 않는다 (SCRUM-18, 31).
2. 고른 경로가 `openProject`로 런타임에 전달된다 (전달 경로는 ADR 0001).
3. Project/Workspace service가 경로를 검증하고, `projectId`를 발급하고, 최근 목록을 갱신한다. 실패하면 `projectOpenFailed {code}`를 보내고 다시 고르게 한다.
4. 렌더러에 `projectOpened`(프로젝트 이름, 경로, 최소 파일 트리)를 보내고, 이어서 그 프로젝트의 에이전트 목록(`agentsUpdated`)을 보낸다.

### 에이전트 시작

1. `startAgent {agentId}`를 받으면 AgentConfig를 확인하고 명령과 env를 구성한다. 상태는 `starting`이고, 아래 단계 중 하나가 실패하면 어댑터 프로세스 트리를 끝내고 `error`가 된다 (SCRUM-23, 66).
2. 어댑터 프로세스를 실행한다 (`cwd` = 프로젝트 경로).
3. `initialize`(`protocolVersion` 1, client capability)를 보내 버전을 확인하고, 인증을 확인한다 ([ADR 0004](adr/0004-model-authentication.md)).
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
2. 런타임이 stdin EOF나 부모 프로세스 종료를 감지한다 (방식은 ADR 0001). 셸이 비정상으로 끝나도 같다.
3. 런타임이 모든 어댑터 프로세스 트리를 정리하고 끝난다. OS별 보장 범위는 [셸과 런타임의 연결](#셸과-런타임의-연결)에 있다.

## 현재 코드와 목표 구조

판정은 계획이다. 파일 단위 판정과 이유는 [upstream.md](upstream.md#상속-범위)에만 있다.

- 그대로 쓴다: 픽셀 월드(`webview-ui/src/office/`의 엔진·레이아웃·에디터·스프라이트, `OfficeCanvas.tsx`), `WebSocketTransport`, `core/src/transport.ts` 같은 렌더러 공용 코드.
- 고쳐 쓴다: 렌더러의 메시지 처리(`useExtensionMessages.ts`, `toolUtils.ts`)와 전송 분기(`transport/index.ts`), 계약(`core/asyncapi.yaml`, ADR 0005), e2e. 계약에는 agents-room 프레임과 명령을 상속 프레임 옆에 더했다. 상속 프레임은 `x-legacy: true`이고, `server/`와 `adapters/vscode/`를 지울 때 함께 지운다.
- 개념만 옮긴다: `server/`(Node/Fastify)는 Java로 다시 구현하고 상태 저장소, 연결 시 스냅샷, 원자적 저장 같은 구조만 가져온다 ([ADR 0002](adr/0002-java-spring-runtime.md)).
- 버린다: Claude hooks와 JSONL 처리(`server/src/providers/hook/**`, `fileWatcher.ts`, `transcriptParser.ts`), `adapters/vscode/**`.
- 새로 만든다: Detail Panel, 권한 대화상자, 에이전트 설정 화면([ux.md](ux.md#화면-구성)), 데스크톱 셸, Java 런타임.

## 관련 문서

- [ux.md](ux.md): [화면 구성](ux.md#화면-구성), [상태 어휘](ux.md#상태-어휘), [관찰한 것만 보여준다](ux.md#관찰한-것만-보여준다), [문구 목록](ux.md#문구-목록)
- ADR: [0001 데스크톱 셸 프레임워크](adr/0001-desktop-framework.md), [0002 Java/Spring 로컬 런타임](adr/0002-java-spring-runtime.md), [0003 ACP 연동 방식](adr/0003-acp-integration.md), [0004 모델 제공자 인증과 자격증명](adr/0004-model-authentication.md), [0005 AgentEvent 모델](adr/0005-agent-event-model.md), [목록과 작성 규칙](adr/README.md)
- [upstream.md](upstream.md): [포크 기준](upstream.md#포크-기준), [상속 범위](upstream.md#상속-범위), [레거시 참조](upstream.md#레거시-참조)
